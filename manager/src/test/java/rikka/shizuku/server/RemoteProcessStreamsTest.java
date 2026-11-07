package rikka.shizuku.server;

import android.os.ParcelFileDescriptor;
import android.os.Parcel;
import moe.shizuku.server.IRemoteProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import rikka.shizuku.server.api.RemoteProcessHolder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class RemoteProcessStreamsTest {
    static class FakeProcess extends Process {
        final AtomicInteger outputRequests = new AtomicInteger();
        public OutputStream getOutputStream() { outputRequests.incrementAndGet(); return new ByteArrayOutputStream(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public int waitFor() { return 0; }
        public int exitValue() { return 0; }
        public void destroy() {}
    }

    @Test public void eachBinderStreamCanOnlyBeClaimedOnce() throws Exception {
        RemoteProcessHolder holder = new RemoteProcessHolder(new FakeProcess(), null);
        try (ParcelFileDescriptor in = holder.getOutputStream();
             ParcelFileDescriptor out = holder.getInputStream();
             ParcelFileDescriptor err = holder.getErrorStream()) {
            assertThrows(IllegalStateException.class, holder::getOutputStream);
            assertThrows(IllegalStateException.class, holder::getInputStream);
            assertThrows(IllegalStateException.class, holder::getErrorStream);
        }
    }

    @Test public void competingClaimsCreateOnlyOnePipe() throws Exception {
        FakeProcess process = new FakeProcess();
        RemoteProcessHolder holder = new RemoteProcessHolder(process, null);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Callable<Boolean> claim = () -> {
            start.await();
            try (ParcelFileDescriptor fd = holder.getOutputStream()) { return true; }
            catch (IllegalStateException expected) { return false; }
        };
        try {
            Future<Boolean> first = workers.submit(claim), second = workers.submit(claim);
            start.countDown();
            assertNotEquals(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
            assertEquals(1, process.outputRequests.get());
        } finally { workers.shutdownNow(); }
    }

    @Test public void marshallingFailureClosesPipeBeforeRelayStartsAndAllowsRetry() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        FakeProcess process = new FakeProcess() {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    @Override public int read() { reads.incrementAndGet(); return -1; }
                };
            }
        };
        ParcelFileDescriptor[] claimed = new ParcelFileDescriptor[1];
        RemoteProcessHolder holder = new RemoteProcessHolder(process, null) {
            boolean fail = true;
            @Override public synchronized ParcelFileDescriptor getInputStream() {
                ParcelFileDescriptor fd = super.getInputStream();
                claimed[0] = fd;
                if (!fail) return fd;
                fail = false;
                return new ParcelFileDescriptor(fd) {
                    @Override public void writeToParcel(Parcel reply, int flags) {
                        throw new IllegalStateException("injected reply marshalling failure");
                    }
                };
            }
        };
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(holder.getInterfaceDescriptor());
            request.setDataPosition(0);
            java.lang.reflect.Field code = IRemoteProcess.Stub.class.getDeclaredField("TRANSACTION_getInputStream");
            code.setAccessible(true);
            assertThrows(IllegalStateException.class,
                    () -> holder.onTransact(code.getInt(null), request, reply, 0));
            assertEquals("relay must not read on marshalling failure", 0, reads.get());
            assertThrows(IllegalStateException.class, () -> claimed[0].getFd());
            try (ParcelFileDescriptor retried = holder.getInputStream()) {
                assertNotNull(retried);
            }
        } finally { request.recycle(); reply.recycle(); }
    }
}
