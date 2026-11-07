package rikka.shizuku.server;

import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_DAEMON;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_REMOVE;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TAG;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.text.format.DateUtils;
import android.util.ArrayMap;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import moe.shizuku.server.IShizukuServiceConnection;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.util.AbiUtil;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.UserHandleCompat;

public abstract class UserServiceManager {

    protected static final Logger LOGGER = new Logger("UserServiceManager");

    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Map<String, UserServiceRecord> userServiceRecords = Collections.synchronizedMap(new ArrayMap<>());
    private final Map<String, List<UserServiceRecord>> packageUserServiceRecords = Collections.synchronizedMap(new ArrayMap<>());

    public UserServiceManager() {
    }

    public PackageInfo ensureCallingPackageForUserService(String packageName, int appId, int userId) {
        @SuppressLint("UnsafeOptInUsageError")
        PackageInfo packageInfo = PackageManagerApis.getPackageInfoNoThrow(packageName, 0x00002000 /*PackageManager.MATCH_UNINSTALLED_PACKAGES*/, userId);
        if (packageInfo == null || packageInfo.applicationInfo == null) {
            throw new SecurityException("unable to find package " + packageName);
        }

        if (UserHandleCompat.getAppId(packageInfo.applicationInfo.uid) != appId) {
            throw new SecurityException("package " + packageName + " is not owned by " + appId);
        }
        return packageInfo;
    }

    public int removeUserService(IShizukuServiceConnection conn, Bundle options) {
        ComponentName componentName = Objects.requireNonNull(options.getParcelable(USER_SERVICE_ARG_COMPONENT), "component is null");

        int uid = Binder.getCallingUid();
        int appId = UserHandleCompat.getAppId(uid);
        int userId = UserHandleCompat.getUserId(uid);

        String packageName = componentName.getPackageName();
        ensureCallingPackageForUserService(packageName, appId, userId);

        String className = Objects.requireNonNull(componentName.getClassName(), "class is null");
        String tag = options.getString(USER_SERVICE_ARG_TAG);
        String key = userId + ":" + packageName + ":" + (tag != null ? tag : className);

        // API < 13.1.4 will not send USER_SERVICE_ARG_REMOVE, true by default
        boolean remove = true;
        if (options.containsKey(USER_SERVICE_ARG_REMOVE)) {
            remove = options.getBoolean(USER_SERVICE_ARG_REMOVE);
        }

        UserServiceRecord removedRecord = null;
        synchronized (this) {
            UserServiceRecord record = getUserServiceRecordLocked(key);
            if (record == null) return 1;
            if (remove) {
                record.pendingDestroy = true;
                if (removeUserServiceLocked(record)) removedRecord = record;
            } else {
                record.callbacks.unregister(conn);
            }
        }
        if (removedRecord != null) removedRecord.destroy();
        return 0;
    }

