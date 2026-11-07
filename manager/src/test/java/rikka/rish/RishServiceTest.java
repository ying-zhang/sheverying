package rikka.rish;

import android.app.Application;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, shadows = RishServiceTest.HostNatives.class)
public class RishServiceTest {
    @Implements(RishHost.class)
    public static class HostNatives {
        static int starts;
        @Implementation protected void start() { starts++; }
    }

    @Test public void conflictCannotOverwriteHostAndPollingDoesNotWaitForExit() throws Exception {
        HostNatives.starts = 0;
        RishService service = new RishService() {
            public void enforceCallingPermission(String method) {}
        };
        Method create = RishService.class.getDeclaredMethod("createHost", String[].class,
                String[].class, String.class, byte.class, ParcelFileDescriptor.class,
                ParcelFileDescriptor.class, ParcelFileDescriptor.class);
        create.setAccessible(true);
        Object[] params = {new String[]{"-c", "sleep 60"}, null, "/", (byte) 0, null, null, null};
        create.invoke(service, params);
        try {
            create.invoke(service, params);
            fail("Must reject same-PID session conflict");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
        }
        assertEquals(1, HostNatives.starts);
        Method query = RishService.class.getDeclaredMethod("getExitCode", boolean.class);
        query.setAccessible(true);
        long start = System.nanoTime();
        assertEquals(Integer.MAX_VALUE, query.invoke(service, true));
        assertTrue("Polling must not occupy a Binder thread for seconds",
                System.nanoTime() - start < 500_000_000L);
    }
}
