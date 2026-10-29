package com.blocknet.wallet;

import android.app.Application;

/** Tracks foreground state across wallet, scanner and activity handoffs. */
public final class MobileCoreApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        MobileCorePolicy.install(this);
        try { BackgroundChainSync.reconcileSchedule(this); } catch (RuntimeException ignored) { }
    }
}
