package com.blocknet.wallet;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.util.concurrent.*;

/** A requested sync session waits for an allowed connection and owns only its node. */
public final class CoreService extends Service {
    public static final String START = "com.blocknet.wallet.START_NODE";
    public static final String STOP = "com.blocknet.wallet.STOP_NODE";
    public static final String RECHECK = "com.blocknet.wallet.RECHECK_NETWORK";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService alerts = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService announcements = Executors.newSingleThreadScheduledExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean stopping;
    private volatile long ownedGeneration = -1;
    private volatile long commandEpoch;
    private ConnectivityManager connectivity;
    private boolean observing;
    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { connectionChanged(); }
        @Override public void onLost(Network n) { connectionChanged(); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { connectionChanged(); }
    };
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            worker.execute(() -> { try { new WalletRepository(c).lock(); } catch (Exception ignored) { } });
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel("node", "Local blockchain node", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Sync connection status and controls for your local Blocknet node");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        TransactionNotifier notifier=new TransactionNotifier(this);
        alerts.scheduleWithFixedDelay(notifier::poll,4,15,TimeUnit.SECONDS);
        AnnouncementScanner scanner=new AnnouncementScanner(this);
        announcements.scheduleWithFixedDelay(scanner::poll,20,60,TimeUnit.SECONDS);
        connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity != null) try { connectivity.registerDefaultNetworkCallback(network, handler); observing = true; } catch (Exception ignored) { }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final long command = ++commandEpoch;
        startForeground(41, notification("Checking sync connection…"));
        if (intent != null && STOP.equals(intent.getAction())) {
            stopping = true; NativeRuntime.get(this).setSyncRequested(false); NativeRuntime.get(this).cancelCheckpointRefresh();
            worker.execute(() -> {
                try { NativeRuntime.get(this).stop(); } catch (Exception ignored) { }
                handler.post(() -> { if (stopping && commandEpoch == command) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId); } });
            });
        } else {
            stopping = false; NativeRuntime.get(this).requestSync(this);
            connectionChanged();
            handler.removeCallbacks(monitor); handler.postDelayed(monitor, 5000);
        }
        return START_NOT_STICKY;
    }
    private void connectionChanged() {
        if (stopping) return;
        NativeRuntime r = NativeRuntime.get(this);
        // Signal outside the process lock, even if another operation is waiting on Core.
        if (!SyncNetworkPolicy.read(this).allowed) r.signalNetworkPause();
        worker.execute(this::reconcile);
    }
    private void reconcile() {
        if (stopping) return;
        final long command = commandEpoch;
        NativeRuntime r = NativeRuntime.get(this);
        if (!r.syncRequested()) return;
        SyncNetworkPolicy.State state = SyncNetworkPolicy.read(this);
        if (!state.allowed) {
            try { if (r.alive()) r.stop(); }
            catch (Exception e) { publish("Sync stopped. Open wallet to check node status."); return; }
            publish(state.reason); return;
        }
        try {
            if (!r.alive()) {
                r.start(); ownedGeneration = r.generation();
                if (!SyncNetworkPolicy.read(this).allowed) { r.signalNetworkPause(); r.stop(); publish(SyncNetworkPolicy.read(this).reason); return; }
            }
            ownedGeneration = r.generation();
            // A bounded request; checkpoint HTTPS runs on its own worker and cannot delay a pause.
            if (r.network().equals("mainnet")) try { r.verifyCheckpoints(); } catch (Exception ignored) { }
            if (!stopping && commandEpoch == command) downloads.execute(r::refreshCheckpointsInBackground);
            publish("Local node running · " + state.connectionLabel);
        } catch (Exception e) {
            if (stopping || commandEpoch != command) return;
            if (!SyncNetworkPolicy.read(this).allowed) { publish(SyncNetworkPolicy.read(this).reason); return; }
            r.setSyncRequested(false);
            handler.post(() -> {
                if (stopping || commandEpoch != command) return;
                getSystemService(NotificationManager.class).notify(42, notification("Node could not start. Open the wallet for details."));
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            });
        }
    }
    private void publish(String message) {
        handler.post(() -> { if (!stopping) getSystemService(NotificationManager.class).notify(41, notification(message)); });
    }
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (stopping) return;
            connectionChanged(); handler.postDelayed(this, 15000);
        }
    };
    private Notification notification(String message) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CoreService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "node").setSmallIcon(R.drawable.ic_wallet)
            .setContentTitle("Blocknet Wallet").setContentText(message).setContentIntent(pi)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null, "Stop sync", stop).build()).build();
    }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        stopping = true; handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screen); } catch (Exception ignored) { }
        if (observing) try { connectivity.unregisterNetworkCallback(network); } catch (Exception ignored) { }
        NativeRuntime r = NativeRuntime.get(this);
        r.cancelCheckpointRefresh();
        r.clearSyncRequest(this);
        worker.execute(() -> { long owned = ownedGeneration; try { if (owned >= 0) r.stopServiceGeneration(this,owned); } catch (Exception ignored) { } });
        worker.shutdown(); downloads.shutdownNow(); alerts.shutdownNow(); announcements.shutdownNow(); super.onDestroy();
    }
}
