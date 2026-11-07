package rikka.rish;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Server-side PTY host process wrapper for {@code rish}.
 * <p>
 * Incorporates UTF-8 string block serialization from Nightzuku API ({@code RishHost.kt})
 * and synchronized {@code ptmx} lifecycle management, {@code CountDownLatch} exit synchronization,
 * and {@code closePtmx()} cleanup from Porter API ({@code PorshHost.kt}).
 */
public class RishHost {

    private static final String TAG = "RishHost";

    private static byte[] createCBytesForStringArray(String[] array) {
        if (array == null) {
            return null;
        }

        byte[][] bytes = new byte[array.length][];
        int count = bytes.length; // For added NUL bytes
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = array[i].getBytes(StandardCharsets.UTF_8);
            count += bytes[i].length;
        }
        byte[] block = new byte[count];
        int i = 0;
        for (byte[] arg : bytes) {
            System.arraycopy(arg, 0, block, i, arg.length);
            i += arg.length + 1;
        }
        return block;
    }

    private static byte[] createCBytesForString(String s) {
        if (s == null) {
            return null;
        }

        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, result, 0, bytes.length);
        result[result.length - 1] = (byte) 0;
        return result;
    }

    private static int detachFd(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return -1;
        }
        return pfd.detachFd();
    }

    private final String[] args;
    private final String[] env;
    private final String dir;
    private final byte tty;
    private final int stdin;
    private final int stdout;
    private final int stderr;
    private final CountDownLatch exited = new CountDownLatch(1);
    private final Object ptmxLock = new Object();
    private volatile int pid;
    private int ptmx = -1;
    private volatile int exitCode = Integer.MAX_VALUE;
    private volatile long exitedAtMillis = 0L;

    public RishHost(
            String[] args, String[] env, String dir,
            byte tty,
            ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr) {

        this.args = args;
        this.env = env;
        this.dir = dir;
        this.tty = tty;
        this.stdin = detachFd(stdin);
        this.stdout = detachFd(stdout);
        this.stderr = detachFd(stderr);
    }

    /**
     * Fork and execute, start transfer threads.
     */
    public void start() {
        Log.d(TAG, "start");

        byte[] argBlock = createCBytesForStringArray(args);
        byte[] envBlock = createCBytesForStringArray(env);
        byte[] dirBlock = createCBytesForString(dir);

        int[] result = start(
                argBlock, args != null ? args.length : 0,
                envBlock, env != null ? env.length : -1,
                dirBlock,
                tty, stdin, stdout, stderr);

        pid = result[0];
        synchronized (ptmxLock) {
            ptmx = result[1];
        }

        Thread waitThread = new Thread(() -> onExited(waitFor(pid)), "rish-wait-" + pid);
        waitThread.setDaemon(true);
        waitThread.start();
    }

    private void onExited(int code) {
        closePtmx();
        exitCode = code;
        exitedAtMillis = SystemClock.elapsedRealtime();
        exited.countDown();
    }

    boolean hasExited() {
        return exited.getCount() == 0L;
    }

    long getExitedAtMillis() {
        return exitedAtMillis;
    }

    int awaitExitCode(long timeoutMillis) {
        try {
            if (!exited.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out waiting for " + pid + " to exit");
                return -1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
        return exitCode;
    }

    public int getPid() {
        return pid;
    }

    public int getExitCode() {
        return exitCode;
    }

    public void setWindowSize(long size) {
        Log.d(TAG, "setWindowSize");

        synchronized (ptmxLock) {
            if (ptmx < 0) {
                return;
            }
            setWindowSize(ptmx, size);
        }
    }

    private void closePtmx() {
        synchronized (ptmxLock) {
            if (ptmx < 0) {
                return;
            }
            try {
                ParcelFileDescriptor.adoptFd(ptmx).close();
            } catch (IOException e) {
                Log.w(TAG, "close ptmx", e);
            }
            ptmx = -1;
        }
    }

    private static native int[] start(
            byte[] argBlock, int argc,
            byte[] envBlock, int envc,
            byte[] dirBlock,
            byte tty, int stdin, int stdout, int stderr);

    private static native void setWindowSize(int ptmx, long size);

    private static native int waitFor(int pid);
}
