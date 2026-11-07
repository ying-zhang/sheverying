package moe.shizuku.server;

interface IRemoteProcess {

    // Each stream can be transferred once per remote process. Clients must cache
    // the returned stream (as ShizukuRemoteProcess does), including after close.
    // Repeated raw Binder getters throw IllegalStateException.

    ParcelFileDescriptor getOutputStream();

    ParcelFileDescriptor getInputStream();

    ParcelFileDescriptor getErrorStream();

    int waitFor();

    int exitValue();

    void destroy();

    boolean alive();

    boolean waitForTimeout(long timeout, String unit);
}
