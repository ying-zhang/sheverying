#include <jni.h>
#include <unistd.h>
#include <termios.h>
#include <fcntl.h>
#include <cerrno>
#include <cstdlib>
#include <wait.h>
#include <android/log.h>
#include <pthread.h>
#include <memory>
#include <atomic>
#include <mutex>
#include <map>
#include "logging.h"
#include "pts.h"

static int setWindowSize(int ptmx, jlong size) {
    static_assert(sizeof(jlong) == sizeof(winsize));
    winsize w = *((winsize *) &size);

    LOGD("setWindowSize %d %d %d %d", w.ws_row, w.ws_col, w.ws_xpixel, w.ws_ypixel);

    if (ioctl(ptmx, TIOCSWINSZ, &w) == -1) {
        PLOGE("ioctl TIOCGWINSZ");
        return -1;
    }
    return 0;
}

static void *xmalloc(JNIEnv *env, size_t size) {
    void *p = malloc(size);
    if (p == nullptr)
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), nullptr);
    else
        memset(p, 0, size);
    return p;
}

#define NEW(type, n) ((type *) xmalloc(env, (n) * sizeof(type)))

static const char *getBytes(JNIEnv *env, jbyteArray arr) {
    return arr == nullptr ? nullptr : (const char *) env->GetByteArrayElements(arr, nullptr);
}

static void closeIfValid(int fd) {
    if (fd != -1) {
        close(fd);
    }
}

static void releaseBytes(JNIEnv *env, jbyteArray arr, const char *parr) {
    if (parr != nullptr)
        env->ReleaseByteArrayElements(arr, (jbyte *) parr, JNI_ABORT);
}

static void initVectorFromBlock(const char **vector, const char *block, int count) {
    int i;
    const char *p;
    for (i = 0, p = block; i < count; i++) {
        vector[i] = p;
        while (*(p++));
    }
    vector[count] = nullptr;
}

// Relay callbacks retain the child identity, even after the numeric PID is reused.
struct ChildState {
    explicit ChildState(pid_t value) : pid(value) {}
    pid_t pid;
    std::mutex lock;
    bool reaped = false;
};
static std::mutex children_lock;
static std::map<pid_t, std::shared_ptr<ChildState>> children;

static void kill_child(const std::shared_ptr<ChildState>& child) {
    std::lock_guard<std::mutex> guard(child->lock);
    if (child->reaped) return;
    siginfo_t info{};
    if (waitid(P_PID, child->pid, &info, WEXITED | WNOHANG | WNOWAIT) != 0) return;
    // Our waiter cannot reap while this lock is held. setsid failure never permits group kill.
    if (getpgid(child->pid) == child->pid) kill(-child->pid, SIGKILL);
    kill(child->pid, SIGKILL);
}

