package rikka.shizuku.server;

import android.app.Application;
import android.os.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.shizuku.SystemServiceHelper;
import rikka.shizuku.server.util.HandlerUtil;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ApiLifecycleTest {
    @Before public void setup() {
        HandlerUtil.setMainHandler(new Handler(Looper.getMainLooper()));
        SystemServiceHelper.clearCache();
    }

    private static class Record extends UserServiceRecord {
        int removals;
        Record() { super(1, true); }
        @Override public void removeSelf() { removals++; destroy(); }
    }

    private IShizukuServiceConnection connection(AtomicInteger died) {
        return new IShizukuServiceConnection.Stub() {
            public void connected(IBinder binder) {}
            public void died() { died.incrementAndGet(); }
        };
    }

    @Test public void removedRecordCancelsTimeoutAndNotifiesWaitingClient() {
        Record record = new Record();
        AtomicInteger died = new AtomicInteger();
        record.callbacks.register(connection(died));
        record.setStartingTimeout(1000);
        record.destroy();
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertTrue(record.removed);
        assertFalse(record.starting);
        assertEquals(0, record.removals);
        assertEquals(1, died.get());
        assertEquals(0, record.callbacks.getRegisteredCallbackCount());
    }

    @Test public void attachedRecordClearsStartingAndCancelsTimeout() {
        Record record = new Record();
        record.setStartingTimeout(1000);
        record.setBinder(new Binder());
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertFalse(record.starting);
        assertFalse(record.removed);
        assertEquals(0, record.removals);
        record.destroy();
    }

    @Test public void unlinkFailureStillKillsCallbacks() {
        Record record = new Record();
        record.callbacks.register(connection(new AtomicInteger()));
        record.setBinder((IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, (p, m, a) -> {
                    if (m.getName().equals("unlinkToDeath")) throw new IllegalStateException("already unlinked");
                    if (m.getName().equals("pingBinder")) return false;
                    if (m.getName().equals("getInterfaceDescriptor")) return "test";
                    return null;
                }));
        record.destroy();
        assertEquals(0, record.callbacks.getRegisteredCallbackCount());
    }

    @Test public void attachedBinderDeathNotifiesClientOnce() {
        Record record = new Record();
        AtomicInteger died = new AtomicInteger();
        AtomicBoolean alive = new AtomicBoolean(true);
        AtomicReference<IBinder.DeathRecipient> recipient = new AtomicReference<>();
        record.callbacks.register(connection(died));
        record.setBinder((IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "linkToDeath": recipient.set((IBinder.DeathRecipient) a[0]); return null;
                        case "unlinkToDeath": return true;
                        case "pingBinder": return alive.get();
                        case "getInterfaceDescriptor": return "test";
                        default: return null;
                    }
                }));
        alive.set(false);
        recipient.get().binderDied();
        record.destroy();
        assertEquals(1, died.get());
    }

    @Test public void lateAttachRejectsLocalAndDescriptorlessBinders() {
        UserServiceManager manager = manager(new AtomicInteger());
        Bundle options = new Bundle();
        options.putString(rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, "expired");
        AtomicInteger transactions = new AtomicInteger();
        manager.attachUserService(new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                transactions.incrementAndGet();
                return true;
            }
        }, options);
        IBinder orphan = (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, (p, m, a) -> {
                    if (m.getName().equals("pingBinder")) return true;
                    if (m.getName().equals("getInterfaceDescriptor")) return null;
                    if (m.getName().equals("transact")) { transactions.incrementAndGet(); return true; }
                    return null;
                });
        manager.attachUserService(orphan, options);
        assertEquals(0, transactions.get());
    }

    @Test public void reentrantDeathCompletesConnectedBeforeDiedAndClearsCallbacks() {
        Record record = new Record();
        java.util.List<String> events = new java.util.ArrayList<>();
        record.callbacks.register(new IShizukuServiceConnection.Stub() {
            public void connected(IBinder binder) {
                events.add("connected");
                record.service = null;
                record.destroy();
                events.add("connected-return");
            }
            public void died() { events.add("died"); }
        });
        record.setBinder(new Binder());
        record.destroy();
        assertEquals(java.util.Arrays.asList("connected", "connected-return", "died"), events);
        assertEquals(0, record.callbacks.getRegisteredCallbackCount());
    }

    @Test public void concurrentDeathDoesNotBlockOnConnectedCallback() throws Exception {
        Record record = new Record();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger died = new AtomicInteger();
        record.callbacks.register(new IShizukuServiceConnection.Stub() {
            public void connected(IBinder binder) {
                entered.countDown();
                try { assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            public void died() { died.incrementAndGet(); }
        });
        Thread attach = new Thread(() -> record.setBinder(new Binder()));
        attach.start();
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        try {
            record.service = null;
            Thread remove = new Thread(record::destroy);
            remove.start();
            remove.join(1000);
            assertFalse("Destroy must not wait on a remote callback", remove.isAlive());
            assertEquals(0, died.get());
        } finally { release.countDown(); attach.join(3000); }
        assertFalse(attach.isAlive());
        assertEquals(1, died.get());
        assertEquals(0, record.callbacks.getRegisteredCallbackCount());
    }

    @SuppressWarnings("unchecked")
    @Test public void managerReleasesLifecycleLockBeforeConnectionAndDeathCallbacks() throws Exception {
        UserServiceManager manager = new UserServiceManager() {
            @Override public android.content.pm.PackageInfo ensureCallingPackageForUserService(String pkg, int app, int user) {
                android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
                info.packageName = pkg;
                info.applicationInfo = new android.content.pm.ApplicationInfo();
                info.applicationInfo.sourceDir = "/test/base.apk";
                return info;
            }
            public String getUserServiceStartCmd(UserServiceRecord r, String key, String token,
                    String pkg, String cls, String suffix, int uid, boolean bits, boolean debug) {
                throw new AssertionError("An existing attached service must not spawn");
            }
        };
        Record record = new Record();
        record.setBinder(new Binder());
        Field recordsField = UserServiceManager.class.getDeclaredField("userServiceRecords");
        recordsField.setAccessible(true);
        String key = rikka.shizuku.server.util.UserHandleCompat.getUserId(Binder.getCallingUid()) + ":test.package:Service";
        ((Map<String, UserServiceRecord>) recordsField.get(manager)).put(key, record);
        AtomicInteger connected = new AtomicInteger(), died = new AtomicInteger(), locked = new AtomicInteger();
        IShizukuServiceConnection conn = new IShizukuServiceConnection.Stub() {
            public void connected(IBinder binder) {
                if (Thread.holdsLock(manager)) locked.incrementAndGet();
                connected.incrementAndGet();
            }
            public void died() {
                if (Thread.holdsLock(manager)) locked.incrementAndGet();
                died.incrementAndGet();
            }
        };
        Bundle options = new Bundle();
        options.putParcelable(rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT,
                new android.content.ComponentName("test.package", "Service"));
        options.putBoolean(rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, true);
        assertEquals(1, manager.addUserService(conn, options, 13));
        record.service = null;
        assertEquals(0, manager.removeUserService(conn, options));
        assertEquals(1, connected.get());
        assertEquals(1, died.get());
        assertEquals("Callbacks must run outside the manager lifecycle lock", 0, locked.get());
    }

    @SuppressWarnings("unchecked")
    @Test public void permissionRevocationKeepsOtherAndroidUsers() throws Exception {
        UserServiceManager manager = manager(new AtomicInteger());
        Record personal = new Record(), work = new Record();
        personal.userId = 0;
        work.userId = 10;
        Field field = UserServiceManager.class.getDeclaredField("packageUserServiceRecords");
        field.setAccessible(true);
        ((Map<String, java.util.List<UserServiceRecord>>) field.get(manager)).put("test.package",
                new java.util.ArrayList<>(java.util.Arrays.asList(personal, work)));
        manager.removeUserServicesForPackage("test.package", 10);
        assertFalse(personal.removed);
        assertTrue(work.removed);
        assertEquals(0, personal.removals);
        assertEquals(1, work.removals);
    }

    @SuppressWarnings("unchecked")
    private Map<String, IBinder> cache() throws Exception {
        Field field = SystemServiceHelper.class.getDeclaredField("SYSTEM_SERVICE_CACHE");
        field.setAccessible(true);
        return (Map<String, IBinder>) field.get(null);
    }

    @Test public void deadBinderIsNotReturnedWhenRefreshFails() throws Exception {
        IBinder dead = (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, (p, m, a) -> false);
        cache().put("missing-test-service", dead);
        Field method = SystemServiceHelper.class.getDeclaredField("getService");
        method.setAccessible(true);
        Object saved = method.get(null);
        try {
            method.set(null, null);
            assertNull(SystemServiceHelper.getSystemService("missing-test-service"));
            assertFalse(cache().containsKey("missing-test-service"));
        } finally { method.set(null, saved); }
    }

    @Test public void liveCachedBinderIsReusedAndCanBeCleared() throws Exception {
        IBinder live = new Binder();
        cache().put("test-service", live);
        assertSame(live, SystemServiceHelper.getSystemService("test-service"));
        SystemServiceHelper.clearCache();
        assertTrue(cache().isEmpty());
    }
    private UserServiceManager manager(AtomicInteger commands) {
        return new UserServiceManager() {
            public String getUserServiceStartCmd(UserServiceRecord r, String key, String token,
                    String pkg, String cls, String suffix, int uid, boolean bits, boolean debug) {
                commands.incrementAndGet();
                throw new IllegalStateException("package manager unavailable");
            }
        };
    }

    private void startQueued(UserServiceManager manager, UserServiceRecord record) throws Exception {
        Method method = UserServiceManager.class.getDeclaredMethod("startUserService",
                UserServiceRecord.class, String.class, String.class, String.class, String.class,
                String.class, int.class, boolean.class, boolean.class);
        method.setAccessible(true);
        method.invoke(manager, record, "key", record.token, "package", "Service", null, 10000, false, false);
    }

    @SuppressWarnings("unchecked")
    @Test public void commandGenerationFailureRemovesRecordAndNotifiesClient() throws Exception {
        AtomicInteger commands = new AtomicInteger(), died = new AtomicInteger();
        UserServiceManager manager = manager(commands);
        Record record = new Record();
        record.callbacks.register(connection(died));
        Field records = UserServiceManager.class.getDeclaredField("userServiceRecords");
        records.setAccessible(true);
        ((Map<String, UserServiceRecord>) records.get(manager)).put("key", record);
        startQueued(manager, record);
        assertEquals(1, commands.get());
        assertTrue(record.removed);
        assertEquals(1, died.get());
    }

    @Test public void removedQueuedRequestDoesNotGenerateOrExecuteCommand() throws Exception {
        AtomicInteger commands = new AtomicInteger();
        UserServiceManager manager = manager(commands);
        Record record = new Record();
        record.destroy();
        startQueued(manager, record);
        assertEquals(0, commands.get());
    }

}
