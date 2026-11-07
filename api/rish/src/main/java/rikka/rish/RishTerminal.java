package rikka.rish;

import android.os.Parcel;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-side PTY terminal relay for {@code rish}.
 * <p>
 * Incorporates fixes from Nightzuku API ({@code RishTerminal.kt}) and Porter API ({@code PorshTerminal.kt}):
 * <ul>
 *     <li>Fixes {@code stderr} descriptor routing bug (passing {@code getFd(stderr, 0)} instead of {@code getFd(stdout, 0)}).</li>
 *     <li>Applies {@link #routable(byte)} so piped {@code stdout} forces {@code stderr} onto a pipe as well.</li>
 *     <li>Passes a non-null {@code reply} Parcel in {@link #setWindowSize(long)} before {@code readException()}.</li>
 *     <li>Uses a daemon thread for {@code SIGWINCH} window-size forwarding and breaks on exception.</li>
 *     <li>Closes local pipe descriptors in {@code finally} when {@link #waitFor()} completes.</li>
 * </ul>
 */
public class RishTerminal {

    private static final String TAG = "RishTerminal";
    // Native relays use process standard I/O and SIGWINCH. A terminal is a process-lifetime resource.
    private static final AtomicBoolean terminalClaimed = new AtomicBoolean();

    public static int getFd(FileDescriptor[] fileDescriptor, int i) {
        if (fileDescriptor == null) {
            return -1;
        }
        return FileDescriptors.getFd(fileDescriptor[i]);
    }

    public static void closeFd(FileDescriptor[] fileDescriptor, int i) {
        if (fileDescriptor == null) {
            return;
        }
        FileDescriptors.closeSilently(fileDescriptor[i]);
    }

    /**
     * When stdout is piped (`rish -c cmd | grep x`), stderr must also travel over a pipe
     * rather than an unread PTY. Adapted from Porter API ({@code PorshTerminal.routable}).
     */
    static byte routable(byte tty) {
        if ((tty & RishConstants.ATTY_OUT) != 0) {
            return tty;
        }
        return (byte) (tty & ~RishConstants.ATTY_ERR);
    }

    private final String[] argv;
    private final byte tty;
    private FileDescriptor[] stdin;
    private FileDescriptor[] stdout;
    private FileDescriptor[] stderr;
    private volatile int ttyFd = -1;
    private volatile int exitCode;
    private boolean started;

    public RishTerminal(String[] argv) throws ErrnoException, RemoteException {
        if (!terminalClaimed.compareAndSet(false, true)) {
            throw new IllegalStateException("Only one rish terminal per process is supported");
        }
        this.argv = argv;
        try {
            this.tty = routable(prepare());
            createHost();
        } catch (ErrnoException | RemoteException | RuntimeException | Error e) {
            terminalClaimed.set(false);
            throw e;
        }
    }

    private void createHost() throws ErrnoException, RemoteException {
        Log.d(TAG, "createHost");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        List<String> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            list.add(entry.getKey() + "=" + entry.getValue());
        }
        String[] env = list.toArray(new String[0]);

        String dir = new File("").getAbsolutePath();

        try {
            data.writeInterfaceToken(RishConfig.getInterfaceToken());
            data.writeByte(tty);
            stdin = Os.pipe();
            data.writeFileDescriptor(stdin[0]);
            stdout = Os.pipe();
            data.writeFileDescriptor(stdout[1]);
            if ((tty & RishConstants.ATTY_ERR) == 0) {
                stderr = Os.pipe();
                data.writeFileDescriptor(stderr[1]);
            }
            data.writeStringArray(argv);
            data.writeStringArray(env);
            data.writeString(dir);
            RishConfig.getBinder().transact(RishConfig.getTransactionCode(RishConfig.TRANSACTION_createHost), data, reply, 0);
            reply.readException();
        } catch (ErrnoException | RemoteException | RuntimeException e) {
            closeFd(stdin, 1);
            closeFd(stdout, 0);
            closeFd(stderr, 0);
            throw e;
        } finally {
            data.recycle();
            reply.recycle();

            closeFd(stdin, 0);
            closeFd(stdout, 1);
            closeFd(stderr, 1);
        }
    }

    public synchronized void start() {
        if (started) throw new IllegalStateException("Terminal already started");
        started = true;
        Log.d(TAG, "start");

        try {
            ttyFd = start(tty, getFd(stdin, 1), getFd(stdout, 0), getFd(stderr, 0));
        } catch (RuntimeException | Error e) {
            closeFd(stdout, 0);
            closeFd(stderr, 0);
            throw e;
        } finally {
            closeFd(stdin, 1);
            if (stdin != null) stdin[1] = null;
        }

        if (ttyFd != -1) {
            Thread winThread = new Thread(() -> {
                while (true) {
                    Log.d(TAG, "waitForWindowSizeChange");

                    try {
                        long size = waitForWindowSizeChange(ttyFd);
                        if (size == -1) break;
                        setWindowSize(size);
                    } catch (Throwable e) {
                        Log.w(TAG, Log.getStackTraceString(e));
                        break;
                    }
                }
            }, "rish-winch");
            winThread.setDaemon(true);
            winThread.start();
        }
    }

    private void setWindowSize(long size) throws RemoteException {
        Log.d(TAG, "setWindowSize");

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        try {
            data.writeInterfaceToken(RishConfig.getInterfaceToken());
            data.writeLong(size);
            RishConfig.getBinder().transact(RishConfig.getTransactionCode(RishConfig.TRANSACTION_setWindowSize), data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private int requestExitCode() throws RemoteException {
        Log.d(TAG, "requestExitCode");

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        try {
            data.writeInterfaceToken(RishConfig.getInterfaceToken());
            data.writeInt(1); // Optional extension: nonblocking exit query, ignored by old servers.
            RishConfig.getBinder().transact(RishConfig.getTransactionCode(RishConfig.TRANSACTION_getExitCode), data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public int waitFor() {
        Log.d(TAG, "waitFor");

        waitForProcessExit();
        try {
            do {
                exitCode = requestExitCode();
                if (exitCode == Integer.MAX_VALUE) Thread.sleep(100);
            } while (exitCode == Integer.MAX_VALUE);
        } catch (Throwable e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.w(TAG, Log.getStackTraceString(e));
            exitCode = -1;
        } finally {
            closeFd(stdout, 0);
            closeFd(stderr, 0);
        }
        return exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }

    private static native byte prepare();

    private static native int start(byte tty, int stdin, int stdout, int stderr);

    private static native long waitForWindowSizeChange(int fd);

    private static native void waitForProcessExit();
}
