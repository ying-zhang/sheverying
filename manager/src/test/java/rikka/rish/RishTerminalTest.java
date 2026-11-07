package rikka.rish;

import android.app.Application;
import android.os.*;
import java.lang.reflect.Field;
import java.io.FileDescriptor;
import org.robolectric.util.ReflectionHelpers;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, shadows = RishTerminalTest.TerminalNatives.class)
public class RishTerminalTest {
    @Implements(RishTerminal.class)
    public static class TerminalNatives {
        static int stdout, stderr;
        @RealObject RishTerminal terminal;
        // Android Os.pipe has no Windows implementation in Robolectric. Supply only
        // pipe endpoints; production routing and Binder reply handling still run.
        @Implementation protected void createHost() {
            for (String name : new String[]{"stdin", "stdout", "stderr"}) {
                ReflectionHelpers.setField(terminal, name,
                        new FileDescriptor[]{new FileDescriptor(), new FileDescriptor()});
            }
        }
        @Implementation protected static int getFd(FileDescriptor[] fds, int i) {
            return System.identityHashCode(fds[i]) & Integer.MAX_VALUE;
        }
        @Implementation protected static byte prepare() { return 0; }
        @Implementation protected static int start(byte tty, int in, int out, int err) {
            stdout = out; stderr = err; return -1;
        }
        @Implementation protected static void waitForProcessExit() {}
    }

    private static void config(String name, Object value) throws Exception {
        Field field = RishConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @After public void cleanup() throws Exception {
        config("binder", null);
        java.util.concurrent.atomic.AtomicBoolean claimed = ReflectionHelpers.getStaticField(RishTerminal.class, "terminalClaimed");
        claimed.set(false);
    }

    @Test public void separateStderrAndSynchronousExitCode() throws Exception {
        config("interfaceToken", "test.rish");
        config("transactionCodeStart", 30000);
        config("binder", new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                assertNotNull("Synchronous calls need a reply", reply);
                reply.writeNoException();
                if (code == 30002) reply.writeInt(37);
                return true;
            }
        });
        RishTerminal terminal = new RishTerminal(new String[]{"-c", "exit 37"});
        terminal.start();
        FileDescriptor[] stdin = ReflectionHelpers.getField(terminal, "stdin");
        assertNull("The original stdin writer must close after native duplicates it", stdin[1]);
        assertTrue(TerminalNatives.stdout >= 0);
        assertTrue(TerminalNatives.stderr >= 0);
        assertNotEquals(TerminalNatives.stdout, TerminalNatives.stderr);
        assertEquals(37, terminal.waitFor());
    }

    @Test public void pipedStdoutAlsoRoutesStderrThroughPipe() {
        assertEquals(0, RishTerminal.routable((byte) RishConstants.ATTY_ERR));
        assertEquals(7, RishTerminal.routable((byte) 7));
    }

    @Test public void secondTerminalIsRejectedBeforeHostCreation() throws Exception {
        new RishTerminal(new String[]{"-c", "true"});
        try {
            new RishTerminal(new String[]{"-c", "true"});
            fail("A second terminal must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("one rish terminal"));
        }
    }

    @Test public void pendingExitCodeIsPolledUntilActualExit() throws Exception {
        config("interfaceToken", "test.rish");
        config("transactionCodeStart", 30000);
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        config("binder", new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                data.enforceInterface("test.rish");
                assertEquals(1, data.readInt());
                reply.writeNoException();
                reply.writeInt(requests.incrementAndGet() == 1 ? Integer.MAX_VALUE : 23);
                return true;
            }
        });
        RishTerminal terminal = new RishTerminal(new String[]{"-c", "exit 23"});
        terminal.start();
        assertEquals(23, terminal.waitFor());
        assertEquals(2, requests.get());
    }
}
