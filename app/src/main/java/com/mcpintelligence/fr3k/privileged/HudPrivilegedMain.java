package com.mcpintelligence.fr3k.privileged;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.util.UUID;

/** Launched only by authorised ADB/root with CLASSPATH set to the installed HUD APK. */
public final class HudPrivilegedMain {
    private static final String HUD = "com.mcpintelligence.fr3k.hud";
    public static void main(String[] args) throws Exception {
        final int uid = Process.myUid();
        if (uid != 2000) throw new SecurityException("This bootstrap profile requires authorised ADB shell UID");
        Looper.prepareMainLooper();
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getMethod("systemMain").invoke(null);
        Context systemContext = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
        Context context = systemContext;
        final PackageManager pm = context.getPackageManager();
        final int hudUid = pm.getApplicationInfo(HUD, 0).uid;
        final String hudSource = pm.getApplicationInfo(HUD, 0).sourceDir;
        final byte[] hudSigner = java.security.MessageDigest.getInstance("SHA-256").digest(
            pm.getPackageInfo(HUD, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo.getApkContentsSigners()[0].toByteArray());
        final String generation = UUID.randomUUID().toString();
        final Looper looper = Looper.myLooper();
        IHudPrivilegedService.Stub service = new IHudPrivilegedService.Stub() {
            private void enforceHud() {
                if (Binder.getCallingUid() != hudUid) throw new SecurityException("HUD UID required");
                long token = Binder.clearCallingIdentity();
                try {
                    android.content.pm.ApplicationInfo installed = pm.getApplicationInfo(HUD, 0);
                    byte[] signer = java.security.MessageDigest.getInstance("SHA-256").digest(
                        pm.getPackageInfo(HUD, PackageManager.GET_SIGNING_CERTIFICATES)
                            .signingInfo.getApkContentsSigners()[0].toByteArray());
                    if (installed.uid != hudUid || !hudSource.equals(installed.sourceDir)
                            || !java.security.MessageDigest.isEqual(hudSigner, signer))
                        throw new SecurityException("HUD installation changed; re-bootstrap required");
                } catch (PackageManager.NameNotFoundException | java.security.NoSuchAlgorithmException e) {
                    throw new SecurityException("HUD identity could not be verified");
                } finally { Binder.restoreCallingIdentity(token); }
            }
            @Override public int getProtocolVersion() { enforceHud(); return 1; }
            @Override public int getServerUid() { enforceHud(); return uid; }
            @Override public String getGeneration() { enforceHud(); return generation; }
            @Override public Bundle inspectPackage(String pkg) {
                enforceHud();
                if (pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
                    throw new IllegalArgumentException("Invalid package name");
                long identity = Binder.clearCallingIdentity();
                try {
                    PackageInfo info = pm.getPackageInfo(pkg, 0);
                    Bundle result = new Bundle();
                    result.putString("package", info.packageName);
                    result.putLong("versionCode", info.getLongVersionCode());
                    result.putString("basePath", info.applicationInfo.sourceDir);
                    result.putStringArray("splitPaths", info.applicationInfo.splitSourceDirs);
                    result.putInt("serviceUid", uid);
                    result.putString("generation", generation);
                    return result;
                } catch (PackageManager.NameNotFoundException e) {
                    throw new IllegalArgumentException("Package not installed: " + pkg);
                } finally { Binder.restoreCallingIdentity(identity); }
            }
            @Override public void shutdown() {
                enforceHud();
                new Handler(looper).post(() -> System.exit(0));
            }
        };
        Bundle extras = new Bundle();
        extras.putBinder("service", service);
        Class<?> amClass = Class.forName("android.app.ActivityManager");
        java.lang.reflect.Method getService = amClass.getDeclaredMethod("getService");
        getService.setAccessible(true);
        Object am = getService.invoke(null);
        Class<?> iam = Class.forName("android.app.IActivityManager");
        Binder providerToken = new Binder();
        String authority = HUD + ".privileged";
        Object holder = iam.getMethod("getContentProviderExternal", String.class, int.class,
            android.os.IBinder.class, String.class).invoke(am, authority, 0, providerToken, "fR3k HUD bootstrap");
        if (holder == null) throw new IllegalStateException("HUD provider not found");
        Object provider = holder.getClass().getField("provider").get(holder);
        Bundle reply = (Bundle) Class.forName("android.content.IContentProvider")
            .getMethod("call", android.content.AttributionSource.class, String.class,
                String.class, String.class, Bundle.class)
            .invoke(provider, new android.content.AttributionSource.Builder(uid)
                .setPackageName("com.android.shell").build(), authority, "attach", null, extras);
        if (reply == null || !reply.getBoolean("attached")) throw new SecurityException("HUD rejected service");
        android.os.IBinder clientLifetime = reply.getBinder("clientLifetime");
        if (clientLifetime == null) throw new SecurityException("Missing client lifecycle binder");
        clientLifetime.linkToDeath(() -> System.exit(0), 0);
        iam.getMethod("removeContentProviderExternal", String.class, android.os.IBinder.class)
            .invoke(am, authority, providerToken);
        // Bounded lifetime. Restart/re-authorise explicitly rather than establishing hidden persistence.
        new Handler(looper).postDelayed(() -> System.exit(0), 30 * 60 * 1000L);
        System.out.println("HUD_PRIVILEGED_READY uid=" + uid + " protocol=1");
        Looper.loop();
    }
}
