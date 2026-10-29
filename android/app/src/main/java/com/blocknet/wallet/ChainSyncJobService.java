package com.blocknet.wallet;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs an unloaded Core within an OS-owned, interruptible chain-sync job. */
public final class ChainSyncJobService extends JobService {
    private static final long MAX_RUN = TimeUnit.MINUTES.toMillis(8);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger epoch = new AtomicInteger();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager connectivity;
    private boolean observing;
    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { checkNetwork(); }
        @Override public void onLost(Network n) { checkNetwork(); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) { checkNetwork(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity != null) try {
            connectivity.registerDefaultNetworkCallback(network, main);
            observing = true;
        } catch (RuntimeException ignored) { }
    }

    private void checkNetwork() {
        if (!SyncNetworkPolicy.read(this).allowed) {
            NativeRuntime runtime = NativeRuntime.get(this);
            if (runtime.ownsSyncRequest(this)) runtime.signalNetworkPause();
        }
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        final int run = epoch.incrementAndGet();
        worker.execute(() -> run(parameters, run));
        return true;
    }

    private boolean stillOwned(NativeRuntime runtime, int run) {
        return epoch.get() == run && runtime.ownsSyncRequest(this)
            && MobileCorePolicy.requested(this) && MobileCorePolicy.enabled(this)
            && !MobileCorePolicy.permitted(this) && SyncNetworkPolicy.read(this).allowed;
    }

    private void run(JobParameters parameters, int run) {
        NativeRuntime runtime = NativeRuntime.get(this);
        long generation = -1;
        boolean owned = false;
        boolean statusObserved = false;
        boolean peerTipConfirmed = false;
        try {
            if (epoch.get() != run || !MobileCorePolicy.requested(this)
                || !MobileCorePolicy.enabled(this) || MobileCorePolicy.permitted(this)
                || !SyncNetworkPolicy.read(this).allowed) return;
            if (!runtime.tryRequestSync(this)) return;
            owned = true;
            BackgroundChainSync.jobLease(true);
            if (!stillOwned(runtime, run)) return;
            // Include storage preparation and Core's ring-index rebuild in the job budget.
            long deadline = SystemClock.elapsedRealtime() + MAX_RUN;
            // After process death a child may have survived without a Java owner.
            // Stop it before a background job starts; it might have a loaded wallet.
            if (runtime.alive()) runtime.stop();
            if (!stillOwned(runtime, run)) return;
            runtime.start();
            generation = runtime.generation();
            WalletApi api = new WalletApi(runtime);
            while (stillOwned(runtime, run) && runtime.alive()
                && SystemClock.elapsedRealtime() < deadline) {
                if (runtime.cookie().isFile()) {
                    try {
                        org.json.JSONObject progress = api.request("GET", "/api/wallet/bootstrap", null, null, 5000);
                        statusObserved = true;
                        // The job must never retain a loaded wallet or its keys.
                        if (progress.optBoolean("wallet_loaded")) break;
                        if (BackgroundChainSync.ready(progress)) { peerTipConfirmed = true; break; }
                    } catch (Exception startingOrInterrupted) { /* Retry until the time budget or policy changes. */ }
                }
                Thread.sleep(15000);
            }
        } catch (Exception interruptedOrUnavailable) {
            // The next periodic run starts from Core's durable chain database.
        } finally {
            if (owned && epoch.get() == run && MobileCorePolicy.requested(this)
                && MobileCorePolicy.enabled(this) && !MobileCorePolicy.permitted(this)
                && SyncNetworkPolicy.read(this).allowed)
                BackgroundChainSync.recordOutcome(this, statusObserved, peerTipConfirmed);
            Thread.interrupted(); // onDestroy may interrupt the poll; still drain Core's graceful stop.
            if (owned && runtime.ownsSyncRequest(this)) {
                runtime.signalNetworkPause();
                try { if (generation >= 0) runtime.stopGeneration(generation);
                    else if (runtime.alive()) runtime.stop(); }
                catch (Exception ignored) { }
                runtime.clearSyncRequest(this);
            }
            BackgroundChainSync.jobLease(false);
            if (epoch.get() == run) main.post(() -> {
                if (epoch.get() == run) jobFinished(parameters, false);
            });
        }
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        epoch.incrementAndGet();
        BackgroundChainSync.jobLease(false);
        NativeRuntime runtime = NativeRuntime.get(this);
        if (runtime.ownsSyncRequest(this)) runtime.signalNetworkPause();
        return MobileCorePolicy.requested(this) && MobileCorePolicy.enabled(this);
    }

    @Override public void onDestroy() {
        epoch.incrementAndGet();
        BackgroundChainSync.jobLease(false);
        NativeRuntime runtime = NativeRuntime.get(this);
        if (runtime.ownsSyncRequest(this)) runtime.signalNetworkPause();
        if (observing) try { connectivity.unregisterNetworkCallback(network); } catch (RuntimeException ignored) { }
        worker.shutdownNow();
        super.onDestroy();
    }
}
