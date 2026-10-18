package com.blocknet.wallet;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.util.concurrent.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

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
    private volatile long awaitingApiGeneration = -1;
    private volatile long commandEpoch;
    private volatile boolean backgroundLockFailed;
    private volatile boolean bootstrapProofFailed;
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
            worker.execute(() -> secureBackgroundWallet(NativeRuntime.get(c)));
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
        alerts.scheduleWithFixedDelay(() -> { if (!MobileCorePolicy.enabled(this) || MobileCorePolicy.permitted(this)) notifier.poll(); },4,15,TimeUnit.SECONDS);
        AnnouncementScanner scanner=new AnnouncementScanner(this);
        announcements.scheduleWithFixedDelay(() -> { if (!MobileCorePolicy.enabled(this) || MobileCorePolicy.permitted(this)) scanner.poll(); },20,60,TimeUnit.SECONDS);
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
                getSystemService(NotificationManager.class).notify(42, advisory("Stop sync was not saved; node remains active. Open the wallet and try again."));
                connectionChanged(CoreServiceReconcileGate.Trigger.RECHECK);
                return START_NOT_STICKY;
            }
            stopping = true; reconcileEpoch.incrementAndGet(); NativeRuntime.get(this).setSyncRequested(false); NativeRuntime.get(this).signalNetworkPause();
            worker.execute(() -> {
                boolean stopped;
                try { NativeRuntime.get(this).stop(); stopped = !NativeRuntime.get(this).alive(); }
                catch (Exception failed) { stopped = !NativeRuntime.get(this).alive(); }
                final boolean complete = stopped;
                handler.post(() -> { if (stopping && commandEpoch == command) {
                    if (complete) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId); }
                    else getSystemService(NotificationManager.class).notify(41, notification("Node stop failed. Open wallet to check node status."));
                } });
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
        boolean allowed = SyncNetworkPolicy.read(this).allowed && MobileCorePolicy.syncPermitted(this);
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
        if(!MobileCorePolicy.background()){backgroundLockFailed=false;bootstrapProofFailed=false;}
        if(backgroundLockFailed&&MobileCorePolicy.background()){
            publish("Open wallet to resume initial sync",epoch);return;
        }
        SyncNetworkPolicy.State state = SyncNetworkPolicy.read(this);
        if (!state.allowed || !MobileCorePolicy.syncPermitted(this)) {
            try { if (r.alive()) r.stop(); }
            catch (Exception e) { publish("Sync stopped. Open wallet to check node status.",epoch); return; }
            publish(state.allowed ? "Node stopped while wallet is in background" : state.reason,epoch);
            if (MobileCorePolicy.paused(this)) retirePausedService(epoch,command);
            return;
        }
        try {
            if (!r.alive()) {
                r.start(); ownedGeneration = r.generation(); awaitingApiGeneration=ownedGeneration;
                if (stopping || epoch != reconcileEpoch.get() || !SyncNetworkPolicy.read(this).allowed || !MobileCorePolicy.syncPermitted(this)) {
                    r.signalNetworkPause(); r.stop(); publish(MobileCorePolicy.paused(this) ? "Node stopped while wallet is in background" : SyncNetworkPolicy.read(this).reason,epoch); return;
                }
            }
            if(!secureBackgroundWallet(r))return;
            ownedGeneration = r.generation();
            // A bounded request; checkpoint HTTPS runs on its own worker and cannot delay a pause.
            if (r.network().equals("mainnet")) try { r.verifyCheckpoints(); } catch (Exception ignored) { }
            if (stopping || commandEpoch != command || epoch != reconcileEpoch.get() || !SyncNetworkPolicy.read(this).allowed || !MobileCorePolicy.syncPermitted(this)) {
                r.signalNetworkPause(); r.stop(); return;
            }
            downloads.execute(r::refreshCheckpointsInBackground);
            publish(BootstrapSyncState.pending(this)?"Initial wallet sync running · "+state.connectionLabel:
                "Local node running · " + state.connectionLabel,epoch);
        } catch (Exception e) {
            if (stopping || commandEpoch != command || epoch != reconcileEpoch.get()) return;
            if (!SyncNetworkPolicy.read(this).allowed || !MobileCorePolicy.syncPermitted(this)) {
                publish(MobileCorePolicy.paused(this) ? "Node stopped while wallet is in background" : SyncNetworkPolicy.read(this).reason,epoch);
                if (MobileCorePolicy.paused(this)) retirePausedService(epoch,command);
                return;
            }
            r.setSyncRequested(false);
            handler.post(() -> {
                if (stopping || commandEpoch != command) return;
                getSystemService(NotificationManager.class).notify(42, advisory("Node could not start. Open the wallet for details."));
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
    private boolean secureBackgroundWallet(NativeRuntime runtime) {
        if(!MobileCorePolicy.background()||!runtime.alive()||!runtime.syncRequested()||
            !MobileCorePolicy.syncPermitted(this))return true;
        if(backgroundLockFailed)return false;
        // A newly spawned Core does not load encrypted wallet keys before its
        // API starts. Its cookie may be absent during a long chain DB load.
        if(awaitingApiGeneration==runtime.generation()&&!runtime.cookie().isFile())return true;
        try { new WalletRepository(this).lock(); return true; }
        catch(Exception failure) {
            backgroundLockFailed=true;
            runtime.signalNetworkPause();
            try { runtime.stop(); } catch(Exception ignored) { }
            publish("Wallet lock failed. Open wallet to resume sync.",reconcileEpoch.get());
            getSystemService(NotificationManager.class).notify(42,
                advisory("Wallet lock failed. Open wallet to resume sync."));
            return false;
        }
    }
    private void evaluateBootstrap(NativeRuntime runtime) {
        if(!MobileCorePolicy.enabled(this)||!MobileCorePolicy.background()||bootstrapProofFailed)return;
        final long generation=runtime.generation(),command=commandEpoch;
        final String network=runtime.network();
        final String wallet=BootstrapSyncState.activeWallet(
            getSharedPreferences("wallet",MODE_PRIVATE),network);
        JSONObject progress;
        try { progress=new WalletApi(runtime).request("GET","/api/wallet/bootstrap",null,null,5000); }
        catch(Exception unavailable) { publish("Initial wallet sync running · waiting for Core",reconcileEpoch.get());return; }
        if(stopping||commandEpoch!=command||!runtime.syncRequested()||
            !MobileCorePolicy.enabled(this)||
            !MobileCorePolicy.requested(this)||!MobileCorePolicy.background()||
            !SyncNetworkPolicy.read(this).allowed||generation!=runtime.generation()||
            !network.equals(runtime.network())||!wallet.equals(BootstrapSyncState.activeWallet(
                getSharedPreferences("wallet",MODE_PRIVATE),network)))return;
        boolean noWallet=!progress.optBoolean("wallet_loaded",false);
        boolean chainReady=progress.optBoolean("peer_tip_confirmed",false)&&
            !progress.optBoolean("syncing",true);
        if(!BootstrapSyncState.ready(progress)&&!(noWallet&&chainReady)) {
            publish(noWallet?"Chain syncing · open wallet to finish its scan":
                "Initial wallet scan and chain sync continuing",reconcileEpoch.get());
            return;
        }
        if(new WalletRepository(this).hasPendingSend())return;
        if(stopping||commandEpoch!=command||!MobileCorePolicy.enabled(this)||
            !MobileCorePolicy.background()||!MobileCorePolicy.requested(this)||
            !SyncNetworkPolicy.read(this).allowed)return;
        try { runtime.stop(); }
        catch(Exception stopFailure) {
            publish("Initial sync pause failed. Open wallet to check node status.",reconcileEpoch.get());return;
        }
        if(!noWallet) {
            try {
                if(!BootstrapSyncState.shutdownMatches(progress,runtime.shutdownProof())) {
                    bootstrapProofFailed=true;
                    publish("Wallet sync could not be verified. Open wallet to inspect.",reconcileEpoch.get());
                    connectionChanged(CoreServiceReconcileGate.Trigger.HEALTH);
                    return;
                }
                if(stopping||commandEpoch!=command||!MobileCorePolicy.enabled(this)||
                    !MobileCorePolicy.background()||!MobileCorePolicy.requested(this)||
                    !SyncNetworkPolicy.read(this).allowed||generation!=runtime.generation())return;
                if(!BootstrapSyncState.completeAfterStopped(this,network,wallet,progress.getString("wallet_id")))return;
            } catch(Exception saveFailure) {
                bootstrapProofFailed=true;
                publish("Initial sync state could not be saved. Open wallet to retry.",reconcileEpoch.get());return;
            }
            connectionChanged(CoreServiceReconcileGate.Trigger.HEALTH);
        } else {
            getSystemService(NotificationManager.class).notify(42,
                advisory("Open wallet to resume initial sync"));
            handler.post(()->{if(!stopping&&commandEpoch==command){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}});
        }
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
                if(!MobileCorePolicy.background()){backgroundLockFailed=false;bootstrapProofFailed=false;}
                if(!secureBackgroundWallet(r))return;
                if(MobileCorePolicy.enabled(CoreService.this)&&MobileCorePolicy.background()&&BootstrapSyncState.pending(CoreService.this)
                    &&SyncNetworkPolicy.read(CoreService.this).allowed&&r.alive())evaluateBootstrap(r);
                boolean allowed = SyncNetworkPolicy.read(CoreService.this).allowed && MobileCorePolicy.syncPermitted(CoreService.this);
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
    private Notification advisory(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "node").setSmallIcon(R.drawable.ic_wallet)
            .setContentTitle(getString(R.string.app_name)).setContentText(LocalizedText.get(this,message))
            .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
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
