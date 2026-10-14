package com.mcpintelligence.fr3k.fixturehook;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Qualification only: never hooks a non-owned package or accepts arbitrary method names. */
public final class FixtureHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.mcpintelligence.fr3k.patchfixture";
    private static final String HUD = "com.mcpintelligence.fr3k.hud";
    private static final String ACTION = HUD + ".SET_OWNED_FIXTURE_BANNER";
    private static final String PERMISSION = HUD + ".permission.CONTROL_OVERLAY";
    private static final String HUD_SIGNER = "a28ac772fc0088cee4c30465382084fe6fa2aa4531b7f5a60624e0f2a33816a4";
    private final AtomicBoolean visible = new AtomicBoolean(true);
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private WeakReference<Activity> activity = new WeakReference<>(null);

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        if (!TARGET.equals(param.packageName)) return;
        final Class<?> features = XposedHelpers.findClass(TARGET + ".DemoFeatures", param.classLoader);
        XposedHelpers.findAndHookMethod(features, "showBanner", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                call.setResult(visible.get());
            }
        });
        XposedHelpers.findAndHookMethod(TARGET + ".FixtureActivity", param.classLoader, "onCreate", Bundle.class,
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    activity = new WeakReference<>((Activity) call.thisObject);
                    updateRuntimeLabel(((Activity) call.thisObject).getWindow().getDecorView());
                }
            });
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) throws Throwable {
                Context context = (Context) call.args[0];
                if (!TARGET.equals(context.getPackageName()) || !registered.compareAndSet(false, true)) return;
                byte[] signer = context.getPackageManager().getPackageInfo(HUD,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo.getApkContentsSigners()[0].toByteArray();
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(signer);
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                if (!HUD_SIGNER.equals(hex.toString())) throw new SecurityException("HUD signer mismatch");
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context ctx, Intent intent) {
                        if (!ACTION.equals(intent.getAction()) || !intent.hasExtra("visible")) return;
                        visible.set(intent.getBooleanExtra("visible", true));
                        boolean observed = (Boolean) XposedHelpers.callStaticMethod(features, "showBanner");
                        Activity current = activity.get();
                        if (current != null) updateBanner(current.getWindow().getDecorView(), observed);
                        Bundle receipt = new Bundle();
                        receipt.putInt("pid", Process.myPid());
                        receipt.putBoolean("observed", observed);
                        receipt.putString("operationId", intent.getStringExtra("operationId"));
                        setResultExtras(receipt);
                        setResultCode(Activity.RESULT_OK);
                        setResultData("OWNED_METHOD_OBSERVED");
                    }
                };
                IntentFilter filter = new IntentFilter(ACTION);
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    context.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_EXPORTED);
                else context.registerReceiver(receiver, filter, PERMISSION, null);
            }
        });
    }
    private static void updateRuntimeLabel(View view) {
        if (view instanceof android.widget.TextView) {
            android.widget.TextView text = (android.widget.TextView) view;
            CharSequence current = text.getText();
            if (current.toString().contains("Baseline only"))
                text.setText(current.toString().replace("Baseline only — no hook runtime installed",
                    "PREPARED RUNTIME LOADED — owned fixture only"));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) updateRuntimeLabel(group.getChildAt(i));
        }
    }
    private static void updateBanner(View view, boolean visible) {
        if ("fixture-owned-banner".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) updateBanner(group.getChildAt(i), visible);
        }
    }
}
