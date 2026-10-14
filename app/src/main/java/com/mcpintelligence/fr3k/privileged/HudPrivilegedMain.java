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
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Launched only by authorised ADB/root with CLASSPATH set to the installed HUD APK. */
public final class HudPrivilegedMain {
    private static final String HUD = "com.mcpintelligence.fr3k.hud";
    private static final String PACKAGE_PATTERN = "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+";
    /** Typed app-op allow-list. A caller can never name an op that is not on this list. */
    private static final List<String> APP_OPS = Arrays.asList(
        "WAKE_LOCK", "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND", "START_FOREGROUND",
        "VIBRATE", "POST_NOTIFICATION", "SYSTEM_ALERT_WINDOW", "GET_USAGE_STATS");
    private static final List<String> MODES = Arrays.asList("default", "allow", "ignore", "deny");

    public static void main(String[] args) throws Exception {
        final int uid = Process.myUid();
        // Shell (2000) is the unrooted tier; uid 0 is the rooted tier that unlocks root-gated ops.
        if (uid != 2000 && uid != 0) throw new SecurityException("Requires authorised shell or root bootstrap");
        final String tier = uid == 0 ? "ROOT" : "SHELL";
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
            private void enforceRoot(String operation) {
                if (uid != 0) throw new SecurityException(operation + " requires the ROOT tier; bootstrap with adb root");
            }
            private String requirePackage(String value) {
                if (value == null || !value.matches(PACKAGE_PATTERN)) throw new IllegalArgumentException("invalid package name");
                return value;
            }
            private String requireOp(String op) {
                if (op == null || !APP_OPS.contains(op)) throw new IllegalArgumentException("op is not allow-listed: " + op);
                return op;
            }
            private String run(String... command) throws Exception {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                // java.lang.Process: android.os.Process is already imported for the uid check.
                java.lang.Process process = builder.start();
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) output.append(line).append('\n');
                }
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("command timed out");
                }
                return output.toString();
            }
            @Override public int getProtocolVersion() { enforceHud(); return 2; }
            @Override public String getCapabilityTier() { enforceHud(); return tier; }
            @Override public List<String> listAllowedOps() { enforceHud(); return new ArrayList<>(APP_OPS); }
            @Override public Bundle getAppOp(String pkg, String op) {
                enforceHud();
                pkg = requirePackage(pkg);
                op = requireOp(op);
                try {
                    Bundle result = new Bundle();
                    result.putString("package", pkg);
                    result.putString("op", op);
                    result.putString("mode", parseAppOpMode(run("/system/bin/cmd", "appops", "get", pkg, op), op));
                    result.putString("tier", tier);
                    return result;
                } catch (Exception e) {
                    throw new IllegalStateException("appops read failed: " + e.getMessage());
                }
            }
            @Override public Bundle setAppOp(String pkg, String op, String mode) {
                enforceHud();
                pkg = requirePackage(pkg);
                op = requireOp(op);
                if (mode == null || !MODES.contains(mode)) throw new IllegalArgumentException("mode not allowed: " + mode);
                try {
                    String before = parseAppOpMode(run("/system/bin/cmd", "appops", "get", pkg, op), op);
                    run("/system/bin/cmd", "appops", "set", pkg, op, mode);
                    String after = parseAppOpMode(run("/system/bin/cmd", "appops", "get", pkg, op), op);
                    if (!mode.equals(after)) throw new IllegalStateException("wanted " + mode + " but observed " + after);
                    Bundle result = new Bundle();
                    result.putString("package", pkg);
                    result.putString("op", op);
                    result.putString("before", before);
                    result.putString("after", after);
                    result.putString("tier", tier);
                    return result;
                } catch (Exception e) {
                    throw new IllegalStateException("appops write failed: " + e.getMessage());
                }
            }
            @Override public Bundle listPrivateDir(String pkg) {
                enforceHud();
                enforceRoot("listPrivateDir");
                pkg = requirePackage(pkg);
                long identity = Binder.clearCallingIdentity();
                try {
                    File dir = new File("/data/data/" + pkg);
                    if (!dir.isDirectory()) dir = new File("/data/user/0/" + pkg);
                    if (!dir.isDirectory()) throw new IllegalArgumentException("no private directory for " + pkg);
                    File[] children = dir.listFiles();
                    String[] names = new String[children == null ? 0 : children.length];
                    for (int i = 0; i < names.length; i++) names[i] = children[i].getName();
                    Bundle result = new Bundle();
                    result.putString("package", pkg);
                    result.putStringArray("names", names);
                    result.putString("tier", tier);
                    return result;
                } finally { Binder.restoreCallingIdentity(identity); }
            }
            @Override public Bundle setComponentEnabled(String pkg, String component, boolean enabled) {
                enforceHud();
                enforceRoot("setComponentEnabled");
                pkg = requirePackage(pkg);
                if (component == null || !component.matches("[A-Za-z0-9_.$]+"))
                    throw new IllegalArgumentException("invalid component");
                try {
                    run("/system/bin/pm", enabled ? "enable" : "disable-user", "--user", "0", pkg + "/" + component);
                    Bundle result = new Bundle();
                    result.putString("package", pkg);
                    result.putString("component", component);
                    result.putBoolean("enabled", enabled);
                    result.putString("tier", tier);
                    return result;
                } catch (Exception e) {
                    throw new IllegalStateException("component change failed: " + e.getMessage());
                }
            }
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
                    result.putString("tier", tier);
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
        System.out.println("HUD_PRIVILEGED_READY uid=" + uid + " tier=" + tier + " protocol=2");
        Looper.loop();
    }

    /** Parses "WAKE_LOCK: allow" style output. Unrecognised output is reported as unknown, never guessed. */
    private static String parseAppOpMode(String output, String op) {
        if (output == null) return "unknown";
        for (String line : output.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(op)) continue;
            int colon = trimmed.indexOf(':');
            if (colon < 0) continue;
            String mode = trimmed.substring(colon + 1).trim().split("\\s+")[0];
            if (MODES.contains(mode)) return mode;
        }
        return "unknown";
    }
}
