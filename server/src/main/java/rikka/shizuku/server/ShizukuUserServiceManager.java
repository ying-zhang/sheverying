package rikka.shizuku.server;

import android.content.pm.PackageInfo;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import moe.shizuku.starter.ServiceStarter;
import rikka.shizuku.server.util.Android17Compat;
import rikka.shizuku.server.util.UserHandleCompat;

public class ShizukuUserServiceManager extends UserServiceManager {

    private final Map<UserServiceRecord, ApkChangedListener> apkChangedListeners = new ConcurrentHashMap<>();

    private final ScheduledExecutorService packageLookup = Executors.newSingleThreadScheduledExecutor();

    public ShizukuUserServiceManager() {
        super();
    }

    @Override
    public String getUserServiceStartCmd(
            UserServiceRecord record, String key, String token, String packageName,
            String classname, String processNameSuffix, int callingUid, boolean use32Bits, boolean debug) {

        String appProcess = "/system/bin/app_process";
        if (use32Bits && new File("/system/bin/app_process32").exists()) {
            appProcess = "/system/bin/app_process32";
        }
        return ServiceStarter.commandForUserService(
                appProcess,
                ShizukuService.getManagerApplicationInfo().sourceDir,
                token, packageName, classname, processNameSuffix, callingUid, debug);
    }

    @Override
    public void onUserServiceRecordCreated(UserServiceRecord record, PackageInfo packageInfo) {
        super.onUserServiceRecordCreated(record, packageInfo);

        String packageName = packageInfo.packageName;
        int ownerUserId = UserHandleCompat.getUserId(packageInfo.applicationInfo.uid);
        ApkChangedListener listener = new ApkChangedListener() {
            private long generation;
            private int attempts;
            private int missing;

            @Override
            public void onApkChanged() {
                packageLookup.execute(() -> {
                    attempts = 0;
                    missing = 0;
                    checkPackage(++generation);
                });
            }

            private void checkPackage(long expectedGeneration) {
                if (generation != expectedGeneration || record.removed || apkChangedListeners.get(record) != this) return;
                attempts++;
                String newSourceDir = null;
                boolean unavailable = false;
                try {
                    PackageInfo pi = Android17Compat.getPackageInfoOrThrow(packageName, 0, ownerUserId);
                    if (pi != null) {
                        if (pi.applicationInfo == null || pi.applicationInfo.sourceDir == null) {
                            unavailable = true;
                        } else {
                            newSourceDir = pi.applicationInfo.sourceDir;
                        }
                    }
                } catch (Exception e) {
                    unavailable = true;
                    Android17Compat.invalidate("package");
                    LOGGER.w(e, "Package lookup unavailable for %s", packageName);
                }
                synchronized (ShizukuUserServiceManager.this) {
                    if (record.removed || apkChangedListeners.get(record) != this) return;
                    if (newSourceDir != null) {
                        ApkChangedObservers.stop(this);
                        ApkChangedObservers.start(newSourceDir, this);
                        return;
                    }
                    missing = unavailable ? 0 : missing + 1;
                }
                if (missing >= 3) {
                    record.removeSelf();
                    return;
                }
                if (attempts < 5) {
                    packageLookup.schedule(() -> checkPackage(expectedGeneration), 1, TimeUnit.SECONDS);
                } else {
                    LOGGER.w("Keeping service %s: package removal could not be confirmed", record.token);
                    attempts = 0;
                    packageLookup.schedule(() -> checkPackage(expectedGeneration), 30, TimeUnit.SECONDS);
                }
            }
        };
        apkChangedListeners.put(record, listener);

        ApkChangedObservers.start(packageInfo.applicationInfo.sourceDir, listener);
    }

    @Override
    public void onUserServiceRecordRemoved(UserServiceRecord record) {
        super.onUserServiceRecordRemoved(record);
        ApkChangedListener listener = apkChangedListeners.get(record);
        if (listener != null) {
            ApkChangedObservers.stop(listener);
            apkChangedListeners.remove(record);
        }
    }
}
