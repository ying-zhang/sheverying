package rikka.shizuku.server;

import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteCallbackList;

import androidx.annotation.Nullable;

import java.util.UUID;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

import moe.shizuku.server.IShizukuServiceConnection;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.Logger;

public abstract class UserServiceRecord {

    private Runnable startTimeoutCallback;

    private class ConnectionList extends RemoteCallbackList<IShizukuServiceConnection> {

        @Override
        public void onCallbackDied(IShizukuServiceConnection callback) {
            if (daemon || getRegisteredCallbackCount() != 0) {
                return;
            }

            LOGGER.v("Remove service record %s since it does not run as a daemon and all connections are gone", token);
            removeSelf();
        }
    }

    protected static final Logger LOGGER = new Logger("UserServiceRecord");

    private final IBinder.DeathRecipient deathRecipient;
    public final int versionCode;
    public int userId = -1;
    public String token;
    public volatile IBinder service;
    @Nullable
    private volatile String interfaceDescriptor;
    public final RemoteCallbackList<IShizukuServiceConnection> callbacks = new ConnectionList();
    public volatile boolean daemon;
    public volatile boolean starting;
    public volatile boolean pendingDestroy;
    public volatile boolean removed;
    public Runnable spawnRunnable;
    public int spawnAttempts;
    private final ArrayDeque<Runnable> notifications = new ArrayDeque<>();
    private boolean delivering;
    private boolean deathNotified;
    private boolean callbacksClosed;
    private boolean deathObserved;

    public UserServiceRecord(int versionCode, boolean daemon) {
        this.versionCode = versionCode;
        this.token = UUID.randomUUID().toString() + "-" + System.currentTimeMillis();
        this.deathRecipient = () -> {
            LOGGER.v("Binder for service record %s is dead", token);
            synchronized (this) { deathObserved = true; }
            removeSelf();
        };
        this.daemon = daemon;
    }

    public void setStartingTimeout(long timeoutMillis) {
        if (starting) {
            LOGGER.w("Service record %s is already starting", token);
            return;
        }

        LOGGER.v("Set starting timeout for service record %s: %d", token, timeoutMillis);

        starting = true;
        startTimeoutCallback = () -> {
            if (!removed && starting) {
                LOGGER.w("Service record %s is not started in %d ms", token, timeoutMillis);
                removeSelf();
            }
        };
        HandlerUtil.getMainHandler().postDelayed(startTimeoutCallback, timeoutMillis);
    }

    public void markRemoved() {
        removed = true;
        starting = false;
        if (startTimeoutCallback != null) {
            HandlerUtil.getMainHandler().removeCallbacks(startTimeoutCallback);
        }
    }

    public void setDaemon(boolean daemon) {
        this.daemon = daemon;
    }

    public void setBinder(IBinder binder) {
        LOGGER.v("Binder received for service record %s", token);

        String descriptor = null;
        try { descriptor = binder.getInterfaceDescriptor(); } catch (Throwable ignored) {}
        boolean reject;
        synchronized (this) {
            if (service == binder) return;
            reject = removed || pendingDestroy || service != null;
            if (!reject) {
                if (startTimeoutCallback != null) {
                    HandlerUtil.getMainHandler().removeCallbacks(startTimeoutCallback);
                }
                starting = false;
                service = binder;
                interfaceDescriptor = descriptor;
                try {
                    binder.linkToDeath(deathRecipient, 0);
                } catch (Throwable tr) {
                    deathObserved = true;
                    reject = true;
                }
            }
        }
        if (reject) {
            destroyBinder(binder, descriptor);
            if (deathObserved) removeSelf();
            return;
        }

        if (!daemon && callbacks.getRegisteredCallbackCount() == 0) {
            LOGGER.v("Service record %s attached with no connections left - removing", token);
            removeSelf();
            return;
        }

        broadcastBinderReceived();
    }

    public void broadcastBinderReceived() {
        enqueueNotification(false, false);
    }

    public void broadcastBinderDied() {
        enqueueNotification(true, false);
    }

    private void enqueueNotification(boolean died, boolean close) {
        boolean drain;
        synchronized (this) {
            if (callbacksClosed || (!died && !close && (removed || pendingDestroy))) return;
            if (!close && (!died || !deathNotified)) {
                if (died) deathNotified = true;
                List<IShizukuServiceConnection> snapshot = new ArrayList<>();
                int count = callbacks.beginBroadcast();
                try {
                    for (int i = 0; i < count; i++) snapshot.add(callbacks.getBroadcastItem(i));
                } finally {
                    callbacks.finishBroadcast();
                }
                IBinder connectedBinder = service;
                notifications.add(() -> {
                    for (IShizukuServiceConnection callback : snapshot) {
                        try {
                            if (died) callback.died(); else callback.connected(connectedBinder);
                        } catch (Throwable e) {
                            LOGGER.w("Failed to notify connection %s", token);
                        }
                    }
                });
            }
            if (close) {
                callbacksClosed = true;
                notifications.add(callbacks::kill);
            }
            drain = !delivering;
            if (drain) delivering = true;
        }
        if (!drain) return;
        // A reentrant callback only queues work. No lifecycle lock is held across Binder calls.
        while (true) {
            Runnable next;
            synchronized (this) {
                next = notifications.poll();
                if (next == null) { delivering = false; return; }
            }
            try { next.run(); } catch (Throwable e) { LOGGER.w("Connection notification failed %s", token); }
        }
    }

    public abstract void removeSelf();

    private void destroyBinder(IBinder binder, String descriptor) {
        if (binder == null || !binder.pingBinder() || descriptor == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            binder.transact(USER_SERVICE_TRANSACTION_destroy, data, null, Binder.FLAG_ONEWAY);
        } catch (Throwable e) {
            LOGGER.w("Failed to call destroy %s", token);
        } finally { data.recycle(); }
    }

    public void destroy() {
        IBinder activeService;
        String descriptor;
        boolean dead;
        synchronized (this) {
            if (removed) return;
            markRemoved();
            activeService = service;
            descriptor = interfaceDescriptor;
            dead = deathObserved || activeService == null;
        }
        try {
            if (activeService != null) {
                try { activeService.unlinkToDeath(deathRecipient, 0); } catch (Throwable ignored) {}
                dead |= !activeService.pingBinder();
            }
            if (dead) broadcastBinderDied();
            else destroyBinder(activeService, descriptor);
        } finally {
            enqueueNotification(false, true);
        }
    }
}
