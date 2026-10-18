package com.blocknet.wallet;

import static org.junit.Assert.*;
import org.junit.Test;

public class CoreDiagnosticsTest {
    @Test public void stoppedDoesNotPresentSavedChainAsCurrent() {
        String report = CoreDiagnostics.stopped("0.22.0-mobile1", "mainnet");
        assertTrue(report.contains("Node stopped. Chain status is unavailable."));
        assertFalse(report.contains("Chain height:"));
    }

    @Test public void runningUsesOnlySelectedPublicStatus() {
        String report = CoreDiagnostics.running("0.22.0-mobile1", "mainnet", 72, 3, true, 80);
        assertTrue(report.contains("Chain height: 72"));
        assertTrue(report.contains("Sync target: 80"));
        assertFalse(report.contains("wallet"));
        assertFalse(report.contains("peer_id"));
        assertFalse(report.contains("Bearer"));
    }

    @Test public void failedStatusDoesNotClaimCurrentHeight() {
        String report = CoreDiagnostics.unavailable("0.22.0-mobile1", "testnet");
        assertTrue(report.contains("current chain status is unavailable"));
        assertFalse(report.contains("Chain height:"));
    }

    @Test public void pausedDuringHandoverDoesNotClaimCurrentHeight() {
        String report = CoreDiagnostics.paused("0.22.0-mobile1", "mainnet");
        assertTrue(report.contains("Chain status may be stale"));
        assertFalse(report.contains("Chain height:"));
    }

    @Test public void handoverOrRestartInvalidatesAStatusResponse() {
        assertTrue(CoreDiagnostics.currentSession(true, true, true, true, 5, 5, "mainnet", "mainnet"));
        assertFalse(CoreDiagnostics.currentSession(true, false, true, true, 5, 5, "mainnet", "mainnet"));
        assertFalse(CoreDiagnostics.currentSession(true, true, false, true, 5, 5, "mainnet", "mainnet"));
        assertFalse(CoreDiagnostics.currentSession(false, true, true, true, 5, 5, "mainnet", "mainnet"));
        assertFalse(CoreDiagnostics.currentSession(true, true, true, true, 5, 6, "mainnet", "mainnet"));
        assertFalse(CoreDiagnostics.currentSession(true, true, true, true, 5, 5, "mainnet", "testnet"));
    }
}
