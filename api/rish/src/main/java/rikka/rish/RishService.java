package rikka.rish;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side Binder dispatcher for {@code rish} interactive PTY sessions.
 * <p>
 * Incorporates thread-safe {@link ConcurrentHashMap} host registry, null-safe {@code env}
 * resolution, bounded exit-code collection, and automatic reaping of uncollected exited
 * hosts from Nightzuku API ({@code RishService.kt}) and Porter API ({@code PorshHostRegistry.kt}).
 */
public abstract class RishService {

    private static final String TAG = "RishService";
    private static final long EXIT_CODE_TIMEOUT_MILLIS = 5_000L;
    private static final long UNCOLLECTED_MAX_AGE_MILLIS = 60_000L;

    private final Map<Integer, RishHost> HOSTS = new ConcurrentHashMap<>();

    private static final boolean IS_ROOT = Os.getuid() == 0;

    private void reapUncollected() {
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<Integer, RishHost> entry : HOSTS.entrySet()) {
            RishHost host = entry.getValue();
            if (!host.hasExited()) {
                continue;
            }
            if (now - host.getExitedAtMillis() <= UNCOLLECTED_MAX_AGE_MILLIS) {
                continue;
            }
            if (HOSTS.remove(entry.getKey(), host)) {
                Log.d(TAG, "Reaped uncollected host of " + entry.getKey());
            }
        }
    }

    private synchronized void createHost(
            String[] args, String[] env, String dir,
            byte tty,
            ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr) {

        int callingPid = Binder.getCallingPid();
        reapUncollected();

        // Under adb, users need to set RISH_PRESERVE_ENV=1 to preserve env.
        // Under root, keep env unless RISH_PRESERVE_ENV=0 is set.
        boolean allowEnv = IS_ROOT;
        if (env != null) {
            for (String e : env) {
                if ("RISH_PRESERVE_ENV=1".equals(e)) {
                    allowEnv = true;
                    break;
                } else if ("RISH_PRESERVE_ENV=0".equals(e)) {
                    allowEnv = false;
                    break;
                }
            }
        }
        if (!allowEnv) {
            env = null;
        }

        if (HOSTS.containsKey(callingPid)) {
            for (ParcelFileDescriptor fd : new ParcelFileDescriptor[]{stdin, stdout, stderr}) {
                if (fd != null) try { fd.close(); } catch (java.io.IOException ignored) {}
            }
            throw new IllegalStateException("Only one uncollected rish session per process is supported");
        }
        RishHost host = new RishHost(args, env, dir, tty, stdin, stdout, stderr);
        host.start();
        Log.d(TAG, "Forked " + host.getPid());

        HOSTS.put(callingPid, host);
    }

    private void setWindowSize(long size) {
        int callingPid = Binder.getCallingPid();

        RishHost host = HOSTS.get(callingPid);
        if (host == null) {
            Log.d(TAG, "Not existing host created by " + callingPid);
            return;
        }

        host.setWindowSize(size);
    }

    private int getExitCode(boolean polling) {
        int callingPid = Binder.getCallingPid();

        RishHost host = HOSTS.get(callingPid);
        if (host == null) {
            Log.d(TAG, "Not existing host created by " + callingPid);
            return -1;
        }

        // New clients opt into nonblocking polling. Old clients retain their bounded wait.
        if (polling && !host.hasExited()) return Integer.MAX_VALUE;
        int code = polling ? host.getExitCode() : host.awaitExitCode(EXIT_CODE_TIMEOUT_MILLIS);
        if (!host.hasExited()) {
            return code;
        }
        HOSTS.remove(callingPid, host);
        return host.getExitCode();
    }

    public abstract void enforceCallingPermission(String func);

    public boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) {
        if (code == RishConfig.getTransactionCode(RishConfig.TRANSACTION_createHost)) {
            Log.d(TAG, "TRANSACTION_createHost");

            enforceCallingPermission("createHost");

            if (reply == null || (flags & IBinder.FLAG_ONEWAY) != 0) {
                return true;
            }

            ParcelFileDescriptor stdin;
            ParcelFileDescriptor stdout;
            ParcelFileDescriptor stderr = null;

            data.enforceInterface(RishConfig.getInterfaceToken());
            byte tty = data.readByte();
            stdin = data.readFileDescriptor();
            stdout = data.readFileDescriptor();
            if ((tty & RishConstants.ATTY_ERR) == 0) {
                stderr = data.readFileDescriptor();
            }
            String[] args = data.createStringArray();
            String[] env = data.createStringArray();
            String dir = data.readString();
            createHost(args, env, dir, tty, stdin, stdout, stderr);
            reply.writeNoException();
            return true;
        } else if (code == RishConfig.getTransactionCode(RishConfig.TRANSACTION_setWindowSize)) {
            Log.d(TAG, "TRANSACTION_setWindowSize");

            enforceCallingPermission("setWindowSize");

            data.enforceInterface(RishConfig.getInterfaceToken());
            long size = data.readLong();
            setWindowSize(size);
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        } else if (code == RishConfig.getTransactionCode(RishConfig.TRANSACTION_getExitCode)) {
            Log.d(TAG, "TRANSACTION_getExitCode");

            enforceCallingPermission("getExitCode");

            data.enforceInterface(RishConfig.getInterfaceToken());
            int exitCode = getExitCode(data.dataAvail() >= 4 && data.readInt() == 1);
            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(exitCode);
            }
            return true;
        }
        return false;
    }
}