static jintArray RishHost_startHost(
        JNIEnv *env, jclass clazz,
        jbyteArray argBlock, jint argc,
        jbyteArray envBlock, jint envc,
        jbyteArray dirBlock,
        jbyte tty,
        jint stdin_read, jint stdout_write, jint stderr_write) {

    bool in_tty = tty & ATTY_IN;
    bool out_tty = tty & ATTY_OUT;
    bool err_tty = tty & ATTY_ERR;

    int ptmx = -1;
    if (tty) {
        ptmx = open_ptmx();
        if (ptmx == -1) {
            closeIfValid(stdin_read);
            closeIfValid(stdout_write);
            closeIfValid(stderr_write);
            env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Unable to open ptmx");
            return nullptr;
        }
        LOGD("ptmx %d", ptmx);
    }

    int stdin_pipe[2]{-1, -1}, stdout_pipe[2]{-1, -1}, stderr_pipe[2]{-1, -1};
    auto cleanup = [&]() {
        closeIfValid(ptmx);
        for (int fd : stdin_pipe) closeIfValid(fd);
        for (int fd : stdout_pipe) closeIfValid(fd);
        for (int fd : stderr_pipe) closeIfValid(fd);
        closeIfValid(stdin_read);
        closeIfValid(stdout_write);
        closeIfValid(stderr_write);
    };
    if ((!in_tty && pipe2(stdin_pipe, O_CLOEXEC) == -1) ||
        (!out_tty && pipe2(stdout_pipe, O_CLOEXEC) == -1) ||
        (!err_tty && pipe2(stderr_pipe, O_CLOEXEC) == -1)) {
        cleanup();
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Unable to create host pipes");
        return nullptr;
    }

    const char *pargBlock = getBytes(env, argBlock);
    const char **argv = NEW(const char *, argc + 2);
    if (argv == nullptr) {
        cleanup();
        releaseBytes(env, argBlock, pargBlock);
        return nullptr;
    }
    argv[0] = "/system/bin/sh";
    initVectorFromBlock(argv + 1, pargBlock, argc);

    for (int i = 0; i < argc + 2; ++i) {
        LOGD("arg%d=%s", i, argv[i]);
    }

    const char **envv = nullptr;
    const char *penvBlock = nullptr;
    if (envc > 0) {
        penvBlock = getBytes(env, envBlock);
        envv = NEW(const char *, envc + 1);
        if (envv == nullptr) {
            cleanup();
            free(argv);
            releaseBytes(env, argBlock, pargBlock);
            releaseBytes(env, envBlock, penvBlock);
            return nullptr;
        }

        initVectorFromBlock(envv, penvBlock, envc);
    }

    const char *pdir = nullptr;
    if (dirBlock) {
        pdir = getBytes(env, dirBlock);
    }

    auto pid = fork();
    if (pid == -1) {
        free(argv);
        free(envv);
        releaseBytes(env, argBlock, pargBlock);
        releaseBytes(env, envBlock, penvBlock);
        releaseBytes(env, dirBlock, pdir);

        closeIfValid(ptmx);
        closeIfValid(stdin_pipe[0]);
        closeIfValid(stdin_pipe[1]);
        closeIfValid(stdout_pipe[0]);
        closeIfValid(stdout_pipe[1]);
        closeIfValid(stderr_pipe[0]);
        closeIfValid(stderr_pipe[1]);
        closeIfValid(stdin_read);
        closeIfValid(stdout_write);
        closeIfValid(stderr_write);

        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Unable to fork");
        return nullptr;
    }

    if (pid > 0) {
        free(argv);
        free(envv);
        releaseBytes(env, argBlock, pargBlock);
        releaseBytes(env, envBlock, penvBlock);
        releaseBytes(env, dirBlock, pdir);

        auto child = std::make_shared<ChildState>(pid);
        {
            std::lock_guard<std::mutex> guard(children_lock);
            children[pid] = child;
        }
        auto called = std::make_shared<std::atomic_bool>(false);
        auto func = [child, called](bool failed) {
            if (!failed || called->exchange(true)) {
                return;
            }

            LOGW("client dead, kill forked process");
            kill_child(child);
        };

        if (!in_tty) fcntl(stdin_pipe[1], F_SETFD, FD_CLOEXEC);
        if (!out_tty) fcntl(stdout_pipe[0], F_SETFD, FD_CLOEXEC);
        if (!err_tty) fcntl(stderr_pipe[0], F_SETFD, FD_CLOEXEC);

        auto pty = [ptmx, child]() {
            int fd = fcntl(ptmx, F_DUPFD_CLOEXEC, 0);
            if (fd == -1) {
                PLOGE("dup ptmx");
                kill_child(child);
            }
            return fd;
        };

        if (in_tty) {
            int fd = pty();
            if (fd != -1) {
                transfer_async(stdin_read, fd, [fd, child](bool failed) {
                    // Closing one PTY master duplicate cannot half-close terminal input.
                    // In a mixed tty setup the local canonical tty consumes Ctrl-D and
                    // delivers real EOF; translate that back to the remote tty's VEOF.
                    termios mode{};
                    if (!failed && tcgetattr(fd, &mode) == 0 && (mode.c_lflag & ICANON)
                            && mode.c_cc[VEOF] != _POSIX_VDISABLE) {
                        char eof = static_cast<char>(mode.c_cc[VEOF]);
                        (void) TEMP_FAILURE_RETRY(write(fd, &eof, 1));
                    }
                    close(fd);
                    if (failed) kill_child(child);
                }, true, false);
            } else {
                close(stdin_read);
            }
        } else {
            transfer_async(stdin_read, stdin_pipe[1]);
            close(stdin_pipe[0]);
        }

        if (out_tty) {
            int fd = pty();
            if (fd != -1) {
                transfer_async(fd, stdout_write, func, true, true, true);
            } else {
                close(stdout_write);
            }
        } else {
            transfer_async(stdout_pipe[0], stdout_write, func, true, true, true);
            close(stdout_pipe[1]);

            if (in_tty || err_tty) {
                int null_fd = open("/dev/null", O_WRONLY | O_CLOEXEC);
                if (null_fd == -1) {
                    PLOGE("open /dev/null");
                    kill_child(child);
                } else {
                    int fd = pty();
                    if (fd != -1) {
                        transfer_async(fd, null_fd, [child, called](bool failed) {
                            if (failed && !called->exchange(true)) kill_child(child);
                        });
                    } else {
                        close(null_fd);
                    }
                }
            }
        }

        if (!err_tty) {
            transfer_async(stderr_pipe[0], stderr_write);
            close(stderr_pipe[1]);
        }

        auto result = env->NewIntArray(2);
        env->SetIntArrayRegion(result, 0, 1, &pid);
        env->SetIntArrayRegion(result, 1, 1, &ptmx);
        return result;
    } else {
        if (setsid() < 0) {
            PLOGE("setsid");
            exit(1);
        }

        if (pdir) {
            LOGD("attempt to chdir %s", pdir);

            if (access(pdir, X_OK) == 0) {
                if (chdir(pdir) == -1) {
                    PLOGE("chdir %s", pdir);
                } else {
                    LOGD("chdir %s", pdir);
                }
            } else {
                PLOGE("access %s", pdir);
            }
        }

        int pts = -1;
        if (tty) {
            char pts_slave[PATH_MAX]{0};
            if (ptsname_r(ptmx, pts_slave, PATH_MAX - 1) == -1) {
                PLOGE("ptsname_r");
                exit(1);
            }

            if ((pts = open(pts_slave, O_RDWR)) == -1) {
                PLOGE("open %s", pts_slave);
            }
            LOGD("pts %d", pts);
        } else {
            LOGD("no need pts");
        }

        if (in_tty) {
            dup2(pts, STDIN_FILENO);
            LOGD("pts -> in");
        } else {
            dup2(stdin_pipe[0], STDIN_FILENO);
            close(stdin_pipe[1]);
            LOGD("pipe -> in");
        }

        if (out_tty) {
            dup2(pts, STDOUT_FILENO);
            LOGD("pts -> out");
        } else {
            dup2(stdout_pipe[1], STDOUT_FILENO);
            close(stdout_pipe[0]);
            LOGD("pipe -> out");
        }

        if (err_tty) {
            dup2(pts, STDERR_FILENO);
            LOGD("pts -> err");
        } else {
            dup2(stderr_pipe[1], STDERR_FILENO);
            close(stderr_pipe[0]);
            LOGD("pipe -> err");
        }

        LOGD("istty stdin %d stdout %d stderr %d", isatty(STDIN_FILENO), isatty(STDOUT_FILENO), isatty(STDERR_FILENO));

        if (pts != -1) {
            close(pts);
        }

        if (envv) {
            if (execvpe("/system/bin/sh", (char *const *) argv, (char *const *) envv) == -1) {
                PLOGE("execv");
                exit(1);
            }
        } else {
            if (execvp("/system/bin/sh", (char *const *) argv) == -1) {
                PLOGE("execv");
                exit(1);
            }
        }
        return nullptr;
    }
}

