package com.blocknet.wallet;

import android.content.*;
import android.system.Os;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Wallet operations are synchronous and must be invoked from a worker thread. */
public final class WalletRepository {
    private final Context context;
    private final NativeRuntime runtime;
    private final WalletApi api;
    private final OfflineSnapshotStore offline;
    private final OfflineWalletReader offlineReader;
    private final CacheWriteGate cacheWriteGate = new CacheWriteGate();
    private JSONObject preview;
    private String previewWallet, previewNetwork, previewMemo;
    private long previewAt;
    private volatile VerifiedReceive receiveShortname;
    private static final class VerifiedReceive {
        final String scope,handle; final long at;
        VerifiedReceive(String scope,String handle) { this.scope=scope;this.handle=handle;at=android.os.SystemClock.elapsedRealtime(); }
    }
    public WalletRepository(Context context) {
        this.context = context.getApplicationContext();
        runtime = NativeRuntime.get(context); api = new WalletApi(runtime);
        offline = new OfflineSnapshotStore(this.context); offlineReader=new OfflineWalletReader(this.context);
    }
    public static final class Snapshot {
        public boolean running, loaded, locked, viewOnly, syncing, scanning;
        public long spendable, pending, height, walletHeight;
        public int peers, checkpointEntries;
        public long checkpointHeight, syncTarget;
        public boolean checkpointFastSync;
        public boolean syncRequested, syncAllowed, allowMobileData;
        public boolean cached, cacheAvailable, localWalletAvailable, chainHeightKnown=true;
        public long savedAt;
        public String connectionLabel = "", syncPolicyMessage = "";
        public String checkpointWarning = "", syncPercent = "";
        public String address = "", network = "mainnet", nodeMessage = "Node stopped";
        public JSONArray history = new JSONArray(), sends = new JSONArray();
        public JSONObject status = new JSONObject();
    }
    public synchronized Snapshot refresh() throws Exception {
        Snapshot s = new Snapshot();
        SyncNetworkPolicy.State connection = SyncNetworkPolicy.read(context);
        s.connectionLabel = connection.connectionLabel; s.syncAllowed = connection.allowed;
        s.syncPolicyMessage = connection.reason; s.allowMobileData = SyncNetworkPolicy.allowMobileData(context);
        s.syncRequested = runtime.syncRequested();
        s.network = network(); s.running = runtime.alive(); s.nodeMessage = runtime.message();
        long refreshGeneration=runtime.generation();String refreshWallet=activeWallet();
        String scope = cacheScope(s.network,refreshWallet);
        s.localWalletAvailable=hasLocalWallet(s.network,refreshWallet);
        s.cacheAvailable = hasOfflineView(s.network,refreshWallet);
        if (!sameLiveSession(connection.allowed,s.syncRequested,MobileCorePolicy.permitted(context),s.running,
            refreshGeneration,runtime.generation(),s.network,network(),refreshWallet,activeWallet()))
            return localView();
        try { s.status = api.get("/api/status"); }
        catch (Exception e) {
            if (!currentLiveSession(refreshGeneration,s.network,refreshWallet)) return localView();
            s.nodeMessage = "Starting node: " + e.getMessage(); applyOfflineView(s,scope); return s;
        }
        s.nodeMessage = "Local node running";
        s.height = s.status.optLong("chain_height"); s.peers = s.status.optInt("peers"); s.syncing = s.status.optBoolean("syncing");
        s.syncTarget = s.status.optLong("sync_target"); s.syncPercent = s.status.optString("sync_percent");
        s.checkpointWarning = runtime.checkpointWarning();
        if (network().equals("mainnet")) {
            try {
                JSONObject checkpoints = runtime.verifyCheckpoints();
                s.checkpointEntries = checkpoints.optInt("loaded");
                s.checkpointHeight = checkpoints.optLong("max_height");
                s.checkpointFastSync = s.checkpointEntries > 0 && s.height < s.checkpointHeight;
            } catch (Exception e) { s.checkpointWarning = "Checkpoint use is not verified: " + e.getMessage(); }
        }
        if (hasIncompleteWalletChange()) {
            s.nodeMessage = "A wallet change was interrupted. Open your selected wallet again before continuing.";
            return currentLiveSession(refreshGeneration,s.network,refreshWallet) ? s : localView();
        }
        try {
            JSONObject balance = api.get("/api/wallet/balance");
            s.loaded = true; s.scanning = balance.optBoolean("scanning");
            s.spendable = balance.optLong("spendable"); s.pending = balance.optLong("pending") + balance.optLong("pending_unconfirmed");
            s.walletHeight = balance.optLong("synced_height");
            JSONObject address = api.get("/api/wallet/address");
            s.address = address.getString("address"); s.viewOnly = address.optBoolean("view_only");
            s.history = historyPage(0).optJSONArray("outputs");
            s.sends = sendsPage(0).optJSONArray("sends");
            if (s.history == null) s.history = new JSONArray(); if (s.sends == null) s.sends = new JSONArray();
            if(currentLiveSession(refreshGeneration,s.network,refreshWallet)&&!hasIncompleteWalletChange())
                TransactionNotifier.rememberWallet(context,s.network,refreshWallet,s.address);
            if (!s.address.isEmpty() && completeBalanceForCache(balance) && !hasIncompleteWalletChange()
                && currentLiveSession(refreshGeneration,s.network,refreshWallet)
                && OfflineSnapshotStore.authorized(scope)) {
                try {
                    JSONObject savedView=cacheJson(scope,s);
                    boolean wrote=cacheWriteGate.saveIfNeeded(scope,refreshGeneration,walletFile(refreshWallet),
                        savedView.toString(),s.cacheAvailable,android.os.SystemClock.elapsedRealtime(),
                        WalletFileBinding::digest,(binding,savedAt)->{
                            if(!currentLiveSession(refreshGeneration,s.network,refreshWallet)
                                ||!OfflineSnapshotStore.authorized(scope))
                                throw new IOException("Wallet session changed while saving the offline view");
                            offline.save(scope,savedView.put("walletDigest",binding).put("savedAt",savedAt));
                        });
                    if(wrote)s.cacheAvailable=offline.has(scope);
                }
                catch (Exception ignored) { s.cacheAvailable=offline.has(scope); }
            }
        } catch (WalletApi.ApiException e) {
            if (!currentLiveSession(refreshGeneration,s.network,refreshWallet)) return localView();
            if (e.status == 403 || e.status == 409) { s.loaded = true; s.locked = true; }
            else if (e.status != 503) throw e;
        } catch (Exception e) {
            if (currentLiveSession(refreshGeneration,s.network,refreshWallet)) throw e;
            return localView();
        }
        // A Wi-Fi handover may finish while the local API requests above are in flight.
        // Never return their old values as a live balance after sync becomes disallowed.
        if (!currentLiveSession(refreshGeneration,s.network,refreshWallet)) return localView();
        if (!s.loaded || s.locked) applyOfflineView(s,scope);
        return s;
    }
    private Snapshot localView() {
        Snapshot local = new Snapshot();
        SyncNetworkPolicy.State connection = SyncNetworkPolicy.read(context);
        local.connectionLabel=connection.connectionLabel; local.syncAllowed=connection.allowed;
        local.syncPolicyMessage=connection.reason; local.allowMobileData=SyncNetworkPolicy.allowMobileData(context);
        local.syncRequested=runtime.syncRequested(); local.running=runtime.alive(); local.nodeMessage=runtime.message();
        local.network=network(); String wallet=activeWallet(); String scope=cacheScope(local.network,wallet);
        local.localWalletAvailable=hasLocalWallet(local.network,wallet);
        local.cacheAvailable=hasOfflineView(local.network,wallet);
        applyOfflineView(local,scope);
        return local;
    }
    private boolean currentLiveSession(long generation,String network,String wallet) {
        return sameLiveSession(SyncNetworkPolicy.read(context).allowed,runtime.syncRequested(),
            MobileCorePolicy.permitted(context),runtime.alive(),generation,runtime.generation(),
            network,network(),wallet,activeWallet());
    }
    static boolean sameLiveSession(boolean syncAllowed,boolean syncRequested,boolean foregroundPermitted,
                                   boolean nodeRunning,long beforeGeneration,long nowGeneration,
                                   String beforeNetwork,String nowNetwork,String beforeWallet,String nowWallet) {
        return syncAllowed && syncRequested && foregroundPermitted && nodeRunning
            && beforeGeneration==nowGeneration && beforeNetwork.equals(nowNetwork)
            && beforeWallet.equals(nowWallet);
    }
    static boolean completeBalanceForCache(JSONObject balance) {
        return completeBalanceForCache(balance.optBoolean("scanning"),balance.has("spendable"),
            balance.has("pending"),balance.has("pending_unconfirmed"),balance.has("synced_height"));
    }
    static boolean completeBalanceForCache(boolean scanning,boolean spendable,boolean pending,
                                           boolean pendingUnconfirmed,boolean syncedHeight) {
        return !scanning && spendable && pending && pendingUnconfirmed && syncedHeight;
    }
    private static String cacheScope(String network,String wallet) { return network + "/" + wallet; }
    private void authorizeCache(String wallet,String password) {
        cacheWriteGate.reset();
        try {
            boolean oldCacheInvalidated=offline.authorizeOnline(cacheScope(network(),wallet),password);
            if (oldCacheInvalidated) try { new BiometricVault(context).disable(network(),wallet); }
            catch (Exception ignored) { /* The old cached view is already invalidated. */ }
        } catch (Exception ignored) { OfflineSnapshotStore.lock(); }
    }
    public boolean hasOfflineView(String network,String wallet) {
        return hasLocalWallet(network,wallet)&&offline.has(cacheScope(network,wallet));
    }
    public boolean hasLocalWallet(String network,String wallet) {
        return network.equals(this.network())&&new File(runtime.walletsDir(),wallet).isFile()&&!hasIncompleteWalletChange();
    }
    private File walletFile(String wallet) { return new File(runtime.walletsDir(),wallet); }
    private boolean cacheMatchesWallet(String wallet,JSONObject saved) throws Exception {
        return WalletFileBinding.matches(saved.optString("walletDigest"),WalletFileBinding.digest(walletFile(wallet)));
    }
    private void unlockSavedOrFile(String wallet,String password) throws Exception {
        if (runtime.alive() && (!runtime.syncRequested() || !SyncNetworkPolicy.read(context).allowed
            || !MobileCorePolicy.permitted(context))) { runtime.signalNetworkPause(); runtime.stop(); }
        String scope=cacheScope(network(),wallet);
        if(hasOfflineView(network(),wallet)) {
            try {
                JSONObject saved=offline.unlockOffline(scope,password);
                if(cacheMatchesWallet(wallet,saved))return;
            } catch(Exception invalidOrChanged) {
                // The current encrypted wallet file is the authority for this password.
            }
            OfflineSnapshotStore.lock();
        }
        openLocalSavedView(wallet,password);
    }
    private void openLocalSavedView(String wallet,String password) throws Exception {
        if(runtime.alive()&&!SyncNetworkPolicy.read(context).allowed){runtime.signalNetworkPause();runtime.stop();}
        String before=WalletFileBinding.digest(walletFile(wallet));
        JSONObject saved=offlineReader.read(wallet,password);
        String after=WalletFileBinding.digest(walletFile(wallet));
        if(!before.equals(after))throw new IOException("Wallet file changed while opening it. Try again.");
        String scope=cacheScope(network(),wallet);
        boolean invalidated=offline.authorizeOnline(scope,password);
        if(invalidated)try{new BiometricVault(context).disable(network(),wallet);}catch(Exception ignored){}
        try{saved.put("scope",scope).put("walletDigest",after);offline.save(scope,saved);}
        catch(Exception failure){OfflineSnapshotStore.lock();throw failure;}
    }
    public boolean offlineSessionAuthorized(){return OfflineSnapshotStore.authorized(cacheScope(network(),activeWallet()));}
    private static JSONObject cacheJson(String scope,Snapshot s) throws Exception {
        return new JSONObject().put("scope",scope)
            .put("spendable",s.spendable).put("pending",s.pending).put("height",s.height)
            .put("walletHeight",s.walletHeight).put("chainHeightKnown",true)
            .put("viewOnly",s.viewOnly).put("address",s.address)
            .put("history",s.history).put("sends",s.sends);
    }
    /** Skips redundant full-file hashes and encrypted writes, with a bounded recheck. */
    static final class CacheWriteGate {
        static final long RECHECK_MS=5*60*1000L;
        interface Digest { String read(File wallet) throws Exception; }
        interface Writer { void save(String binding,long savedAt) throws Exception; }
        private String scope;
        private byte[] contentFingerprint;
        private long generation=-1,size=-1,modified=-1,writtenAt=-1;
        void reset(){scope=null;if(contentFingerprint!=null)Arrays.fill(contentFingerprint,(byte)0);
            contentFingerprint=null;generation=-1;size=-1;modified=-1;writtenAt=-1;}
        boolean saveIfNeeded(String nextScope,long nextGeneration,File wallet,String nextContent,
                             boolean cacheExists,long now,Digest digest,Writer writer) throws Exception {
            long nextSize=wallet.length(),nextModified=wallet.lastModified();
            byte[] plain=nextContent.getBytes(StandardCharsets.UTF_8);
            byte[] nextFingerprint;
            try { nextFingerprint=MessageDigest.getInstance("SHA-256").digest(plain); }
            finally { Arrays.fill(plain,(byte)0); }
            if(cacheExists&&nextScope.equals(scope)&&nextGeneration==generation
                &&nextSize==size&&nextModified==modified&&Arrays.equals(nextFingerprint,contentFingerprint)
                &&writtenAt>=0&&now>=writtenAt&&now-writtenAt<RECHECK_MS){
                Arrays.fill(nextFingerprint,(byte)0);return false;
            }
            try {
                String binding=digest.read(wallet);
                if(wallet.length()!=nextSize||wallet.lastModified()!=nextModified)
                    throw new IOException("Wallet file changed while saving the offline view");
                writer.save(binding,System.currentTimeMillis());
            } catch(Exception failure) { Arrays.fill(nextFingerprint,(byte)0);throw failure; }
            scope=nextScope;generation=nextGeneration;size=nextSize;modified=nextModified;
            if(contentFingerprint!=null)Arrays.fill(contentFingerprint,(byte)0);
            contentFingerprint=nextFingerprint;writtenAt=now;
            return true;
        }
    }
    private void applyOfflineView(Snapshot s,String scope) {
        if (!s.cacheAvailable) return;
        if (!OfflineSnapshotStore.authorized(scope)) { s.loaded=true; s.locked=true; return; }
        try {
            JSONObject saved=offline.read(scope);
            if (saved==null) { s.loaded=true; s.locked=true; return; }
            if(!cacheMatchesWallet(activeWallet(),saved)) {
                OfflineSnapshotStore.lock(); s.cacheAvailable=false;
                s.loaded=true; s.locked=true;
                s.nodeMessage="Wallet file changed. Unlock with its current password.";
                return;
            }
            s.loaded=true; s.locked=false; s.cached=true; s.savedAt=saved.getLong("savedAt");
            s.spendable=saved.getLong("spendable"); s.pending=saved.getLong("pending");
            s.height=saved.getLong("height"); s.walletHeight=saved.getLong("walletHeight");
            s.chainHeightKnown=saved.optBoolean("chainHeightKnown",true);
            s.viewOnly=saved.getBoolean("viewOnly"); s.address=saved.getString("address");
            s.history=saved.getJSONArray("history"); s.sends=saved.getJSONArray("sends");
        } catch (Exception invalid) {
            OfflineSnapshotStore.lock();
            s.loaded=true; s.locked=true;
            s.nodeMessage="Saved view could not be opened. Unlock the wallet again to refresh it.";
        }
    }
    public void startNode() { context.startForegroundService(new Intent(context, CoreService.class).setAction(CoreService.START)); }
    public synchronized void setAllowMobileData(boolean allow) throws Exception {
        SyncNetworkPolicy.setAllowMobileData(context,allow);
        if (!SyncNetworkPolicy.read(context).allowed) runtime.signalNetworkPause();
        if (runtime.syncRequested() || runtime.alive()) context.startForegroundService(new Intent(context,CoreService.class).setAction(CoreService.RECHECK));
    }
    public synchronized void stopNode() throws Exception {
        MobileCorePolicy.setRequested(context,false);
        runtime.setSyncRequested(false);
        runtime.cancelCheckpointRefresh();
        context.stopService(new Intent(context, CoreService.class)); runtime.stop(); preview = null;
    }
    public synchronized JSONObject refreshCheckpoints() throws Exception { return runtime.refreshCheckpoints(); }
    public String network() { return runtime.network(); }
    public synchronized void setNetwork(String network) throws Exception {
        if (!network.equals("mainnet") && !network.equals("testnet")) throw new IllegalArgumentException("Choose mainnet or testnet");
        if (hasPendingSend()) throw new IOException("Resolve the pending transaction before changing networks");
        if (network.equals(network())) return;
        boolean restart = runtime.alive(); stopNode(); OfflineSnapshotStore.lock();
        if (!runtime.prefs.edit().putString("network", network).commit()) throw new IOException("Could not save network");
        if (restart) startNode();
    }
    public String activeWallet() { return runtime.prefs.getString("active." + network(), "main.wallet.dat"); }
    private String receiveScope() { return network()+"."+activeWallet(); }
    public String savedReceiveShortname() {
        try { String value=runtime.prefs.getString("receive-shortname."+receiveScope(),"");return value.isEmpty()?"":"$"+ShortnameResolver.normalize(value); }
        catch(Exception invalid) { return ""; }
    }
    /** Only a recently verified mapping may replace the authoritative address in a QR. */
    public String preferredReceiveShortname() {
        VerifiedReceive current=receiveShortname;
        return current!=null&&current.scope.equals(receiveScope())&&!hasIncompleteWalletChange()
            &&android.os.SystemClock.elapsedRealtime()-current.at<5*60*1000?current.handle:"";
    }
    public synchronized String verifyAndSaveReceiveShortname(String name) throws Exception {
        requireKnownWallet();
        String expectedNetwork=network(),file=activeWallet(),scope=receiveScope();long generation=runtime.generation();
        String address=api.get("/api/wallet/address").getString("address");
        ShortnameResolver.Result resolved=new ShortnameResolver(context).resolve(name);
        if(!resolved.verified||!address.equals(resolved.address))throw new IOException("This shortname does not resolve to the current wallet address");
        if(!expectedNetwork.equals(network())||!file.equals(activeWallet())||generation!=runtime.generation()
            ||!address.equals(api.get("/api/wallet/address").getString("address")))throw new IOException("The wallet changed while verifying the shortname. Try again.");
        String canonical="$"+resolved.handle;
        if(!runtime.prefs.edit().putString("receive-shortname."+scope,canonical).commit())throw new IOException("Could not save the preferred shortname");
        receiveShortname=new VerifiedReceive(scope,canonical);return canonical;
    }
    public void clearReceiveShortname() throws IOException {
        receiveShortname=null;
        if(!runtime.prefs.edit().remove("receive-shortname."+receiveScope()).commit())throw new IOException("Could not clear the preferred shortname");
    }
    public List<String> wallets() {
        String[] names = runtime.walletsDir().list((dir,name) -> name.endsWith(".wallet.dat") && new File(dir,name).isFile());
        List<String> result = new ArrayList<>(); if (names != null) result.addAll(Arrays.asList(names)); Collections.sort(result); return result;
    }
    static String filename(String name) {
        String n = name.trim();
        if (n.endsWith(".wallet.dat")) n = n.substring(0, n.length()-11);
        if (!n.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")) throw new IllegalArgumentException("Wallet name: 1–48 letters, numbers, dashes or underscores");
        return n + ".wallet.dat";
    }
    private void validateNew(String name, String password) throws Exception {
        if (password.length() < 8) throw new IllegalArgumentException("Use a wallet password with at least 8 characters");
        if (new File(runtime.walletsDir(), filename(name)).exists()) throw new IOException("A wallet with this name already exists");
        if (hasPendingSend()) throw new IOException("Resolve the pending transaction before changing wallets");
    }
    private void makeRoom() throws Exception {
        boolean loaded = true;
        try { api.get("/api/wallet/balance"); }
        catch (WalletApi.ApiException e) {
            if (e.status == 503) loaded = false;
            else if (e.status != 403 && e.status != 409) throw e;
        }
        if (loaded) {
            // v0.22 unload leaves a catch-up scanner running with the old wallet object.
            // Restart the process before opening another wallet so it cannot overwrite files.
            runtime.restart();
            startNode(); // refresh the service's ownership generation
            waitForApi();
        }
        preview = null;
    }
    private void waitForApi() throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 60000;
        Exception last = null;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            if (!runtime.alive()) throw new IOException(runtime.message());
            try { api.get("/api/status"); return; }
            catch (Exception e) { last = e; Thread.sleep(250); }
        }
        throw new IOException("Node is still loading its chain. Wait for it to become ready and try again.", last);
    }
    private void activate(String name) throws Exception {
        String file = filename(name);
        if (!runtime.prefs.edit().putString("active." + network(), file).commit()) throw new IOException("Could not save active wallet");
        api.patch("/api/settings", new JSONObject().put("wallet_file", new File(runtime.walletsDir(), file).getAbsolutePath()));
        preview = null;
    }
    private String walletChangeKey() { return "wallet-change."+network(); }
    private boolean hasIncompleteWalletChange() { return runtime.prefs.contains(walletChangeKey()); }
    private void requireKnownWallet() throws IOException {
        if(hasIncompleteWalletChange())throw new IOException("A wallet change was interrupted. Open your selected wallet again before sending or making a backup.");
    }
    private void openWallet(String name,String route,JSONObject body) throws Exception {
        String file=filename(name);
        if(!route.equals("/api/wallet/load"))BootstrapSyncState.begin(context,network(),file);
        // Persist before the side effect: response loss or process death must not expose a
        // loaded wallet under the previous filename, particularly during backup export.
        if(!runtime.prefs.edit().putString(walletChangeKey(),file).commit())throw new IOException("Could not safely record wallet selection");
        try {
            makeRoom();
            JSONObject opened=api.post(route,body);
            if(!file.equals(opened.getString("filename")))throw new IOException("The core opened an unexpected wallet file");
            activate(file);
            if(!runtime.prefs.edit().remove(walletChangeKey()).commit())throw new IOException("Could not confirm wallet selection");
            authorizeCache(file,body.getString("password"));
            if(!MobileCorePolicy.permitted(context))lock();
        }catch(Exception failure){
            // The durable marker stays until a successful, explicit reopen reconciles it.
            try{stopNode();}catch(Exception stopFailure){failure.addSuppressed(stopFailure);}
            throw failure;
        }
    }
    public synchronized void create(String name, String password) throws Exception {
        validateNew(name,password);
        openWallet(name,"/api/wallet/create",new JSONObject().put("filename",filename(name)).put("password",password));
    }
    public synchronized void restore(String name, String password, String mnemonic) throws Exception {
        validateNew(name,password);
        String words = mnemonic.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (words.split(" ").length != 12) throw new IllegalArgumentException("Enter all 12 recovery words");
        openWallet(name,"/api/wallet/import",new JSONObject().put("filename",filename(name)).put("password",password).put("mnemonic",words));
    }
    public synchronized void importKeys(String name, String password, String spendKey, String viewKey, boolean viewOnly) throws Exception {
        validateNew(name,password);
        if (!spendKey.matches("[a-fA-F0-9]{64}") || !viewKey.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("Both keys must contain exactly 64 hexadecimal characters");
        openWallet(name,"/api/wallet/import",new JSONObject().put("filename",filename(name)).put("password",password).put(viewOnly?"spend_pub":"spend_priv",spendKey).put("view_priv",viewKey));
    }
    public synchronized void switchWallet(String name, String password) throws Exception {
        if (hasPendingSend()) throw new IOException("Resolve the pending transaction before switching wallets");
        String file = filename(name);
        if (!new File(runtime.walletsDir(),file).isFile()) throw new IOException("Wallet file does not exist");
        if (!runtime.alive() || !runtime.syncRequested() || !SyncNetworkPolicy.read(context).allowed
            || !MobileCorePolicy.permitted(context)) {
            unlockSavedOrFile(file,password);
            if (!runtime.prefs.edit().putString("active."+network(),file).commit()) { OfflineSnapshotStore.lock(); throw new IOException("Could not save active wallet"); }
            preview=null; return;
        }
        openWallet(file,"/api/wallet/load",new JSONObject().put("filepath",file).put("password",password));
    }
    public synchronized void unload() throws Exception {
        if (hasPendingSend()) throw new IOException("Resolve the pending transaction before unloading");
        makeRoom();
    }
    public synchronized void unlock(String password) throws Exception {
        if (!runtime.alive() || !runtime.syncRequested() || !SyncNetworkPolicy.read(context).allowed
            || !MobileCorePolicy.permitted(context)) {
            unlockSavedOrFile(activeWallet(),password);
            return;
        }
        if(hasIncompleteWalletChange()) { switchWallet(activeWallet(),password);return; }
        try { api.post("/api/wallet/unlock", new JSONObject().put("password",password)); }
        catch (WalletApi.ApiException e) {
            if (e.status != 503) throw e;
            api.post("/api/wallet/load", new JSONObject().put("password",password).put("filepath",activeWallet()));
        }
        if(!MobileCorePolicy.permitted(context)||!runtime.syncRequested()||!SyncNetworkPolicy.read(context).allowed){
            try { lock(); }
            catch(Exception failedLock){runtime.signalNetworkPause();try{runtime.stop();}catch(Exception ignored){}}
            throw new IOException("Wallet went to background while unlocking. Open it and unlock again.");
        }
        authorizeCache(activeWallet(),password);
    }
    public synchronized void unlockBiometric(String expectedNetwork,String wallet,char[] password) throws Exception {
        try {
            if (!network().equals(expectedNetwork)) throw new IOException("The network changed. Unlock the selected wallet again.");
            String file = filename(wallet);
            if (!new File(runtime.walletsDir(),file).isFile()) throw new IOException("Wallet file does not exist");
            if (activeWallet().equals(file)) unlock(new String(password));
            else switchWallet(file,new String(password));
        } finally { Arrays.fill(password,'\0'); }
    }
    public synchronized void lock() throws Exception {
        preview = null; cacheWriteGate.reset(); OfflineSnapshotStore.lock();
        if (runtime.alive()) try { api.post("/api/wallet/lock",null); }
        catch (Exception e) {
            // The service may stop Core between the liveness check and this request.
            // Once its process is gone there is no loaded wallet left to lock.
            // A live Core must still acknowledge the lock before the UI proceeds.
            if (!lockSatisfiedAfterFailure(e,runtime.alive())) throw e;
        }
    }
    static boolean lockSatisfiedAfterFailure(Exception error,boolean coreAlive) {
        return !coreAlive || (error instanceof WalletApi.ApiException
            && lockAlreadySatisfied((WalletApi.ApiException)error));
    }
    static boolean lockAlreadySatisfied(WalletApi.ApiException error) {
        return error.status == 503 && "no wallet loaded".equals(error.getMessage());
    }
    public String seed(String password) throws Exception { requireKnownWallet(); return api.post("/api/wallet/seed",new JSONObject().put("password",password)).getString("mnemonic"); }
    public void rescan() throws Exception { requireKnownWallet(); api.post("/api/wallet/sync",null); }
    public JSONObject sign(String message) throws Exception { requireKnownWallet(); return api.post("/api/wallet/sign",new JSONObject().put("message",message)); }
    public JSONObject verify(String address,String message,String signature) throws Exception { return api.post("/api/verify",new JSONObject().put("address",address).put("message",message).put("signature",signature)); }
    /** Core parses the checksum and both Ristretto public keys before it checks the dummy signature. */
    public void validateRecipientAddress(String address) throws Exception {
        requireSyncConnection();
        JSONObject response=verify(address,"address-book-validation","00".repeat(64));
        if(!response.has("valid"))throw new IOException("Core did not validate the recipient address");
    }
    public JSONObject historyPage(int offset) throws Exception { requireKnownWallet(); return api.get("/api/wallet/history/page?limit=100&order=desc&offset=" + Math.max(0,offset)); }
    public JSONObject sendsPage(int offset) throws Exception { requireKnownWallet(); return api.get("/api/wallet/sends?limit=100&order=desc&offset=" + Math.max(0,offset)); }
    private JSONObject sendBody(String address,String amount,String memo,boolean dry) throws Exception {
        if (address.trim().isEmpty()) throw new IllegalArgumentException("Enter a recipient address");
        if (memo.getBytes(StandardCharsets.UTF_8).length > 124) throw new IllegalArgumentException("Memo must be at most 124 UTF-8 bytes");
        JSONObject recipient = new JSONObject().put("address",address.trim()).put("amount",Amounts.parse(amount));
        if (!memo.isEmpty()) recipient.put("memo_text",memo);
        return new JSONObject().put("recipients",new JSONArray().put(recipient)).put("dry_run",dry);
    }
    public synchronized JSONObject previewSend(String address,String amount,String memo) throws Exception {
        requireSyncConnection();
        requireKnownWallet();
        preview = null;
        if (hasPendingSend()) throw new IOException("A previous send has an unresolved outcome. Check its status before creating another transaction.");
        JSONObject status = api.get("/api/status");
        if (status.optBoolean("syncing")) throw new IOException("Wait for blockchain sync before sending");
        JSONObject b = api.get("/api/wallet/balance");
        if (b.optBoolean("scanning")) throw new IOException("Wait for the wallet scan before sending");
        JSONObject candidate = api.post("/api/wallet/send",sendBody(address,amount,memo,true));
        requireSyncConnection();
        preview = candidate;
        previewWallet = activeWallet(); previewNetwork = network(); previewMemo = memo; previewAt = android.os.SystemClock.elapsedRealtime();
        return new JSONObject(preview.toString());
    }
    public synchronized JSONObject send(String address,String amount,String memo) throws Exception {
        requireSyncConnection();
        requireKnownWallet();
        if (hasPendingSend()) throw new IOException("Check the previous transaction status before sending again");
        if (preview == null || !activeWallet().equals(previewWallet) || !network().equals(previewNetwork) || !memo.equals(previewMemo) || android.os.SystemClock.elapsedRealtime()-previewAt > 120000) throw new IOException("Review this payment again before sending");
        JSONObject recipient = preview.getJSONArray("recipients").getJSONObject(0);
        String approved = recipient.optString("resolved_address",recipient.getString("address"));
        if (!address.trim().equals(approved) || Amounts.parse(amount) != recipient.getLong("amount")) throw new IOException("Payment details changed. Review the payment again.");
        JSONObject currentStatus = api.get("/api/status");
        JSONObject currentBalance = api.get("/api/wallet/balance");
        if (currentStatus.optBoolean("syncing") || currentBalance.optBoolean("scanning")) {
            preview = null;
            throw new IOException("The node or wallet is syncing. Review this payment after sync completes.");
        }
        JSONObject finalEstimate = api.post("/api/wallet/send", sendBody(approved,amount,memo,true));
        if (finalEstimate.getLong("fee") > preview.getLong("fee")) {
            preview = null;
            throw new IOException("The estimated fee increased. Review the payment again before sending.");
        }
        String body = sendBody(approved,amount,memo,false).toString();
        requireSyncConnection();
        String key = UUID.randomUUID().toString();
        JSONObject pending = new JSONObject().put("key",key).put("body",body).put("wallet",activeWallet()).put("network",network()).put("created_at",System.currentTimeMillis());
        if (!runtime.prefs.edit().putString(pendingKey(),pending.toString()).commit()) throw new IOException("Could not safely save transaction request");
        preview = null;
        try { requireSyncConnection(); }
        catch (IOException stopped) { clearPending(); throw stopped; }
        try {
            JSONObject result = api.request("POST","/api/wallet/send",body,key);
            if (!result.has("txid")) throw new IOException("Send returned no transaction ID; check transaction status");
            new TransactionNotifier(context).acceptedOutgoing(network(),activeWallet(),result);
            clearPending(); return result;
        } catch (WalletApi.ApiException e) {
            if (e.status == 400 || e.status == 401 || e.status == 403 || e.status == 429 || e.status == 503) clearPending();
            throw e;
        } catch (IOException e) { throw new IOException("Payment outcome is unknown. Use Check pending transaction; do not create a new payment until reconciled.",e); }
    }
    private String pendingKey() { return "pending-send."+network(); }
    private void requireSyncConnection() throws IOException {
        SyncNetworkPolicy.State state = SyncNetworkPolicy.read(context);
        if (!state.allowed) throw new IOException(state.reason);
        boolean requested=runtime.syncRequested(), foreground=MobileCorePolicy.permitted(context);
        if (!sendSessionAllowed(state.allowed,requested,foreground))
            throw new IOException(!requested ? "Start sync before sending a payment"
                : "Open the wallet to resume sync before sending a payment");
    }
    static boolean sendSessionAllowed(boolean networkAllowed,boolean syncRequested,boolean foregroundPermitted) {
        return networkAllowed && syncRequested && foregroundPermitted;
    }
    public boolean hasPendingSend() { return runtime.prefs.contains(pendingKey()); }
    private void clearPending() throws IOException { if (!runtime.prefs.edit().remove(pendingKey()).commit()) throw new IOException("Could not save transaction status"); }
    public synchronized JSONObject pendingSendStatus() throws Exception {
        String saved = runtime.prefs.getString(pendingKey(),null);
        if (saved == null) return new JSONObject().put("state","none");
        JSONObject pending = new JSONObject(saved);
        JSONObject status = api.get("/api/wallet/send/status?idempotency_key=" + pending.getString("key"));
        String state = status.optString("state");
        if(state.equals("completed")) {
            JSONObject result=status.optJSONObject("result");
            if(result!=null)new TransactionNotifier(context).acceptedOutgoing(pending.getString("network"),pending.getString("wallet"),result);
        }
        if (state.equals("completed") || state.equals("failed")) clearPending();
        if (state.equals("not_found")) status.put("message","No persisted result found. Check outgoing history and balance before resolving this payment manually.");
        status.put("request",new JSONObject(pending.getString("body")));
        return status;
    }
    public synchronized void acknowledgePendingOutcome() throws Exception {
        // Explicit UI action after showing the recipient, amount, and outgoing history.
        if (!hasPendingSend()) return;
        JSONObject state = pendingSendStatus();
        if (state.optString("state").equals("in_progress")) throw new IOException("Transaction is still in progress");
        clearPending();
    }
    public boolean isBackupComplete() { return runtime.prefs.getBoolean("backup."+network()+"."+activeWallet(),false); }
    public void markBackupComplete() { runtime.prefs.edit().putBoolean("backup."+network()+"."+activeWallet(),true).apply(); }
    public synchronized void exportBackup(OutputStream destination) throws Exception {
        requireKnownWallet();
        // Stop the node so background scanner writes cannot race with the copy.
        // Core handles SIGTERM by saving the wallet and closing the database.
        stopNode();
        File file = new File(runtime.walletsDir(),activeWallet());
        if (!file.isFile()) throw new IOException("No wallet file to export");
        try (InputStream in = new FileInputStream(file)) { copyBounded(in,destination,64*1024*1024); destination.flush(); }
    }
    public synchronized void importBackup(InputStream source,String name) throws Exception {
        String filename = filename(name); File target = new File(runtime.walletsDir(),filename);
        if (target.exists()) throw new IOException("A wallet with this name already exists");
        BootstrapSyncState.begin(context,network(),filename);
        File temp = new File(runtime.walletsDir(),filename+".import");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { copyBounded(source,out,64*1024*1024); out.getFD().sync(); }
            WalletBackup.validate(temp);
            Os.chmod(temp.getPath(),0600); Files.move(temp.toPath(),target.toPath());
        } finally { temp.delete(); }
    }
    static void copyBounded(InputStream in,OutputStream out,int limit) throws IOException {
        byte[] buf = new byte[8192]; long bytes=0; int n;
        while ((n=in.read(buf))!=-1) { bytes+=n; if(bytes>limit)throw new IOException("Wallet file exceeds the 64 MB import/export limit"); out.write(buf,0,n); }
    }
    public synchronized String diagnostics() throws Exception {
        String version = BuildConfig.CORE_VERSION;
        String network = runtime.network();
        long generation = runtime.generation();
        if (!runtime.alive()) return CoreDiagnostics.localized(context, CoreDiagnostics.stopped(version, network));
        if (!diagnosticSessionCurrent(network, generation))
            return CoreDiagnostics.localized(context, CoreDiagnostics.paused(version, network));
        try {
            JSONObject status = api.get("/api/status");
            if (!runtime.alive()) return CoreDiagnostics.localized(context, CoreDiagnostics.stopped(version, network));
            if (!diagnosticSessionCurrent(network, generation))
                return CoreDiagnostics.localized(context, CoreDiagnostics.paused(version, network));
            if (!status.has("chain_height") || !status.has("peers") || !status.has("syncing"))
                return CoreDiagnostics.localized(context, CoreDiagnostics.unavailable(version, network));
            return CoreDiagnostics.localized(context, CoreDiagnostics.running(version, network, status.optLong("chain_height"),
                status.optInt("peers"), status.optBoolean("syncing"),
                status.optLong("sync_target")));
        } catch (Exception failure) {
            if (!runtime.alive()) return CoreDiagnostics.localized(context, CoreDiagnostics.stopped(version, network));
            if (!diagnosticSessionCurrent(network, generation))
                return CoreDiagnostics.localized(context, CoreDiagnostics.paused(version, network));
            return CoreDiagnostics.localized(context, CoreDiagnostics.unavailable(version, network));
        }
    }
    private boolean diagnosticSessionCurrent(String network, long generation) {
        return CoreDiagnostics.currentSession(runtime.syncRequested(), SyncNetworkPolicy.read(context).allowed,
            MobileCorePolicy.permitted(context), runtime.alive(), generation, runtime.generation(),
            network, runtime.network());
    }
    public String nodeLog() throws Exception {
        File f=runtime.logFile(); if(!f.exists())return "No node log yet.";
        try(RandomAccessFile in=new RandomAccessFile(f,"r")) { in.seek(Math.max(0,in.length()-16000)); byte[] b=new byte[(int)(in.length()-in.getFilePointer())];in.readFully(b);return new String(b,StandardCharsets.UTF_8); }
    }
}
