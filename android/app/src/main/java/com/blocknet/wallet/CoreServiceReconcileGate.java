package com.blocknet.wallet;

/** Coalesces equivalent service signals without cancelling a slow permitted start. */
final class CoreServiceReconcileGate {
    enum Trigger { START, POLICY, RECHECK, HEALTH }

    private Boolean lastAllowed;
    private int pending;

    synchronized boolean enqueue(Trigger trigger, boolean allowed) {
        boolean changed = lastAllowed == null || lastAllowed != allowed;
        lastAllowed = allowed;
        if (trigger == Trigger.POLICY && !changed) return false;
        if ((trigger == Trigger.RECHECK || trigger == Trigger.HEALTH) && !changed && pending > 0) return false;
        pending++;
        return true;
    }

    synchronized void finished() {
        if (pending < 1) throw new IllegalStateException("No reconcile is pending");
        pending--;
    }

    synchronized int pending() { return pending; }
}
