package com.blocknet.wallet;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import org.junit.Test;

public class MobileCorePolicyTest {
    @Test public void requestedForegroundSyncRequiresPermittedNetwork() {
        assertTrue(MobileCorePolicy.shouldRun(true, true, true, true));
        assertFalse(MobileCorePolicy.shouldRun(true, false, true, true));
        assertFalse(MobileCorePolicy.shouldRun(true, true, true, false));
        assertFalse(MobileCorePolicy.shouldRun(true, true, false, true));
    }

    @Test public void missingPreferenceDefaultsToForegroundOnly() {
        assertTrue(MobileCorePolicy.DEFAULT_FOREGROUND_ONLY);
        assertFalse(MobileCorePolicy.shouldRun(MobileCorePolicy.DEFAULT_FOREGROUND_ONLY,false,true,true));
        assertTrue(MobileCorePolicy.shouldRun(MobileCorePolicy.DEFAULT_FOREGROUND_ONLY,true,true,true));
    }

    @Test public void legacyMissingKeyIsReadOnlyAndExplicitContinuousChoiceSurvives() {
        SharedPreferences absent=preference(null);
        assertTrue(MobileCorePolicy.enabled(true,absent));
        assertFalse(MobileCorePolicy.enabled(false,absent));
        assertFalse(MobileCorePolicy.enabled(true,preference(false)));
        assertTrue(MobileCorePolicy.enabled(true,preference(true)));
    }

    private static SharedPreferences preference(Boolean saved) {
        return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                if (method.getName().equals("getBoolean")) {
                    assertEquals("mobile_core_foreground_only",args[0]);
                    return saved==null ? args[1] : saved;
                }
                if (method.getName().equals("edit")) fail("Policy read rewrote a saved preference");
                throw new AssertionError("Unexpected preference call: "+method.getName());
            });
    }

    @Test public void explicitContinuousModeCanStillRunInBackground() {
        assertTrue(MobileCorePolicy.shouldRun(false, false, true, true));
        assertFalse(MobileCorePolicy.shouldRun(false, false, false, true));
    }

    @Test public void activityHandoffAndRotationDoNotStopCore() {
        MobileCorePolicy.Lifecycle lifecycle = new MobileCorePolicy.Lifecycle();
        lifecycle.started(); // MainActivity
        lifecycle.started(); // ScannerActivity opens before MainActivity stops
        assertEquals(-1, lifecycle.stopped()); // MainActivity stops
        long scannerExit = lifecycle.stopped();
        lifecycle.started(); // MainActivity returns within the handoff grace period
        assertFalse(lifecycle.shouldPause(scannerExit));
        long rotation = lifecycle.stopped();
        lifecycle.started(); // Replacement MainActivity
        assertFalse(lifecycle.shouldPause(rotation));
        long externalPicker = lifecycle.stopped();
        assertTrue(lifecycle.shouldPause(externalPicker));
    }

    @Test public void processDeathResumesOnlyPersistedUserRequest() {
        assertTrue(MobileCorePolicy.shouldAutoResume(true, true, false));
        assertFalse(MobileCorePolicy.shouldAutoResume(false, true, false));
        assertFalse(MobileCorePolicy.shouldAutoResume(true, false, false)); // Stop sync
        assertFalse(MobileCorePolicy.shouldAutoResume(true, true, true)); // Already owned
    }

    @Test public void screenOnResumeWithoutStartRestoresForeground() {
        MobileCorePolicy.Lifecycle lifecycle = new MobileCorePolicy.Lifecycle();
        lifecycle.started();
        lifecycle.screenOff();
        assertTrue(MobileCorePolicy.shouldResumeOnActivity(lifecycle.hasStartedActivity(), true));
        assertFalse(MobileCorePolicy.shouldResumeOnActivity(lifecycle.hasStartedActivity(), false));
    }
}