static void RishHost_setWindowSize(JNIEnv *env, jclass clazz, jint ptmx, jlong size) {
    setWindowSize(ptmx, size);
}

static jint RishHost_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    LOGD("waitFor %d", pid);
    if (pid <= 0) {
        return -1;
    }

    std::shared_ptr<ChildState> child;
    {
        std::lock_guard<std::mutex> guard(children_lock);
        auto found = children.find(pid);
        if (found == children.end()) return -1;
        child = found->second;
    }
    // Block without holding the signal lock, but leave the zombie unreaped to reserve its PID.
    siginfo_t info{};
    int observed = TEMP_FAILURE_RETRY(waitid(P_PID, pid, &info, WEXITED | WNOWAIT));
    int status = 0;
    pid_t ret;
    {
        std::lock_guard<std::mutex> guard(child->lock);
        ret = observed == 0 ? TEMP_FAILURE_RETRY(waitpid(pid, &status, 0)) : -1;
        child->reaped = true;
    }
    {
        std::lock_guard<std::mutex> guard(children_lock);
        auto found = children.find(pid);
        if (found != children.end() && found->second == child) children.erase(found);
    }

    if (ret == -1) {
        if (errno == ECHILD) {
            return 0;
        }
        PLOGE("waitpid");
        return -1;
    }

    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

int rikka_rish_RishHost_registerNatives(JNIEnv *env) {
    auto clazz = env->FindClass("rikka/rish/RishHost");
    JNINativeMethod methods[] = {
            {"start",         "([BI[BI[BBIII)[I", (void *) RishHost_startHost},
            {"setWindowSize", "(IJ)V",            (void *) RishHost_setWindowSize},
            {"waitFor",       "(I)I",             (void *) RishHost_waitFor},
    };
    return env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0]));
}
