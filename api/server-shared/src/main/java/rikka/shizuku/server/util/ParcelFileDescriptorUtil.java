package rikka.shizuku.server.util;


import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ParcelFileDescriptorUtil {

    public static ParcelFileDescriptor pipeFrom(InputStream inputStream) throws IOException {
        PreparedTransfer transfer = prepareFrom(inputStream);
        transfer.start();
        return transfer.descriptor;
    }

    public static ParcelFileDescriptor pipeTo(OutputStream outputStream) throws IOException {
        PreparedTransfer transfer = prepareTo(outputStream);
        transfer.start();
        return transfer.descriptor;
    }

    public static PreparedTransfer prepareFrom(InputStream inputStream) throws IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            return new PreparedTransfer(pipe[0], pipe[1], new TransferThread(inputStream,
                    new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])));
        } catch (RuntimeException | Error failure) {
            close(pipe[0]); close(pipe[1]);
            throw failure;
        }
    }

    public static PreparedTransfer prepareTo(OutputStream outputStream) throws IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            return new PreparedTransfer(pipe[1], pipe[0], new TransferThread(
                    new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]), outputStream));
        } catch (RuntimeException | Error failure) {
            close(pipe[0]); close(pipe[1]);
            throw failure;
        }
    }

    private static void close(ParcelFileDescriptor fd) {
        try { fd.close(); } catch (IOException failure) { Log.w("TransferThread", "close pipe", failure); }
    }

    /** Do not start a relay until its descriptor has been written into the reply. */
    public static final class PreparedTransfer implements AutoCloseable {
        public final ParcelFileDescriptor descriptor;
        private final ParcelFileDescriptor relaySide;
        private final TransferThread thread;
        private boolean started;
        private boolean closed;

        private PreparedTransfer(ParcelFileDescriptor descriptor, ParcelFileDescriptor relaySide,
                                 TransferThread thread) {
            this.descriptor = descriptor;
            this.relaySide = relaySide;
            this.thread = thread;
        }

        public synchronized void start() {
            if (started || closed) throw new IllegalStateException("Transfer already started or closed");
            try {
                thread.start();
                started = true;
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        /** Abort an unstarted handoff without closing the underlying process stream. */
        @Override public synchronized void close() {
            if (started || closed) return;
            closed = true;
            ParcelFileDescriptorUtil.close(descriptor);
            ParcelFileDescriptorUtil.close(relaySide);
        }
    }

    public static class TransferThread extends Thread {
        final InputStream mIn;
        final OutputStream mOut;

        public TransferThread(InputStream in, OutputStream out) {
            super("ParcelFileDescriptor Transfer Thread");
            mIn = in;
            mOut = out;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            int len;

            try {
                while ((len = mIn.read(buf)) > 0) {
                    mOut.write(buf, 0, len);
                    mOut.flush();
                }
            } catch (IOException e) {
                Log.e("TransferThread", Log.getStackTraceString(e));
            } finally {
                try {
                    mIn.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    mOut.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
