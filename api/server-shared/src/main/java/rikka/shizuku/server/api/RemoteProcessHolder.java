package rikka.shizuku.server.api;

import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import moe.shizuku.server.IRemoteProcess;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.ParcelFileDescriptorUtil;

public class RemoteProcessHolder extends IRemoteProcess.Stub {

    private static final Logger LOGGER = new Logger("RemoteProcessHolder");

    private final Process process;
    // AIDL transfers (and closes) the returned PFD. Never cache that object.
    // Each pipe has one owner; retaining a stdin writer here would suppress EOF.
    private boolean inputTransferred;
    private boolean outputTransferred;
    private boolean errorTransferred;
    private static final AtomicLong FAILED_HANDOFFS = new AtomicLong();
    private final ThreadLocal<Handoff> transaction = new ThreadLocal<>();

    private static final class Handoff {
        ParcelFileDescriptorUtil.PreparedTransfer transfer;
        int stream;
    }

    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        Handoff previous = transaction.get();
        Handoff handoff = new Handoff();
        transaction.set(handoff);
        boolean complete = false;
        try {
            boolean handled = super.onTransact(code, data, reply, flags);
            if (handoff.transfer != null) handoff.transfer.start();
            complete = true;
            return handled;
        } finally {
            transaction.set(previous);
            if (!complete && handoff.transfer != null) {
                handoff.transfer.close();
                synchronized (this) {
                    if (handoff.stream == 0) outputTransferred = false;
                    else if (handoff.stream == 1) inputTransferred = false;
                    else errorTransferred = false;
                }
                LOGGER.w("Process stream handoff failed before relay start (stream=%d, total=%d)",
                        handoff.stream, FAILED_HANDOFFS.incrementAndGet());
            }
        }
    }

    private ParcelFileDescriptor handoff(ParcelFileDescriptorUtil.PreparedTransfer transfer, int stream) {
        Handoff pending = transaction.get();
        if (pending == null) {
            // Local calls have no reply marshalling boundary.
            transfer.start();
        } else {
            pending.transfer = transfer;
            pending.stream = stream;
        }
        return transfer.descriptor;
    }

    public RemoteProcessHolder(Process process, IBinder token) {
        this.process = process;

        if (token != null) {
            try {
                DeathRecipient deathRecipient = () -> {
                    try {
                        if (alive()) {
                            destroy();
                            LOGGER.i("destroy process because the owner is dead");
                        }
                    } catch (Throwable e) {
                        LOGGER.w(e, "failed to destroy process");
                    }
                };
                token.linkToDeath(deathRecipient, 0);
            } catch (Throwable e) {
                LOGGER.w(e, "linkToDeath");
            }
        }
    }

    @Override
    public synchronized ParcelFileDescriptor getOutputStream() {
        if (outputTransferred) throw new IllegalStateException("Process stdin already transferred");
        try {
            ParcelFileDescriptor fd = handoff(ParcelFileDescriptorUtil.prepareTo(process.getOutputStream()), 0);
            outputTransferred = true;
            return fd;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized ParcelFileDescriptor getInputStream() {
        if (inputTransferred) throw new IllegalStateException("Process stdout already transferred");
        try {
            ParcelFileDescriptor fd = handoff(ParcelFileDescriptorUtil.prepareFrom(process.getInputStream()), 1);
            inputTransferred = true;
            return fd;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized ParcelFileDescriptor getErrorStream() {
        if (errorTransferred) throw new IllegalStateException("Process stderr already transferred");
        try {
            ParcelFileDescriptor fd = handoff(ParcelFileDescriptorUtil.prepareFrom(process.getErrorStream()), 2);
            errorTransferred = true;
            return fd;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int waitFor() {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int exitValue() {
        return process.exitValue();
    }

    @Override
    public void destroy() {
        process.destroy();
    }

    @Override
    public boolean alive() throws RemoteException {
        try {
            this.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    @Override
    public boolean waitForTimeout(long timeout, String unitName) throws RemoteException {
        TimeUnit unit = TimeUnit.valueOf(unitName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return process.waitFor(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        long startTime = System.nanoTime();
        long rem = unit.toNanos(timeout);

        do {
            try {
                exitValue();
                return true;
            } catch (IllegalThreadStateException ex) {
                if (rem > 0) {
                    try {
                        Thread.sleep(
                                Math.min(TimeUnit.NANOSECONDS.toMillis(rem) + 1, 100));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException();
                    }
                }
            }
            rem = unit.toNanos(timeout) - (System.nanoTime() - startTime);
        } while (rem > 0);
        return false;
    }
}
