package com.blocknet.wallet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Short CPU leases while an authenticated first sync makes real chain or scan progress. */
final class BootstrapWakeLease {
    interface Lock {
        void acquire(long timeoutMs);
        void release();
        boolean isHeld();
    }

    static final long STALL_MS = 15 * 60 * 1000L;
    static final long SLICE_MS = 2 * 60 * 1000L;
    static final long SELECTION_HANDOFF_MS = 2 * 60 * 1000L;
    private static final long RENEW_EARLY_MS = 2000;
    private final Lock lock;
    private long stallDeadline = -1;
    private long leaseDeadline = -1;
    private long highestChain = -1;
    private long highestScan = -1;
    private String walletId;
    private String scopeNetwork,scopeWallet;
    private long scopeGeneration = -1;
    private boolean expired;

    BootstrapWakeLease(Lock lock) { this.lock = lock; }

    static boolean backgroundEligible(boolean serviceStopping, boolean foregroundOnly,
                            boolean background, boolean requested,
                            boolean runningRequest, boolean networkAllowed, boolean pending,
                            boolean backgroundLockFailed, boolean proofFailed) {
        return !serviceStopping && foregroundOnly && background
            && requested && runningRequest && networkAllowed && pending
            && !backgroundLockFailed && !proofFailed;
    }
    static boolean shouldPause(boolean backgroundEligible, boolean expiredOrUnavailable) {
        return backgroundEligible && expiredOrUnavailable;
    }
    static String identityForAddress(String address) throws Exception {
        if(address==null||address.isEmpty())throw new IllegalArgumentException("Missing wallet identity");
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(
            ("blocknet-wallet-activity-v1\0"+address).getBytes(StandardCharsets.UTF_8));
        char[] hex=new char[64],digits="0123456789abcdef".toCharArray();
        for(int i=0;i<hash.length;i++) {
            hex[2*i]=digits[(hash[i]>>>4)&15];
            hex[2*i+1]=digits[hash[i]&15];
        }
        return new String(hex);
    }
    static boolean completedSelectionMatches(boolean stopping, boolean requested,
                                             boolean runningRequest, boolean changePending,
                                             long selectedGeneration, long ownedGeneration,
                                             String selectedNetwork, String currentNetwork,
                                             String selectedWallet, String activeWallet) {
        return !stopping && requested && runningRequest && !changePending &&
            selectedGeneration == ownedGeneration && selectedNetwork != null &&
            selectedNetwork.equals(currentNetwork) && selectedWallet != null &&
            selectedWallet.equals(activeWallet);
    }
    /** A queued Core reconciliation may publish its generation after wallet selection. */
    static final class SelectionGate {
        private long acknowledgedSerial;
        private long handoffDeadline = -1;
        private String appliedNetwork,appliedWallet,appliedWalletId;
        private long appliedGeneration = -1;

        boolean apply(long nowMs, boolean foreground, long serial,
                      boolean stopping, boolean requested, boolean runningRequest,
                      boolean changePending, long selectedGeneration, long ownedGeneration,
                      String selectedNetwork, String currentNetwork,
                      String selectedWallet, String activeWallet, String selectedWalletId,
                      BootstrapWakeLease lease) {
            if (serial <= acknowledgedSerial ||
                (handoffDeadline >= 0 && nowMs >= handoffDeadline && !foreground) ||
                selectedWalletId == null || !selectedWalletId.matches("[0-9a-f]{64}") ||
                !completedSelectionMatches(stopping,requested,runningRequest,changePending,
                    selectedGeneration,ownedGeneration,selectedNetwork,currentNetwork,
                    selectedWallet,activeWallet)) return false;
            if (lease != null) lease.selectedWallet(selectedNetwork,selectedWallet,
                selectedGeneration,selectedWalletId);
            appliedNetwork=selectedNetwork;
            appliedWallet=selectedWallet;
            appliedWalletId=selectedWalletId;
            appliedGeneration=selectedGeneration;
            acknowledgedSerial = serial;
            handoffDeadline = -1;
            return true;
        }

        void bindNewLease(BootstrapWakeLease lease) {
            if (lease != null && lease.scopeNetwork == null && appliedNetwork != null)
                lease.selectedWallet(appliedNetwork,appliedWallet,appliedGeneration,appliedWalletId);
        }

