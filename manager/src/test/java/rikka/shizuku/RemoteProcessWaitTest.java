package rikka.shizuku;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import rikka.shizuku.server.api.RemoteProcessHolder;
import java.io.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RemoteProcessWaitTest {
    @Test public void standardTimedWaitUsesBoundedRemoteMethod() throws Exception {
        AtomicInteger timed = new AtomicInteger();
        RemoteProcessHolder remote = new RemoteProcessHolder(new StubProcess(), null) {
            @Override public boolean waitForTimeout(long timeout, String unit) {
                assertEquals(17, timeout); assertEquals("MILLISECONDS", unit);
                timed.incrementAndGet(); return false;
            }
            @Override public int exitValue() { throw new AssertionError("must not poll exitValue over Binder"); }
        };
        assertFalse(new ShizukuRemoteProcess(remote).waitFor(17, TimeUnit.MILLISECONDS));
        assertEquals(1, timed.get());
    }

    @Test public void transportedNotExitedExceptionKeepsProcessSemantics() {
        RemoteProcessHolder remote = new RemoteProcessHolder(new StubProcess(), null) {
            @Override public int exitValue() { throw new IllegalArgumentException("process hasn't exited"); }
        };
        ShizukuRemoteProcess process = new ShizukuRemoteProcess(remote);
        assertThrows(IllegalThreadStateException.class, process::exitValue);
        assertFalse(process.isAlive() == false);
    }

    private static class StubProcess extends Process {
        public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        public InputStream getErrorStream() { return getInputStream(); }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public int waitFor() { return 0; }
        public int exitValue() { return 0; }
        public void destroy() {}
    }
}