    private boolean removeUserServiceLocked(UserServiceRecord record) {
        if (userServiceRecords.values().remove(record)) {
            record.pendingDestroy = true;
            for (List<UserServiceRecord> records : packageUserServiceRecords.values()) {
                records.remove(record);
            }
            // ArrayMap's synchronized-map values view does not provide a safe
            // removeIf implementation on every supported Android release.
            List<String> emptyPackages = new ArrayList<>();
            for (Map.Entry<String, List<UserServiceRecord>> entry : packageUserServiceRecords.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    emptyPackages.add(entry.getKey());
                }
            }
            for (String packageName : emptyPackages) {
                packageUserServiceRecords.remove(packageName);
            }
            onUserServiceRecordRemoved(record);
            return true;
        }
        return false;
    }

    public int addUserService(IShizukuServiceConnection conn, Bundle options, int callingApiVersion) {
        Objects.requireNonNull(conn, "connection is null");
        Objects.requireNonNull(options, "options is null");

        int uid = Binder.getCallingUid();
        int appId = UserHandleCompat.getAppId(uid);
        int userId = UserHandleCompat.getUserId(uid);

        ComponentName componentName = Objects.requireNonNull(options.getParcelable(USER_SERVICE_ARG_COMPONENT), "component is null");
        String packageName = Objects.requireNonNull(componentName.getPackageName(), "package is null");
        PackageInfo packageInfo = ensureCallingPackageForUserService(packageName, appId, userId);

        String className = Objects.requireNonNull(componentName.getClassName(), "class is null");
        String sourceDir = Objects.requireNonNull(packageInfo.applicationInfo.sourceDir, "apk path is null");

        int versionCode = options.getInt(USER_SERVICE_ARG_VERSION_CODE, 1);
        String tag = options.getString(USER_SERVICE_ARG_TAG);
        String processNameSuffix = options.getString(USER_SERVICE_ARG_PROCESS_NAME);
        boolean debug = options.getBoolean(USER_SERVICE_ARG_DEBUGGABLE, false);
        boolean noCreate = options.getBoolean(USER_SERVICE_ARG_NO_CREATE, false);
        boolean daemon = options.getBoolean(USER_SERVICE_ARG_DAEMON, true);
        boolean use32Bits = options.getBoolean(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, false);
        String key = userId + ":" + packageName + ":" + (tag != null ? tag : className);

        List<UserServiceRecord> retired = new ArrayList<>();
        UserServiceRecord notify = null;
        int result = 0;
        try {
            synchronized (this) {
                UserServiceRecord record = getUserServiceRecordLocked(key);
                if (noCreate) {
                    if (record != null) {
                        record.callbacks.register(conn);

                        if (record.service != null && record.service.pingBinder()) {
                            notify = record;
                        }
                    }

                    result = notify != null ? (callingApiVersion >= 13 ? record.versionCode : 0)
                            : (callingApiVersion >= 13 ? -1 : 1);
                } else {
                    UserServiceRecord newRecord = createUserServiceRecordIfNeededLocked(record, key, versionCode, daemon, packageInfo, retired);
                    newRecord.callbacks.register(conn);

                    if (newRecord.service != null && newRecord.service.pingBinder()) {
                        notify = newRecord;
                    } else if (!newRecord.starting) {
                        newRecord.setStartingTimeout(DateUtils.SECOND_IN_MILLIS * 30);

                        Runnable runnable = () -> startUserService(newRecord, key, newRecord.token, packageName, className, processNameSuffix, uid, use32Bits, debug);
                        newRecord.spawnRunnable = runnable;
                        executor.execute(runnable);
                    }
                }
            }
        } finally {
            for (UserServiceRecord record : retired) record.destroy();
        }
        if (notify != null) notify.broadcastBinderReceived();
        return result;
    }

    private UserServiceRecord getUserServiceRecordLocked(String key) {
        return userServiceRecords.get(key);
    }

    private UserServiceRecord createUserServiceRecordIfNeededLocked(
            UserServiceRecord record, String key, int versionCode, boolean daemon, PackageInfo packageInfo,
            List<UserServiceRecord> retired) {

        if (record != null) {
            if (record.versionCode != versionCode) {
                LOGGER.v("Remove service record %s (%s) because version code not matched (old=%d, new=%d)", key, record.token, record.versionCode, versionCode);
            } else if (!record.starting && (record.service == null || !record.service.pingBinder())) {
                LOGGER.v("Service in record %s (%s) is dead", key, record.token);
            } else {
                LOGGER.i("Found existing service record %s (%s)", key, record.token);

                if (record.daemon != daemon) {
                    record.setDaemon(daemon);
                }
                record.pendingDestroy = false;
                return record;
            }

            if (removeUserServiceLocked(record)) retired.add(record);
        }

        record = new UserServiceRecord(versionCode, daemon) {

            @Override
            public void removeSelf() {
                boolean detached;
                synchronized (UserServiceManager.this) {
                    detached = removeUserServiceLocked(this);
                }
                if (detached) destroy();
            }
        };

        record.userId = UserHandleCompat.getUserId(packageInfo.applicationInfo.uid);
        String packageName = packageInfo.packageName;
        List<UserServiceRecord> list = packageUserServiceRecords.get(packageName);
        if (list == null) {
            list = Collections.synchronizedList(new ArrayList<>());
            packageUserServiceRecords.put(packageName, list);
        }
        list.add(record);

        onUserServiceRecordCreated(record, packageInfo);

        userServiceRecords.put(key, record);
        LOGGER.i("New service record %s (%s): version=%d, daemon=%s, apk=%s", key, record.token, versionCode, Boolean.toString(daemon), packageInfo.applicationInfo.sourceDir);
        return record;
    }

    private void startUserService(
            UserServiceRecord record, String key, String token, String packageName,
            String classname, String processNameSuffix, int callingUid, boolean use32Bits, boolean debug) {

        java.lang.Process process = null;
        boolean failed = false;
        try {
            if (record.removed || record.pendingDestroy) return;
            String cmd = getUserServiceStartCmd(record, key, token, packageName, classname,
                    processNameSuffix, callingUid, use32Bits && AbiUtil.has32Bit(), debug);
            synchronized (this) {
                if (record.removed || record.pendingDestroy) return;
                // Redirect output to /dev/null: no unconsumed pipes can block the child.
                process = new ProcessBuilder("sh").redirectOutput(new java.io.File("/dev/null"))
                        .redirectError(new java.io.File("/dev/null")).start();
            }
            try (OutputStream os = process.getOutputStream()) {
                os.write(cmd.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                throw new java.io.IOException("User service shell timed out");
            }
            failed = process.exitValue() != 0;
        } catch (Throwable e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.w(e, "Failed to start service record %s", token);
            failed = true;
        } finally {
            if (process != null) {
                process.destroyForcibly();
                try { process.getOutputStream().close(); } catch (java.io.IOException ignored) {}
                try { process.getInputStream().close(); } catch (java.io.IOException ignored) {}
                try { process.getErrorStream().close(); } catch (java.io.IOException ignored) {}
            }
        }
        if (failed) dropRecordIfNotAttachedLocked(record);
    }

    private void dropRecordIfNotAttachedLocked(UserServiceRecord record) {
        boolean detached;
        synchronized (this) {
            if (record.service != null || record.removed || record.pendingDestroy) {
                return;
            }
            if (record.callbacks.getRegisteredCallbackCount() > 0
                    && record.spawnRunnable != null
                    && userServiceRecords.containsValue(record)
                    && record.spawnAttempts < MAX_SPAWN_RETRIES) {
                record.spawnAttempts++;
                LOGGER.w("Spawn for service record %s failed with %d waiting connection(s); respawning (attempt %d/%d)",
                        record.token, record.callbacks.getRegisteredCallbackCount(), record.spawnAttempts, MAX_SPAWN_RETRIES);
                executor.execute(record.spawnRunnable);
                return;
            }
            detached = removeUserServiceLocked(record);
        }
        if (detached) record.destroy();
    }

    private static final int MAX_SPAWN_RETRIES = 3;

    public abstract String getUserServiceStartCmd(
            UserServiceRecord record, String key, String token, String packageName,
            String classname, String processNameSuffix, int callingUid, boolean use32Bits, boolean debug);

    private UserServiceRecord findUserServiceLocked(IBinder binder, String token) {
        Map.Entry<String, UserServiceRecord> entry = null;
        for (Map.Entry<String, UserServiceRecord> e : userServiceRecords.entrySet()) {
            if (e.getValue().token.equals(token)) {
                entry = e;
                break;
            }
        }

        if (entry == null) {
            LOGGER.w("Attach for unknown/expired token %s: no matching record, destroying the attaching binder instead", token);
            return null;
        }

        LOGGER.v("Received binder for service record %s", token);

        UserServiceRecord record = entry.getValue();
        if (record.service == binder) return record;
        if (record.removed || record.service != null) {
            return null;
        }
        return record;
    }

    private void destroyUnknownBinder(IBinder binder) {
        if (binder == null || binder instanceof Binder || !binder.pingBinder()) {
            return;
        }
        String descriptor;
        try {
            descriptor = binder.getInterfaceDescriptor();
        } catch (Throwable e) {
            LOGGER.w("Failed to get orphaned user service descriptor", e);
            return;
        }
        if (descriptor == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            binder.transact(ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy, data, reply, Binder.FLAG_ONEWAY);
        } catch (Throwable e) {
            LOGGER.w("Failed to destroy unknown/orphaned user service binder", e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public void attachUserService(IBinder binder, Bundle options) {
        Objects.requireNonNull(binder, "binder is null");
        String token = Objects.requireNonNull(options.getString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN), "token is null");

        UserServiceRecord record;
        synchronized (this) { record = findUserServiceLocked(binder, token); }
        if (record == null) destroyUnknownBinder(binder);
        else record.setBinder(binder);
    }

    public void onUserServiceRecordCreated(UserServiceRecord record, PackageInfo packageInfo) {

    }

    public void onUserServiceRecordRemoved(UserServiceRecord record) {

    }

    public void removeUserServicesForPackage(String packageName) {
        removeUserServicesForPackage(packageName, -1);
    }

    public void removeUserServicesForPackage(String packageName, int userId) {
        List<UserServiceRecord> records;
        synchronized (this) {
            List<UserServiceRecord> list = packageUserServiceRecords.get(packageName);
            records = list == null ? Collections.emptyList() : new ArrayList<>(list);
        }
        for (UserServiceRecord record : records) {
            if (userId == -1 || record.userId == userId) record.removeSelf();
        }
    }

}