        /** Positive: lock-free handoff; -1: bound expired; zero: no valid handoff. */
        long pendingHandoff(long nowMs, long serial, boolean backgroundEligible,
                            boolean stopping, boolean requested, boolean runningRequest,
                            boolean changePending, long selectedGeneration, long ownedGeneration,
                            String selectedNetwork, String currentNetwork,
                            String selectedWallet, String activeWallet, String selectedWalletId) {
            if (serial <= acknowledgedSerial || !backgroundEligible || stopping || !requested ||
                !runningRequest || changePending ||
                selectedNetwork == null || !selectedNetwork.equals(currentNetwork) ||
                selectedWallet == null || !selectedWallet.equals(activeWallet) ||
                selectedWalletId == null || !selectedWalletId.matches("[0-9a-f]{64}")) return 0;
            if (handoffDeadline >= 0 && nowMs >= handoffDeadline) return -1;
            if (selectedGeneration <= ownedGeneration) return 0;
            if (handoffDeadline < 0) handoffDeadline = nowMs + SELECTION_HANDOFF_MS;
            return handoffDeadline-nowMs;
        }

        void abortHandoff() { if (handoffDeadline >= 0) handoffDeadline = 0; }

        long acknowledgedSerial() { return acknowledgedSerial; }
    }

    /** Returns time until the next bounded lease renewal or stall cutoff. */
    synchronized long update(long nowMs, boolean backgroundEligible, boolean cpuWakeNeeded) {
        if (!backgroundEligible || expired) {
            release();
            return 0;
        }
        if (stallDeadline < 0) stallDeadline = nowMs + STALL_MS;
        long remaining = stallDeadline - nowMs;
        if (remaining <= 0) {
            expire();
            return 0;
        }
        if (!cpuWakeNeeded) {
            release();
            return remaining;
        }
        if (!lock.isHeld() || nowMs >= leaseDeadline - RENEW_EARLY_MS) {
            release();
            long slice = Math.min(SLICE_MS, remaining);
            lock.acquire(slice);
            leaseDeadline = nowMs + slice;
        }
        return Math.min(leaseDeadline, stallDeadline) - nowMs;
    }

    /** The first sample is only a baseline. Only a higher validated height extends the cutoff. */
    synchronized boolean observeProgress(long nowMs, String network, String wallet,
                                         long generation, String id, String chainHash,
                                         long chainHeight, long scanHeight) {
        if (expired || stallDeadline < 0 || network == null || wallet == null ||
            generation < 0 || id == null || !id.matches("[0-9a-f]{64}") ||
            chainHash == null || !chainHash.matches("[0-9a-f]{64}") ||
            chainHeight < 0 || scanHeight < 0 || scanHeight > chainHeight ||
            nowMs >= stallDeadline) return false;
        if (scopeNetwork == null) {
            scopeNetwork = network;
            scopeWallet = wallet;
            scopeGeneration = generation;
        } else if (!scopeNetwork.equals(network) || !scopeWallet.equals(wallet) ||
                   generation < scopeGeneration) return false;
        else scopeGeneration = generation; // A Core restart does not erase the high-water marks.
        if (walletId == null) {
            walletId = id;
        }
        if (!walletId.equals(id)) return false;
        if (highestChain < 0) {
            highestChain = chainHeight;
            highestScan = scanHeight;
            return false;
        }
        boolean advanced = chainHeight > highestChain || scanHeight > highestScan;
        highestChain = Math.max(highestChain, chainHeight);
        highestScan = Math.max(highestScan, scanHeight);
        if (advanced) stallDeadline = nowMs + STALL_MS;
        return advanced;
    }

    /** Called only after a successful foreground wallet selection is fully committed. */
    synchronized void selectedWallet(String network, String wallet, long generation,String expectedId) {
        reset();
        scopeNetwork = network;
        scopeWallet = wallet;
        scopeGeneration = generation;
        walletId = expectedId;
    }

    synchronized void expire() { expired = true; release(); }
    synchronized boolean expired() { return expired; }

    /** Only a new foreground request after the prior service pause can reset progress. */
    synchronized void reset() {
        release();
        stallDeadline = -1;
        leaseDeadline = -1;
        highestChain = -1;
        highestScan = -1;
        walletId = null;
        scopeNetwork = null;
        scopeWallet = null;
        scopeGeneration = -1;
        expired = false;
    }
    synchronized void close() { release(); }

    private void release() { if (lock.isHeld()) lock.release(); }
}
