package com.blocknet.wallet;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

/** Best-effort chain catch-up. A scheduled run never opens an encrypted wallet. */
final class BackgroundChainSync {
    static final int JOB_ID = 0x424e4301;
    static final long INTERVAL = TimeUnit.MINUTES.toMillis(15);
    private static final String LAST_OUTCOME = "background-chain-last-outcome";
    private static volatile boolean serviceLease;
    private static volatile boolean jobLease;
    private BackgroundChainSync() { }

    static void serviceLease(boolean active) { serviceLease = active; }
    static void jobLease(boolean active) { jobLease = active; }
    static boolean serviceLeased() { return serviceLease; }
    static boolean leased() { return serviceLease || jobLease; }

    static boolean ready(JSONObject progress) {
        return progress != null && ready(progress.optInt("version"),
            progress.optBoolean("peer_tip_confirmed"),progress.optBoolean("syncing",true));
    }
    static boolean ready(int version, boolean peerTipConfirmed, boolean syncing) {
        return version == 1 && peerTipConfirmed && !syncing;
    }

    static boolean shouldSchedule(boolean mobileClient, boolean requested, boolean foregroundOnly) {
        return mobileClient && requested && foregroundOnly;
    }

    static void reconcileSchedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!shouldSchedule(BuildConfig.CORE_MOBILE_CLIENT, MobileCorePolicy.requested(context),
                MobileCorePolicy.enabled(context))) {
            scheduler.cancel(JOB_ID);
            return;
        }
        JobInfo pending = scheduler.getPendingJob(JOB_ID);
        if (!needsSchedule(pending != null, pending != null && pending.isPersisted())) return;
        // Android runs this inexactly. NETWORK_TYPE_ANY is only a wakeup hint;
        // SyncNetworkPolicy checks the actual active route during the job.
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, ChainSyncJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .setPeriodic(INTERVAL)
            .setPersisted(true)
            .build();
        scheduler.schedule(job);
    }

    static boolean needsSchedule(boolean pending, boolean persisted) {
        return !pending || !persisted;
    }

    static String outcome(boolean statusObserved, boolean peerTipConfirmed) {
        if (!statusObserved) return "no_status";
        return peerTipConfirmed ? "caught_up" : "incomplete";
    }

    static void recordOutcome(Context context, boolean statusObserved, boolean peerTipConfirmed) {
        context.getSharedPreferences("wallet", Context.MODE_PRIVATE).edit()
            .putString(LAST_OUTCOME, outcome(statusObserved, peerTipConfirmed)).apply();
    }

    static boolean lastCheckHadNoStatus(Context context) {
        return "no_status".equals(context.getSharedPreferences("wallet", Context.MODE_PRIVATE)
            .getString(LAST_OUTCOME, ""));
    }
}
