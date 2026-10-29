package com.blocknet.wallet;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BackgroundChainSyncTest {
    @Test public void periodicChainJobRequiresARequestedForegroundOnlyMobileNode() {
        assertTrue(BackgroundChainSync.shouldSchedule(true,true,true));
        assertFalse(BackgroundChainSync.shouldSchedule(true,false,true)); // Stop sync
        assertFalse(BackgroundChainSync.shouldSchedule(true,true,false)); // continuous service
        assertFalse(BackgroundChainSync.shouldSchedule(false,true,true)); // desktop variant
        assertEquals(15L*60*1000,BackgroundChainSync.INTERVAL);
        assertTrue(BackgroundChainSync.needsSchedule(false,false));
        assertTrue(BackgroundChainSync.needsSchedule(true,false)); // upgrade a pre-reboot job
        assertFalse(BackgroundChainSync.needsSchedule(true,true));
    }

    @Test public void anIdlePeerlessOrStaleCoreIsNotAConfirmedTip() {
        assertTrue(BackgroundChainSync.ready(1,true,false));
        assertFalse(BackgroundChainSync.ready(0,true,false));
        assertFalse(BackgroundChainSync.ready(1,false,false));
        assertFalse(BackgroundChainSync.ready(1,true,true));
        assertEquals("no_status",BackgroundChainSync.outcome(false,false));
        assertEquals("incomplete",BackgroundChainSync.outcome(true,false));
        assertEquals("caught_up",BackgroundChainSync.outcome(true,true));
    }

    @Test public void onlyTheSameProvenWalletMayKeepItsMarkerAfterReencryption() {
        String id="a".repeat(64),digest="b".repeat(64),hash="c".repeat(64);
        assertTrue(BootstrapSyncState.proofAllowsRebind(id+"."+digest,1,id,100,hash));
        assertFalse(BootstrapSyncState.proofAllowsRebind(id+"."+digest,1,"d".repeat(64),100,hash));
        assertFalse(BootstrapSyncState.proofAllowsRebind(id+"."+digest,0,id,100,hash));
        assertFalse(BootstrapSyncState.proofAllowsRebind(id+"."+digest,1,id,-1,hash));
        assertFalse(BootstrapSyncState.proofAllowsRebind(id+"."+digest,1,id,100,"invalid"));
    }
}
