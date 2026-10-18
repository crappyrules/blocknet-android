package com.blocknet.wallet;

import static org.junit.Assert.*;
import org.junit.Test;

public class CoreServiceReconcileGateTest {
    @Test public void unchangedTimerAndNetworkSignalsDoNotCancelSlowStartup() {
        CoreServiceReconcileGate gate = new CoreServiceReconcileGate();
        assertTrue(gate.enqueue(CoreServiceReconcileGate.Trigger.START,true));
        // Simulates the 5-second tick and duplicate validated Wi-Fi callbacks
        // while Core is still loading its chain and checking checkpoints.
        assertFalse(gate.enqueue(CoreServiceReconcileGate.Trigger.HEALTH,true));
        assertFalse(gate.enqueue(CoreServiceReconcileGate.Trigger.POLICY,true));
        assertFalse(gate.enqueue(CoreServiceReconcileGate.Trigger.RECHECK,true));
        assertEquals(1,gate.pending());
        gate.finished();
        // After startup, a health check may recover a genuinely dead child.
        assertTrue(gate.enqueue(CoreServiceReconcileGate.Trigger.HEALTH,true));
        gate.finished();
        assertEquals(0,gate.pending());
    }

    @Test public void disallowedTransitionInvalidatesInFlightStartupOnce() {
        CoreServiceReconcileGate gate = new CoreServiceReconcileGate();
        assertTrue(gate.enqueue(CoreServiceReconcileGate.Trigger.START,true));
        assertTrue(gate.enqueue(CoreServiceReconcileGate.Trigger.POLICY,false));
        assertFalse(gate.enqueue(CoreServiceReconcileGate.Trigger.POLICY,false));
        assertFalse(gate.enqueue(CoreServiceReconcileGate.Trigger.HEALTH,false));
        assertEquals(2,gate.pending());
        // Wi-Fi or foreground return is a new real transition, not a stale tick.
        assertTrue(gate.enqueue(CoreServiceReconcileGate.Trigger.POLICY,true));
        assertEquals(3,gate.pending());
        gate.finished();gate.finished();gate.finished();
        assertEquals(0,gate.pending());
    }

    @Test public void explicitStartCanSupersedePendingStartAndStopIsExternal() {
        CoreServiceReconcileGate gate = new CoreServiceReconcileGate();
        assertTrue(gate.enqueue(CoreServiceReconcileGate.Trigger.START,true));
        assertTrue(gate.enqueue(CoreServiceReconcileGate.Trigger.START,true));
        assertEquals(2,gate.pending());
        gate.finished();gate.finished();
    }
}
