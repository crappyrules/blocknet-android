package com.blocknet.wallet;

import org.junit.Test;
import static org.junit.Assert.*;

public class BootstrapSyncStateTest {
    @Test public void onlyACompletedWalletWithoutPendingWorkCanPause() {
        assertFalse(BootstrapSyncState.shouldKeepRunning(false,false,false,false)); // first launch
        assertTrue(BootstrapSyncState.shouldKeepRunning(true,false,false,false)); // restore
        assertFalse(BootstrapSyncState.shouldKeepRunning(true,true,false,false));
        assertTrue(BootstrapSyncState.shouldKeepRunning(true,true,true,false)); // wallet replacement
        assertTrue(BootstrapSyncState.shouldKeepRunning(true,true,false,true)); // unresolved send
    }

    @Test public void completionRequiresPeerComparisonAndExactWalletScan() throws Exception {
        String id="a".repeat(64);
        assertTrue(BootstrapSyncState.ready(1,id,100,100,true,true,false));
        assertFalse(BootstrapSyncState.ready(1,id,100,100,true,false,false));
        assertFalse(BootstrapSyncState.ready(1,id,100,100,false,true,false));
        assertFalse(BootstrapSyncState.ready(1,id,100,99,true,true,false));
        assertFalse(BootstrapSyncState.ready(1,id,100,100,true,true,true));
        assertFalse(BootstrapSyncState.ready(1,"",100,100,true,true,false));
        assertFalse(BootstrapSyncState.ready(1,id,-1,-1,true,true,false));
    }

    @Test public void bootstrapExceptionDoesNotOverrideStopOrNetworkPreference() {
        assertTrue(MobileCorePolicy.shouldRun(true,false,true,true,true));
        assertFalse(MobileCorePolicy.shouldRun(true,false,true,false,true)); // manual Stop
        assertFalse(MobileCorePolicy.shouldRun(true,false,true,true,false)); // Wi-Fi only
        assertFalse(MobileCorePolicy.shouldRun(true,false,false,true,true)); // completed
        assertTrue(MobileCorePolicy.shouldRun(false,false,false,true,true)); // explicit continuous
    }
    @Test public void shutdownReceiptMustMatchExactObservedTipAndWallet() throws Exception {
        String id="a".repeat(64),hash="b".repeat(64);
        assertTrue(BootstrapSyncState.shutdownMatches(id,100,hash,1,id,100,hash));
        assertFalse(BootstrapSyncState.shutdownMatches(id,100,hash,0,id,100,hash));
        assertFalse(BootstrapSyncState.shutdownMatches(id,100,hash,1,id,100,"c".repeat(64)));
        assertFalse(BootstrapSyncState.shutdownMatches(id,100,hash,1,id,101,hash));
        assertFalse(BootstrapSyncState.shutdownMatches(id,100,hash,1,"c".repeat(64),100,hash));
    }
}
