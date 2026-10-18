package com.blocknet.wallet;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.util.concurrent.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** A requested sync session waits for an allowed connection and owns only its node. */
public final class CoreService extends Service {
    public static final String START = "com.blocknet.wallet.START_NODE";
    public static final String STOP = "com.blocknet.wallet.STOP_NODE";
    public static final String RECHECK = "com.blocknet.wallet.RECHECK_NETWORK";
    public static final String AUTO_RESUME = "auto_resume";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService alerts = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService announcements = Executors.newSingleThreadScheduledExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean stopping;
    private volatile long ownedGeneration = -1;
    private volatile long commandEpoch;
    private final AtomicLong reconcileEpoch = new AtomicLong();
    private final CoreServiceReconcileGate reconcileGate = new CoreServiceReconcileGate();
    private ConnectivityManager connectivity;
    private boolean observing;
    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { connectionChanged(CoreServiceReconcileGate.Trigger.POLICY); }
        @Override public void onLost(Network n) { connectionChanged(CoreServiceReconcileGate.Trigger.POLICY); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { connectionChanged(CoreServiceReconcileGate.Trigger.POLICY); }
    };
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            MobileCorePolicy.screenOff(c);
            worker.execute(() -> { try { new WalletRepository(c).lock(); } catch (Exception ignored) { } });
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel("node", LocalizedText.get(this,"Local blockchain node"), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(LocalizedText.get(this,"Sync connection status and controls for your local Blocknet node"));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        TransactionNotifier notifier=new TransactionNotifier(this);
        alerts.scheduleWithFixedDelay(() -> { if (!MobileCorePolicy.paused(this)) notifier.poll(); },4,15,TimeUnit.SECONDS);
        AnnouncementScanner scanner=new AnnouncementScanner(this);
        announcements.scheduleWithFixedDelay(() -> { if (!MobileCorePolicy.paused(this)) scanner.poll(); },20,60,TimeUnit.SECONDS);
        connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity != null) try { connectivity.registerDefaultNetworkCallback(network, handler); observing = true; } catch (Exception ignored) { }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && RECHECK.equals(intent.getAction())) {
            startForeground(41, notification("Checking sync connection…"));
            if (!stopping && NativeRuntime.get(this).syncRequested()) {
                connectionChanged(CoreServiceReconcileGate.Trigger.RECHECK);
                handler.removeCallbacks(monitor); handler.postDelayed(monitor, 5000);
            } else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId); }
            return START_NOT_STICKY;
        }
        final long command = ++commandEpoch;
        startForeground(41, notification("Checking sync connection…"));
        if (intent != null && intent.getBooleanExtra(AUTO_RESUME,false) && !MobileCorePolicy.requested(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId); return START_NOT_STICKY;
        }
        if (intent != null && STOP.equals(intent.getAction())) {
            try { MobileCorePolicy.setRequested(this,false); }
            catch (IOException error) {
                getSystemService(NotificationManager.class).notify(42, notification("Stop sync was not saved; node remains active. Open the wallet and try again."));
                connectionChanged(CoreServiceReconcileGate.Trigger.RECHECK);
                return START_NOT_STICKY;
            }
            stopping = true; reconcileEpoch.incrementAndGet(); NativeRuntime.get(this).setSyncRequested(false); NativeRuntime.get(this).cancelCheckpointRefresh();
            worker.execute(() -> {
                try { NativeRuntime.get(this).stop(); } catch (Exception ignored) { }
                handler.post(() -> { if (stopping && commandEpoch == command) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId); } });
            });
        } else {
            try { MobileCorePolicy.setRequested(this,true); }
            catch (IOException error) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId); return START_NOT_STICKY; }
            stopping = false; NativeRuntime.get(this).requestSync(this);
            connectionChanged(CoreServiceReconcileGate.Trigger.START);
            handler.removeCallbacks(monitor); handler.postDelayed(monitor, 5000);
        }
        return START_NOT_STICKY;
    }
    private void connectionChanged(CoreServiceReconcileGate.Trigger trigger) {
        if (stopping) return;
        NativeRuntime r = NativeRuntime.get(this);
        boolean allowed = SyncNetworkPolicy.read(this).allowed && MobileCorePolicy.permitted(this);
        // Signal outside the process lock, even if another operation is waiting on Core.
        if (!allowed) r.signalNetworkPause();
        if (!reconcileGate.enqueue(trigger,allowed)) return;
        final long epoch = reconcileEpoch.incrementAndGet();
        try { worker.execute(() -> { try { reconcile(epoch); } finally { reconcileGate.finished(); } }); }
        catch (RejectedExecutionException destroyed) { reconcileGate.finished(); }
    }
    private void reconcile(long epoch) {
        if (stopping || epoch != reconcileEpoch.get()) return;
        final long command = commandEpoch;
        NativeRuntime r = NativeRuntime.get(this);
        if (!r.syncRequested()) return;
        SyncNetworkPolicy.State state = SyncNetworkPolicy.read(this);
        if (!state.allowed || !MobileCorePolicy.permitted(this)) {
            try { if (r.alive()) r.stop(); }
            catch (Exception e) { publish("Sync stopped. Open wallet to check node status.",epoch); return; }
            publish(state.allowed ? "Node stopped while wallet is in background" : state.reason,epoch);
            if (MobileCorePolicy.paused(this)) retirePausedService(epoch,command);
            return;
        }
        try {
            if (!r.alive()) {
                r.start(); ownedGeneration = r.generation();
                if (stopping || epoch != reconcileEpoch.get() || !SyncNetworkPolicy.read(this).allowed || !MobileCorePolicy.permitted(this)) {
                    r.signalNetworkPause(); r.stop(); publish(MobileCorePolicy.paused(this) ? "Node stopped while wallet is in background" : SyncNetworkPolicy.read(this).reason,epoch); return;
                }
            }
            ownedGeneration = r.generation();
            // A bounded request; checkpoint HTTPS runs on its own worker and cannot delay a pause.
            if (r.network().equals("mainnet")) try { r.verifyCheckpoints(); } catch (Exception ignored) { }
            if (stopping || commandEpoch != command || epoch != reconcileEpoch.get() || !SyncNetworkPolicy.read(this).allowed || !MobileCorePolicy.permitted(this)) {
                r.signalNetworkPause(); r.stop(); return;
            }
            downloads.execute(r::refreshCheckpointsInBackground);
            publish("Local node running · " + LocalizedText.get(this,state.connectionLabel),epoch);
        } catch (Exception e) {
            if (stopping || commandEpoch != command || epoch != reconcileEpoch.get()) return;
            if (!SyncNetworkPolicy.read(this).allowed || !MobileCorePolicy.permitted(this)) {
                publish(MobileCorePolicy.paused(this) ? "Node stopped while wallet is in background" : SyncNetworkPolicy.read(this).reason,epoch);
                if (MobileCorePolicy.paused(this)) retirePausedService(epoch,command);
                return;
            }
            r.setSyncRequested(false);
            handler.post(() -> {
                if (stopping || commandEpoch != command) return;
                getSystemService(NotificationManager.class).notify(42, notification("Node could not start. Open the wallet for details."));
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            });
        }
    }
    private void publish(String message, long epoch) {
        handler.post(() -> { if (!stopping && epoch == reconcileEpoch.get()) getSystemService(NotificationManager.class).notify(41, notification(message)); });
    }
    private void retirePausedService(long epoch, long command) {
        handler.post(() -> {
            if (stopping || epoch != reconcileEpoch.get() || command != commandEpoch || !MobileCorePolicy.paused(this)) return;
            if (NativeRuntime.get(this).alive()) return;
            handler.removeCallbacks(monitor);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (stopping) return;
            // A health check waits behind an in-flight start. It must not
            // invalidate a permitted start simply because a timer fired.
            try { worker.execute(() -> {
                if (stopping) return;
                NativeRuntime r = NativeRuntime.get(CoreService.this);
                if (!r.syncRequested()) return;
                boolean allowed = SyncNetworkPolicy.read(CoreService.this).allowed && MobileCorePolicy.permitted(CoreService.this);
                if (MobileCorePolicy.paused(CoreService.this) || (allowed != r.alive()))
                    connectionChanged(CoreServiceReconcileGate.Trigger.HEALTH);
            }); } catch (RejectedExecutionException destroyed) { return; }
            handler.postDelayed(this, 15000);
        }
    };
    private Notification notification(String message) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CoreService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "node").setSmallIcon(R.drawable.ic_wallet)
            .setContentTitle(getString(R.string.app_name)).setContentText(LocalizedText.get(this,message)).setContentIntent(pi)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null, LocalizedText.get(this,"Stop sync"), stop).build()).build();
    }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        stopping = true; handler.removeCallbacksAndMessages(null);
        reconcileEpoch.incrementAndGet();
        try { unregisterReceiver(screen); } catch (Exception ignored) { }
        if (observing) try { connectivity.unregisterNetworkCallback(network); } catch (Exception ignored) { }
        NativeRuntime r = NativeRuntime.get(this);
        r.cancelCheckpointRefresh();
        r.clearSyncRequest(this);
        worker.execute(() -> { long owned = ownedGeneration; try { if (owned >= 0) r.stopServiceGeneration(this,owned); } catch (Exception ignored) { } });
        worker.shutdown(); downloads.shutdownNow(); alerts.shutdownNow(); announcements.shutdownNow(); super.onDestroy();
    }
}
