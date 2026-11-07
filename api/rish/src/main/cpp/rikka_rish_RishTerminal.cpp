#include <jni.h>
#include <unistd.h>
#include <termios.h>
#include <fcntl.h>
#include <cerrno>
#include <cstdlib>
#include <android/log.h>
#include <pthread.h>
#include <memory>
#include <atomic>
#include "logging.h"
#include "pts.h"

static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t output_cond = PTHREAD_COND_INITIALIZER;
static bool output_finished = false;
static int winch_pipe[2] = {-1, -1};
static struct termios old_stdin{};
static int tty_in_raw = 0;
// Standard I/O and SIGWINCH are process resources. Never let another terminal reset them.
static std::atomic_bool terminal_started{false};

static int64_t getWindowSize(int fd) {
    static_assert(sizeof(jlong) == sizeof(winsize));
    jlong screen_size;

    if (ioctl(fd, TIOCGWINSZ, &screen_size) == -1) {
        PLOGE("ioctl TIOCGWINSZ");
        return 0;
    }

    return screen_size;
}

static jbyte RishTerminal_prepare(JNIEnv *env, jclass clazz) {
    jbyte atty = 0;
    if (isatty(STDIN_FILENO)) atty |= ATTY_IN;
    if (isatty(STDOUT_FILENO)) atty |= ATTY_OUT;
    if (isatty(STDERR_FILENO)) atty |= ATTY_ERR;

    LOGD("istty stdin %d stdout %d stderr %d", (atty & ATTY_IN) ? 1 : 0, (atty & ATTY_OUT) ? 1 : 0,
         (atty & ATTY_ERR) ? 1 : 0);


    return atty;
}

static jint RishTerminal_start(
        JNIEnv *env, jclass clazz, jbyte tty,
        jint stdin_pipe, jint stdout_pipe, jint stderr_pipe) {

    int tty_fd;
    bool in_tty = tty & ATTY_IN;
    bool out_tty = tty & ATTY_OUT;
    bool err_tty = tty & ATTY_ERR;
    if (in_tty) {
        tty_fd = STDIN_FILENO;
    } else if (out_tty) {
        tty_fd = STDOUT_FILENO;
    } else if (err_tty) {
        tty_fd = STDERR_FILENO;
    } else {
        tty_fd = -1;
    }

    if (terminal_started.exchange(true)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "Only one native rish terminal per process is supported");
        return -1;
    }
    // Validate all duplicates before changing tty state or starting any relay.
    int input = fcntl(stdin_pipe, F_DUPFD_CLOEXEC, 0);
    int output = input < 0 ? -1 : fcntl(stdout_pipe, F_DUPFD_CLOEXEC, 0);
    int error = (output < 0 || err_tty) ? -1 : fcntl(stderr_pipe, F_DUPFD_CLOEXEC, 0);
    if (input < 0 || output < 0 || (!err_tty && error < 0)) {
        if (input >= 0) close(input);
        if (output >= 0) close(output);
        if (error >= 0) close(error);
        terminal_started.store(false);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "Unable to duplicate rish terminal pipe descriptors");
        return -1;
    }
    stdin_pipe = input;
    stdout_pipe = output;
    stderr_pipe = error;
    if (tty == ATTY_ALL && make_tty_raw(tty_fd, old_stdin) == 0) tty_in_raw = 1;

    pthread_mutex_lock(&mutex);
    output_finished = false;
    pthread_mutex_unlock(&mutex);

    // Wait for both output streams to drain before reporting completion.
    auto pending = std::make_shared<std::atomic_int>(err_tty ? 1 : 2);
    auto func = [=](bool) {
        if (pending->fetch_sub(1) != 1) {
            return;
        }

        LOGI("remote exit");

        if (tty_in_raw) {
            if (restore_fd(tty_fd, old_stdin) == 0) {
                tty_in_raw = 0;
            }
        }

        pthread_mutex_lock(&mutex);
        output_finished = true;
        pthread_cond_broadcast(&output_cond);
        pthread_mutex_unlock(&mutex);
        char b = 1;
        if (winch_pipe[1] != -1) (void) write(winch_pipe[1], &b, 1);
    };

    transfer_async(STDIN_FILENO, stdin_pipe, nullptr, false, true);
    transfer_async(stdout_pipe, STDOUT_FILENO, func, true, false);
    if (!err_tty) {
        transfer_async(stderr_pipe, STDERR_FILENO, func, true, false);
    }

    auto sigwinch_handler = [](int sig) {
        // async-signal-safe: notify the waiter through a self-pipe instead of
        // touching a mutex from the signal handler.
        int saved_errno = errno;
        char b = 1;
        if (winch_pipe[1] != -1) {
            ssize_t ignored = write(winch_pipe[1], &b, 1);
            (void) ignored;
        }
        errno = saved_errno;
    };

    signal(SIGWINCH, sigwinch_handler);
    sigset_t winch;
    sigemptyset(&winch);
    sigaddset(&winch, SIGWINCH);
    pthread_sigmask(SIG_UNBLOCK, &winch, nullptr);
    return tty_fd;
}

static jlong RishTerminal_waitForWindowSizeChange(JNIEnv *env, jclass clazz, jint fd) {
    if (winch_pipe[0] == -1) return -1;
    pthread_mutex_lock(&mutex);
    bool done = output_finished;
    pthread_mutex_unlock(&mutex);
    if (done) return -1;
    char b;
    if (TEMP_FAILURE_RETRY(read(winch_pipe[0], &b, 1)) <= 0) return -1;
    pthread_mutex_lock(&mutex);
    done = output_finished;
    pthread_mutex_unlock(&mutex);
    return done ? -1 : (jlong) getWindowSize(fd);
}

static void RishTerminal_waitForProcessExit(JNIEnv *, jclass) {
    pthread_mutex_lock(&mutex);
    while (!output_finished) pthread_cond_wait(&output_cond, &mutex);
    pthread_mutex_unlock(&mutex);
}

int rikka_rish_RishTerminal_registerNatives(JNIEnv *env) {

    if (pipe2(winch_pipe, O_CLOEXEC) == 0) {
        int flags = fcntl(winch_pipe[1], F_GETFL);
        if (flags != -1) {
            fcntl(winch_pipe[1], F_SETFL, flags | O_NONBLOCK);
        }
    } else {
        PLOGE("pipe2");
    }

    auto clazz = env->FindClass("rikka/rish/RishTerminal");
    JNINativeMethod methods[] = {
            {"prepare",                 "()B",     (void *) RishTerminal_prepare},
            {"start",                   "(BIII)I", (void *) RishTerminal_start},
            {"waitForWindowSizeChange", "(I)J",    (void *) RishTerminal_waitForWindowSizeChange},
            {"waitForProcessExit",      "()V",     (void *) RishTerminal_waitForProcessExit},
    };
    return env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0]));
}
