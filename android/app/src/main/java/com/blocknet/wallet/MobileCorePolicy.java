package com.blocknet.wallet;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.io.IOException;

/** Foreground-only mobile fallback. It stops Core; it is not an in-process suspend. */
final class MobileCorePolicy {
    private static final String KEY = "mobile_core_foreground_only";
    private static final String REQUESTED = "mobile_core_sync_requested";
    static final boolean DEFAULT_FOREGROUND_ONLY = true;
    private static volatile boolean foreground;
    private static final Lifecycle lifecycle = new Lifecycle();

    /** A delayed zero-activity observation is ignored after a handoff or rotation. */
    static final class Lifecycle {
        private int started;
        private long epoch;
        synchronized void started() { started++; epoch++; }
        synchronized long stopped() { started = Math.max(0, started - 1); return started == 0 ? ++epoch : -1; }
        synchronized void screenOff() { epoch++; }
        synchronized boolean shouldPause(long observedEpoch) { return started == 0 && epoch == observedEpoch; }
        synchronized boolean hasStartedActivity() { return started > 0; }
    }

    static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) { activityStarted(activity); }
            @Override public void onActivityStopped(Activity activity) { activityStopped(activity); }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityResumed(Activity activity) { activityResumed(activity); }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private static synchronized void activityStarted(Context context) {
        lifecycle.started();
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null || power.isInteractive()) setForeground(context, true);
    }

    private static synchronized void activityStopped(Context context) {
        long epoch = lifecycle.stopped();
        if (epoch < 0) return;
        // Activity handoffs, camera scanning and rotation should not restart Core.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (lifecycle.shouldPause(epoch)) setForeground(context, false);
        }, 750);
    }

    private static void activityResumed(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (shouldResumeOnActivity(lifecycle.hasStartedActivity(), power == null || power.isInteractive())) setForeground(context, true);
    }

    static boolean shouldResumeOnActivity(boolean started, boolean interactive) { return started && interactive; }

    static synchronized void screenOff(Context context) {
        lifecycle.screenOff();
        setForeground(context, false);
    }

    static boolean enabled(Context context) {
        // Missing key: new default. Existing explicit false remains continuous.
        // This read never rewrites a user's saved choice during upgrade.
        return enabled(BuildConfig.CORE_MOBILE_CLIENT,context.getSharedPreferences("wallet", Context.MODE_PRIVATE));
    }

    static boolean enabled(boolean mobileClient,SharedPreferences preferences) {
        return mobileClient && preferences.getBoolean(KEY,DEFAULT_FOREGROUND_ONLY);
    }

    static boolean permitted(Context context) { return shouldRun(enabled(context), foreground, true, true); }
    static boolean paused(Context context) { return enabled(context) && !foreground; }

    static boolean shouldRun(boolean enabled, boolean foreground, boolean requested, boolean networkAllowed) {
        return requested && networkAllowed && (!enabled || foreground);
    }

    static boolean shouldAutoResume(boolean foreground, boolean persistedRequest, boolean runningRequest) {
        return foreground && persistedRequest && !runningRequest;
    }

    static void setEnabled(Context context, boolean value) throws IOException {
        if (!context.getSharedPreferences("wallet", Context.MODE_PRIVATE).edit().putBoolean(KEY, value).commit())
            throw new IOException("Could not save mobile Core policy");
        recheck(context);
    }

    static boolean requested(Context context) {
        return BuildConfig.CORE_MOBILE_CLIENT && context.getSharedPreferences("wallet", Context.MODE_PRIVATE).getBoolean(REQUESTED, false);
    }

    static void setRequested(Context context, boolean value) throws IOException {
        if (BuildConfig.CORE_MOBILE_CLIENT && !context.getSharedPreferences("wallet", Context.MODE_PRIVATE).edit().putBoolean(REQUESTED, value).commit())
            throw new IOException("Could not save mobile sync request");
    }

    static void setForeground(Context context, boolean value) {
        if (foreground == value) return;
        foreground = value;
        if (!value && paused(context)) NativeRuntime.get(context).signalNetworkPause();
        recheck(context);
    }

    private static void recheck(Context context) {
        if (!BuildConfig.CORE_MOBILE_CLIENT) return;
        NativeRuntime runtime = NativeRuntime.get(context);
        try {
            if (shouldAutoResume(foreground, requested(context), runtime.syncRequested()))
                context.startForegroundService(new Intent(context, CoreService.class).setAction(CoreService.START).putExtra(CoreService.AUTO_RESUME, true));
            else if (runtime.syncRequested())
                context.startService(new Intent(context, CoreService.class).setAction(CoreService.RECHECK));
        }
        catch (IllegalStateException ignored) { /* The service is already stopping; foreground open will recheck. */ }
    }
}
