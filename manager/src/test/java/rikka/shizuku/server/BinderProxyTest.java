package rikka.shizuku.server;

import android.app.Application;
import android.content.Intent;
import android.os.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import moe.shizuku.server.IShizukuApplication;
import rikka.rish.RishConfig;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, shadows = BinderProxyTest.NoNativeLibrary.class)
public class BinderProxyTest {
    @Implements(RishConfig.class)
    public static class NoNativeLibrary {
        @Implementation protected static void init(String token, int start) {}
    }
    private static class TestService extends Service<UserServiceManager, ClientManager<ConfigManager>, ConfigManager> {
        public UserServiceManager onCreateUserServiceManager() { return null; }
        public ClientManager<ConfigManager> onCreateClientManager() { return new ClientManager<>(null); }
        public ConfigManager onCreateConfigManager() { return null; }
        @Override public boolean checkCallerPermission(String f, int uid, int pid, ClientRecord c) { return true; }
        public void showPermissionConfirmation(int r, ClientRecord c, int uid, int pid, int user) {}
        public void attachApplication(IShizukuApplication app, Bundle args) {}
        public void exit() {}
        public void dispatchPackageChanged(Intent intent) {}
        public boolean isHidden(int uid) { return false; }
        public void dispatchPermissionConfirmationResult(int uid, int pid, int r, Bundle data) {}
        public int getFlagsForUid(int uid, int mask) { return 0; }
        public void updateFlagsForUid(int uid, int mask, int value) {}
    }
    private Parcel request(IBinder target) {
        Parcel data = Parcel.obtain();
        data.writeStrongBinder(target);
        data.writeInt(123);
        data.setDataPosition(0);
        return data;
    }
    @Test public void rejectsServerLocalBinderAndNullTarget() throws Exception {
        TestService service = new TestService();
        Parcel reply = Parcel.obtain();
        Parcel local = request(service);
        Parcel missing = request(null);
        try {
            assertThrows(SecurityException.class, () -> service.transactRemote(local, reply, 0));
            assertThrows(IllegalArgumentException.class, () -> service.transactRemote(missing, reply, 0));
        } finally { local.recycle(); missing.recycle(); reply.recycle(); }
    }
    @Test public void remoteFailureDoesNotPreventNextTransaction() throws Exception {
        TestService service = new TestService();
        AtomicInteger calls = new AtomicInteger();
        IBinder target = (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, (p, m, a) -> {
                    if (m.getName().equals("transact")) {
                        if (calls.incrementAndGet() == 1) throw new RemoteException("dead");
                        ((Parcel) a[2]).writeInt(42);
                        return true;
                    }
                    return null;
                });
        Parcel first = request(target), second = request(target), reply = Parcel.obtain();
        try {
            assertThrows(RemoteException.class, () -> service.transactRemote(first, reply, 0));
            service.transactRemote(second, reply, 0);
            reply.setDataPosition(0);
            assertEquals(42, reply.readInt());
            assertEquals(2, calls.get());
        } finally { first.recycle(); second.recycle(); reply.recycle(); }
    }
}
