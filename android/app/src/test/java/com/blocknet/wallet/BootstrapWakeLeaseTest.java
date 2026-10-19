package com.blocknet.wallet;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class BootstrapWakeLeaseTest {
    private static final String WALLET = "a".repeat(64);
    private static final String OTHER_WALLET = "b".repeat(64);
    private static final String HASH = "c".repeat(64);
    private static final long MINUTE = 60_000L;

    private static final class FakeLock implements BootstrapWakeLease.Lock {
        boolean held;
        int acquires,releases;
        long lastTimeout,maxTimeout;
        @Override public void acquire(long timeoutMs) {
            assertFalse(held); assertTrue(timeoutMs>0);
            held=true;acquires++;lastTimeout=timeoutMs;
            maxTimeout=Math.max(maxTimeout,timeoutMs);
        }
        @Override public void release() { assertTrue(held);held=false;releases++; }
        @Override public boolean isHeld() { return held; }
    }

    private static boolean eligible(boolean network,boolean pending,
                                    boolean requested,boolean running,boolean stopping) {
        return BootstrapWakeLease.backgroundEligible(stopping,true,true,requested,running,
            network,pending,false,false);
    }

    @Test public void selectedWalletIdentityMatchesCoreBootstrapDomain() throws Exception {
        assertEquals("62c42e947ef27f5060b96297c445cf53b5959552fae0429d24e9cc62f5b1d46b",
            BootstrapWakeLease.identityForAddress("synthetic-address"));
    }

    @Test public void onlyPendingRequestedBackgroundSyncOnAllowedNetworkIsEligible() {
        assertTrue(eligible(true,true,true,true,false));
        assertFalse(eligible(false,true,true,true,false)); // Wi-Fi lost
        assertFalse(eligible(true,false,true,true,false)); // Scan complete
        assertFalse(eligible(true,true,false,true,false)); // Manual Stop
        assertFalse(eligible(true,true,true,false,false)); // Runtime stopped
        assertFalse(eligible(true,true,true,true,true)); // Service teardown
        assertFalse(BootstrapWakeLease.backgroundEligible(false,false,true,true,true,true,true,false,false));
        assertFalse(BootstrapWakeLease.backgroundEligible(false,true,false,true,true,true,true,false,false));
        assertFalse(BootstrapWakeLease.backgroundEligible(false,true,true,true,true,true,true,true,false));
        assertFalse(BootstrapWakeLease.backgroundEligible(false,true,true,true,true,true,true,false,true));
    }

    @Test public void progressingChainAndWalletContinueBeyondOneHourWithShortLeases() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        assertEquals(BootstrapWakeLease.SLICE_MS,lease.update(0,true,true));
        assertFalse(lease.observeProgress(0,"mainnet","a.wallet",1,WALLET,HASH,100,0)); // First sample is a baseline.
        for(long t=MINUTE;t<=70*MINUTE;t+=MINUTE) {
            if(t%(5*MINUTE)==0)
                assertTrue(lease.observeProgress(t,"mainnet","a.wallet",1,WALLET,HASH,100+t/(5*MINUTE),t/(5*MINUTE)));
            assertTrue("progressing initial sync stopped at minute "+t/MINUTE,
                lease.update(t,true,true)>0);
            assertFalse(lease.expired());
        }
        assertTrue(lock.held);
        assertTrue(lock.acquires>1);
        assertEquals(BootstrapWakeLease.SLICE_MS,lock.maxTimeout);
        lease.update(70*MINUTE+1,false,false); // Scan complete.
        assertFalse(lock.held);
    }

    @Test public void callbacksReconnectAndUnchangedSamplesCannotRenewStallWindow() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        lease.update(0,true,true);
        assertFalse(lease.observeProgress(0,"mainnet","a.wallet",1,WALLET,HASH,100,40));
        for(long t=MINUTE;t<BootstrapWakeLease.STALL_MS;t+=MINUTE) {
            assertFalse(lease.observeProgress(t,"mainnet","a.wallet",1,WALLET,HASH,100,40));
            assertTrue(lease.update(t,true,true)>0); // Frequent callbacks are not progress.
        }
        lease.update(14*MINUTE+1,false,false); // Network loss releases.
        assertFalse(lock.held);
        assertTrue(lease.update(14*MINUTE+2,true,true)>0); // Reconnect uses remaining budget.
        assertEquals(0,lease.update(BootstrapWakeLease.STALL_MS,true,true));
        assertTrue(lease.expired());
        assertFalse(lock.held);
        assertFalse(lease.observeProgress(BootstrapWakeLease.STALL_MS+1,"mainnet","a.wallet",1,WALLET,HASH,101,41));
        assertEquals(0,lease.update(BootstrapWakeLease.STALL_MS+2,true,true));
        assertTrue(lock.maxTimeout<=BootstrapWakeLease.SLICE_MS);
    }

    @Test public void onlyValidatedAdvancementOfSameWalletRenewsStallWindow() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        lease.update(0,true,true);
        assertFalse(lease.observeProgress(0,"mainnet","a.wallet",1,WALLET,HASH,100,40));
        assertFalse(lease.observeProgress(5*MINUTE,"mainnet","a.wallet",1,OTHER_WALLET,HASH,101,41));
        assertFalse(lease.observeProgress(6*MINUTE,"mainnet","a.wallet",1,WALLET,"bad",101,41));
        assertFalse(lease.observeProgress(7*MINUTE,"mainnet","a.wallet",1,WALLET,HASH,99,39)); // Reorg is not advancement.
        assertFalse(lease.observeProgress(8*MINUTE,"mainnet","a.wallet",1,WALLET,HASH,100,101)); // Invalid scan tip.
        assertTrue(lease.observeProgress(9*MINUTE,"mainnet","a.wallet",1,WALLET,HASH,100,41)); // Scan advances.
        assertFalse(lease.observeProgress(14*MINUTE,"mainnet","a.wallet",1,WALLET,HASH,100,41));
        assertTrue(lease.update(16*MINUTE,true,true)>0); // Past the original 15-minute limit.
        assertTrue(lease.observeProgress(20*MINUTE,"mainnet","a.wallet",1,WALLET,HASH,101,41)); // Chain advances.
        assertTrue(lease.update(34*MINUTE,true,true)>0);
        assertEquals(0,lease.update(35*MINUTE,true,true));
        assertTrue(lease.expired());
    }

    @Test public void completionFailureStopAndTeardownRelease() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        lease.update(0,true,true);
        assertEquals(0,lease.update(100,false,false)); // Completion or screen wake.
        assertFalse(lock.held);
        lease.update(200,true,true);
        assertEquals(0,lease.update(300,false,false)); // Stop or denied network.
        assertFalse(lock.held);
        lease.update(400,true,true);
        lease.expire(); // Background lock or proof failure pauses immediately.
        assertFalse(lock.held);
        assertEquals(0,lease.update(500,true,true));
        lease.close(); // Service teardown is idempotent.
        assertFalse(lock.held);
        lease.reset(); // Only a visible reopen can start a new session.
        assertFalse(lease.expired());
        assertEquals(BootstrapWakeLease.SLICE_MS,lease.update(600,true,true));
        lease.close();assertFalse(lock.held);
    }

    @Test public void timedSystemReleaseIsRecoveredOnlyWithinRemainingStallWindow() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        lease.update(0,true,true);
        lock.held=false; // Model PowerManager timing out before the handler runs.
        assertEquals(BootstrapWakeLease.SLICE_MS,lease.update(3*MINUTE,true,true));
        assertEquals(2,lock.acquires);
        assertEquals(0,lease.update(BootstrapWakeLease.STALL_MS,true,true));
        assertFalse(lock.held);
    }

    @Test public void interactiveBackgroundHasStallCutoffWithoutCpuWakeLock() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        assertEquals(BootstrapWakeLease.STALL_MS,lease.update(0,true,false));
        assertFalse(lease.observeProgress(0,"mainnet","a.wallet",1,WALLET,HASH,100,40));
        for(long t=MINUTE;t<BootstrapWakeLease.STALL_MS;t+=MINUTE) {
            assertFalse(lease.observeProgress(t,"mainnet","a.wallet",1,WALLET,HASH,100,40));
            assertTrue(lease.update(t,true,false)>0);
        }
        assertEquals(0,lock.acquires); // Another app keeps the display interactive.
        assertEquals(0,lease.update(BootstrapWakeLease.STALL_MS,true,false));
        assertTrue(BootstrapWakeLease.shouldPause(eligible(true,true,true,true,false),lease.expired()));
    }

    @Test public void interactiveBackgroundCanProgressForOverAnHourWithoutWakeLock() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        lease.update(0,true,false);
        assertFalse(lease.observeProgress(0,"mainnet","a.wallet",1,WALLET,HASH,100,40));
        for(long t=MINUTE;t<=70*MINUTE;t+=MINUTE) {
            if(t%(5*MINUTE)==0)
                assertTrue(lease.observeProgress(t,"mainnet","a.wallet",1,WALLET,HASH,100+t/(5*MINUTE),40));
            assertTrue(lease.update(t,true,false)>0);
        }
        assertEquals(0,lock.acquires);
        assertFalse(lease.expired());
    }

    @Test public void screenOnOffKeepsStallAccountingButReleasesNativeLock() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        lease.update(0,true,false); // Background with another app on screen.
        assertFalse(lease.observeProgress(0,"mainnet","a.wallet",1,WALLET,HASH,100,40));
        lease.update(5*MINUTE,true,true); // Screen locks.
        assertTrue(lock.held);
        lease.update(6*MINUTE,true,false); // Screen wakes behind another app.
        assertFalse(lock.held);
        assertTrue(lease.observeProgress(11*MINUTE,"mainnet","a.wallet",1,WALLET,HASH,101,40));
        assertTrue(lease.update(16*MINUTE,true,false)>0); // Old cutoff did not stop real progress.
        lease.update(17*MINUTE,true,true);
        assertTrue(lock.held);
        assertEquals(0,lease.update(26*MINUTE,true,true)); // The new cutoff still applies.
        assertFalse(lock.held);
    }

    @Test public void completedWalletSelectionResetsScopeButUnexpectedIdentityCannot() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        lease.update(0,true,true);
        assertFalse(lease.observeProgress(0,"mainnet","a.wallet",1,WALLET,HASH,100,90));
        assertFalse(lease.observeProgress(5*MINUTE,"mainnet","b.wallet",1,OTHER_WALLET,HASH,101,0));
        assertFalse(lease.observeProgress(5*MINUTE,"mainnet","a.wallet",1,OTHER_WALLET,HASH,101,0));
        lease.update(6*MINUTE,false,false); // Wallet app returns to foreground.
        lease.selectedWallet("mainnet","b.wallet",1,OTHER_WALLET); // Committed foreground switch event.
        assertEquals(BootstrapWakeLease.SLICE_MS,lease.update(7*MINUTE,true,true));
        assertFalse(lease.observeProgress(7*MINUTE,"mainnet","b.wallet",1,OTHER_WALLET,HASH,101,0));
        for(long t=12*MINUTE;t<=72*MINUTE;t+=5*MINUTE) {
            assertTrue(lease.observeProgress(t,"mainnet","b.wallet",1,OTHER_WALLET,HASH,
                101+(t-7*MINUTE)/(5*MINUTE),(t-7*MINUTE)/(5*MINUTE)));
            assertTrue(lease.update(t,true,true)>0);
        }
        assertFalse(lease.expired());
        assertFalse(lease.observeProgress(73*MINUTE,"mainnet","b.wallet",0,OTHER_WALLET,HASH,200,60));
        assertFalse(lease.observeProgress(73*MINUTE,"testnet","b.wallet",1,OTHER_WALLET,HASH,200,60));
    }

    @Test public void queuedExpiryCannotCommitAfterForegroundReturnOrManualStop() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        lease.update(0,true,true);
        lease.update(BootstrapWakeLease.STALL_MS,true,true); // Worker observed expiry.
        assertTrue(lease.expired());
        assertFalse(BootstrapWakeLease.shouldPause(
            BootstrapWakeLease.backgroundEligible(false,true,false,true,true,true,true,false,false),
            lease.expired())); // Foreground return is processed before pause commit.
        assertFalse(BootstrapWakeLease.shouldPause(eligible(true,true,false,false,true),lease.expired()));
        assertEquals(0,lease.update(BootstrapWakeLease.STALL_MS+1,false,false));
        assertFalse(lock.held);
        assertFalse(BootstrapWakeLease.shouldPause(eligible(false,true,true,true,false),lease.expired()));
    }

    @Test public void onlyCompletedCurrentWalletSelectionMayResetProgress() {
        assertTrue(BootstrapWakeLease.completedSelectionMatches(false,true,true,false,7,7,
            "mainnet","mainnet","b.wallet","b.wallet"));
        assertFalse(BootstrapWakeLease.completedSelectionMatches(true,true,true,false,7,7,
            "mainnet","mainnet","b.wallet","b.wallet")); // Stop
        assertFalse(BootstrapWakeLease.completedSelectionMatches(false,false,true,false,7,7,
            "mainnet","mainnet","b.wallet","b.wallet")); // Manual Stop request
        assertFalse(BootstrapWakeLease.completedSelectionMatches(false,true,false,false,7,7,
            "mainnet","mainnet","b.wallet","b.wallet")); // Runtime no longer requested
        assertFalse(BootstrapWakeLease.completedSelectionMatches(false,true,true,true,7,7,
            "mainnet","mainnet","b.wallet","b.wallet")); // Switch not committed
        assertFalse(BootstrapWakeLease.completedSelectionMatches(false,true,true,false,6,7,
            "mainnet","mainnet","b.wallet","b.wallet")); // Old Core generation
        assertFalse(BootstrapWakeLease.completedSelectionMatches(false,true,true,false,7,7,
            "testnet","mainnet","b.wallet","b.wallet"));
        assertFalse(BootstrapWakeLease.completedSelectionMatches(false,true,true,false,7,7,
            "mainnet","mainnet","a.wallet","b.wallet"));
    }

    @Test public void selectionBeforeServiceGenerationReconciliationIsRetriedOnce() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        BootstrapWakeLease.SelectionGate gate=new BootstrapWakeLease.SelectionGate();
        lease.update(0,true,true);
        assertFalse(lease.observeProgress(0,"mainnet","a.wallet",1,WALLET,HASH,100,80));
        // Core has restarted and committed B, but the service START worker has not
        // yet published ownedGeneration=2 when RECHECK reaches the main looper.
        assertFalse(gate.apply(4*MINUTE,false,1,false,true,true,false,2,1,
            "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET,lease));
        assertEquals(0,gate.acknowledgedSerial());
        assertFalse(lease.observeProgress(4*MINUTE,"mainnet","b.wallet",2,OTHER_WALLET,HASH,101,0));
        // Reconciliation completes; monitor/progress retries the same serial.
        assertTrue(gate.apply(4*MINUTE+1,false,1,false,true,true,false,2,2,
            "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET,lease));
        assertEquals(1,gate.acknowledgedSerial());
        assertFalse(gate.apply(4*MINUTE+2,false,1,false,true,true,false,2,2,
            "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET,lease));
        assertEquals(BootstrapWakeLease.SLICE_MS,lease.update(5*MINUTE,true,true));
        assertFalse(lease.observeProgress(5*MINUTE,"mainnet","b.wallet",2,OTHER_WALLET,HASH,101,0));
        for(long t=10*MINUTE;t<=75*MINUTE;t+=5*MINUTE) {
            assertTrue(lease.observeProgress(t,"mainnet","b.wallet",2,OTHER_WALLET,HASH,
                101+(t-5*MINUTE)/(5*MINUTE),(t-5*MINUTE)/(5*MINUTE)));
            assertTrue(lease.update(t,true,true)>0);
        }
        assertFalse(lease.expired());
        // Neither an uncommitted selection nor a later Stop can reset it.
        assertFalse(gate.apply(76*MINUTE,false,2,false,true,true,true,2,2,
            "mainnet","mainnet","c.wallet","c.wallet",WALLET,lease));
        assertFalse(gate.apply(76*MINUTE+1,false,2,true,false,false,false,2,2,
            "mainnet","mainnet","c.wallet","c.wallet",WALLET,lease));
        assertEquals(1,gate.acknowledgedSerial());
    }

    @Test public void pendingCommittedSelectionMustNotInheritExpiredOldWalletDeadline() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        BootstrapWakeLease.SelectionGate gate=new BootstrapWakeLease.SelectionGate();
        lease.update(0,true,true);
        assertFalse(lease.observeProgress(0,"mainnet","a.wallet",1,WALLET,HASH,100,80));
        lease.update(14*MINUTE,false,false); // The user returns and switches wallets.
        assertFalse(gate.apply(14*MINUTE,true,1,false,true,true,false,2,1,
            "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET,lease));
        long backgroundAt=BootstrapWakeLease.STALL_MS+1;
        long remaining=gate.pendingHandoff(backgroundAt,1,true,false,true,true,false,2,1,
            "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET);
        assertEquals(BootstrapWakeLease.SELECTION_HANDOFF_MS,remaining);
        lease.update(backgroundAt,false,false); // Old deadline is suspended, no CPU lock.
        assertFalse(lock.held);
        assertFalse(lease.expired());
        assertFalse(BootstrapWakeLease.shouldPause(true,lease.expired()));
        long reconciledAt=backgroundAt+MINUTE;
        assertTrue(gate.apply(reconciledAt,false,1,false,true,true,false,2,2,
            "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET,lease));
        assertEquals(BootstrapWakeLease.SLICE_MS,lease.update(reconciledAt,true,true));
        assertFalse(lease.observeProgress(reconciledAt,"mainnet","b.wallet",2,
            WALLET,HASH,200,0)); // Authenticated wrong-wallet identity cannot set a baseline.
        assertFalse(lease.observeProgress(reconciledAt,"mainnet","b.wallet",2,
            OTHER_WALLET,HASH,200,0));
        assertTrue(lease.observeProgress(reconciledAt+5*MINUTE,"mainnet","b.wallet",2,
            OTHER_WALLET,HASH,201,1));
        assertTrue(lease.update(reconciledAt+16*MINUTE,true,true)>0);
    }

    @Test public void pendingSelectionCannotRenewHandoffAndExpiresWithoutReconciliation() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        BootstrapWakeLease.SelectionGate gate=new BootstrapWakeLease.SelectionGate();
        lease.update(0,true,true);
        assertFalse(lease.observeProgress(0,"mainnet","a.wallet",1,WALLET,HASH,100,80));
        long start=BootstrapWakeLease.STALL_MS+1;
        assertEquals(BootstrapWakeLease.SELECTION_HANDOFF_MS,
            gate.pendingHandoff(start,1,true,false,true,true,false,2,1,
                "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET));
        lease.update(start,false,false);
        assertFalse(lock.held);
        assertEquals(MINUTE,gate.pendingHandoff(start+MINUTE,1,true,false,true,true,false,2,1,
            "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET));
        // Even a newer committed selection event cannot push the bound forward.
        assertEquals(MINUTE,gate.pendingHandoff(start+MINUTE,2,true,false,true,true,false,3,1,
            "mainnet","mainnet","c.wallet","c.wallet",WALLET));
        assertEquals(-1,gate.pendingHandoff(start+2*MINUTE,2,true,false,true,true,false,3,1,
            "mainnet","mainnet","c.wallet","c.wallet",WALLET));
        assertFalse(gate.apply(start+2*MINUTE,false,2,false,true,true,false,3,3,
            "mainnet","mainnet","c.wallet","c.wallet",WALLET,lease));
        assertEquals(0,lease.update(start+2*MINUTE,true,true));
        assertTrue(BootstrapWakeLease.shouldPause(true,lease.expired()));
        assertFalse(lock.held);
    }

    @Test public void pendingSelectionRejectsWrongScopeStopAndNetworkLoss() {
        FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
        BootstrapWakeLease.SelectionGate gate=new BootstrapWakeLease.SelectionGate();
        lease.update(0,true,true);
        long start=BootstrapWakeLease.STALL_MS-1000;
        assertEquals(0,gate.pendingHandoff(start,1,true,false,true,true,false,2,1,
            "mainnet","mainnet","b.wallet","c.wallet",OTHER_WALLET));
        assertEquals(0,gate.pendingHandoff(start,1,true,false,true,true,true,2,1,
            "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET)); // Uncommitted marker
        assertEquals(0,gate.pendingHandoff(start,1,true,false,true,true,false,2,1,
            "mainnet","mainnet","b.wallet","b.wallet","bad")); // Invalid identity
        assertEquals(BootstrapWakeLease.SELECTION_HANDOFF_MS,
            gate.pendingHandoff(start,1,true,false,true,true,false,2,1,
                "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET));
        lease.update(start,false,false);
        assertEquals(0,gate.pendingHandoff(start+1000,1,false,false,true,true,false,2,1,
            "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET)); // Network denied
        assertEquals(BootstrapWakeLease.SELECTION_HANDOFF_MS-2*MINUTE/3,
            gate.pendingHandoff(start+2*MINUTE/3,1,true,false,true,true,false,2,1,
                "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET)); // Reconnect does not renew.
        assertEquals(0,gate.pendingHandoff(start+2*MINUTE/3+1000,1,true,true,false,false,false,2,1,
            "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET)); // Manual Stop
        assertFalse(gate.apply(start+2*MINUTE/3+1000,false,1,true,false,false,false,2,2,
            "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET,lease));
        assertFalse(lock.held);
    }

    @Test public void selectionBeforeFirstBackgroundLeaseRetainsExpectedIdentity() {
        BootstrapWakeLease.SelectionGate gate=new BootstrapWakeLease.SelectionGate();
        assertTrue(gate.apply(0,true,1,false,true,true,false,2,2,
            "mainnet","mainnet","b.wallet","b.wallet",OTHER_WALLET,null));
        BootstrapWakeLease lease=new BootstrapWakeLease(new FakeLock());
        gate.bindNewLease(lease);
        lease.update(100,true,true);
        assertFalse(lease.observeProgress(100,"mainnet","b.wallet",2,WALLET,HASH,100,0));
        assertFalse(lease.observeProgress(100,"mainnet","b.wallet",2,OTHER_WALLET,HASH,100,0));
        assertTrue(lease.observeProgress(200,"mainnet","b.wallet",2,OTHER_WALLET,HASH,101,1));
        gate.bindNewLease(lease); // Repeated callbacks never reset the baseline.
        assertFalse(lease.observeProgress(300,"mainnet","b.wallet",2,OTHER_WALLET,HASH,101,1));
    }

    @Test public void barrierPolicyLossBeforeQueuedCheckpointNeverReacquires() throws Exception {
        for(boolean manualStop:new boolean[]{true,false}) {
            FakeLock lock=new FakeLock();BootstrapWakeLease lease=new BootstrapWakeLease(lock);
            lease.update(0,true,true);
            AtomicBoolean requested=new AtomicBoolean(true),network=new AtomicBoolean(true);
            AtomicReference<Throwable> workerFailure=new AtomicReference<>();
            CountDownLatch observed=new CountDownLatch(1),resume=new CountDownLatch(1);
            Thread queued=new Thread(() -> {
                try {
                    boolean stale=BootstrapWakeLease.backgroundEligible(false,true,true,
                        requested.get(),true,network.get(),true,false,false);
                    assertTrue(stale);
                    observed.countDown();
                    assertTrue(resume.await(2,TimeUnit.SECONDS));
                    boolean fresh=BootstrapWakeLease.backgroundEligible(false,true,true,
                        requested.get(),true,network.get(),true,false,false);
                    lease.update(1,fresh,fresh); // Main-looper checkpoint reads policy again.
                } catch(Throwable failure) { workerFailure.set(failure); observed.countDown(); }
            });
            queued.start();
            assertTrue(observed.await(2,TimeUnit.SECONDS));
            if(manualStop)requested.set(false);else network.set(false);
            lease.update(1,false,false); // Stop or network callback releases first.
            resume.countDown();queued.join(2000);
            assertFalse(queued.isAlive());
            assertNull(workerFailure.get());
            assertEquals(1,lock.acquires);
            assertFalse(lock.held);
        }
    }
}
