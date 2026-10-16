package com.blocknet.wallet;

import android.content.*;
import android.system.Os;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Wallet operations are synchronous and must be invoked from a worker thread. */
public final class WalletRepository {
    private final Context context;
    private final NativeRuntime runtime;
    private final WalletApi api;
    private final OfflineSnapshotStore offline;
    private final OfflineWalletReader offlineReader;
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
        if (!s.running || !connection.allowed) { applyOfflineView(s,scope); return s; }
        try { s.status = api.get("/api/status"); }
        catch (Exception e) { s.nodeMessage = "Starting node: " + e.getMessage(); applyOfflineView(s,scope); return s; }
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
            return s;
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
            if(refreshGeneration==runtime.generation()&&s.network.equals(network())&&refreshWallet.equals(activeWallet())&&!hasIncompleteWalletChange())
                TransactionNotifier.rememberWallet(context,s.network,refreshWallet,s.address);
            if (!s.address.isEmpty() && !hasIncompleteWalletChange() && refreshGeneration==runtime.generation()
                && s.network.equals(network()) && refreshWallet.equals(activeWallet()) && runtime.alive()
                && SyncNetworkPolicy.read(context).allowed) {
                try { offline.save(scope,cacheJson(scope,s,WalletFileBinding.digest(walletFile(refreshWallet)))); s.cacheAvailable=true; }
                catch (Exception ignored) { s.cacheAvailable=offline.has(scope); }
            }
        } catch (WalletApi.ApiException e) {
            if (e.status == 403 || e.status == 409) { s.loaded = true; s.locked = true; }
            else if (e.status != 503) throw e;
        } catch (Exception e) {
            if (runtime.alive() && SyncNetworkPolicy.read(context).allowed) throw e;
            s.loaded=false; s.locked=false; s.scanning=false; s.spendable=s.pending=0;
            s.address=""; s.history=new JSONArray(); s.sends=new JSONArray();
            applyOfflineView(s,scope); return s;
        }
        if (!s.loaded || s.locked) applyOfflineView(s,scope);
        return s;
    }
    private static String cacheScope(String network,String wallet) { return network + "/" + wallet; }
    private void authorizeCache(String wallet,String password) {
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
    private static JSONObject cacheJson(String scope,Snapshot s,String walletDigest) throws Exception {
        return new JSONObject().put("scope",scope).put("savedAt",System.currentTimeMillis())
            .put("walletDigest",walletDigest)
            .put("spendable",s.spendable).put("pending",s.pending).put("height",s.height)
            .put("walletHeight",s.walletHeight).put("chainHeightKnown",true)
            .put("viewOnly",s.viewOnly).put("address",s.address)
            .put("history",s.history).put("sends",s.sends);
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
        runtime.writeMantleConfig(); preview = null;
    }
    private String walletChangeKey() { return "wallet-change."+network(); }
    private boolean hasIncompleteWalletChange() { return runtime.prefs.contains(walletChangeKey()); }
    private void requireKnownWallet() throws IOException {
        if(hasIncompleteWalletChange())throw new IOException("A wallet change was interrupted. Open your selected wallet again before sending or making a backup.");
    }
    private void openWallet(String name,String route,JSONObject body) throws Exception {
        String file=filename(name);
        makeRoom();
        // Persist before the side effect: response loss or process death must not expose a
        // loaded wallet under the previous filename, particularly during backup export.
        if(!runtime.prefs.edit().putString(walletChangeKey(),file).commit())throw new IOException("Could not safely record wallet selection");
        try {
            JSONObject opened=api.post(route,body);
            if(!file.equals(opened.getString("filename")))throw new IOException("The core opened an unexpected wallet file");
            activate(file);
            if(!runtime.prefs.edit().remove(walletChangeKey()).commit())throw new IOException("Could not confirm wallet selection");
            authorizeCache(file,body.getString("password"));
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
        if (!runtime.alive() || !SyncNetworkPolicy.read(context).allowed) {
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
        if (!runtime.alive() || !SyncNetworkPolicy.read(context).allowed) {
            unlockSavedOrFile(activeWallet(),password);
            return;
        }
        if(hasIncompleteWalletChange()) { switchWallet(activeWallet(),password);return; }
        try { api.post("/api/wallet/unlock", new JSONObject().put("password",password)); }
        catch (WalletApi.ApiException e) {
            if (e.status != 503) throw e;
            api.post("/api/wallet/load", new JSONObject().put("password",password).put("filepath",activeWallet()));
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
    public synchronized void lock() throws Exception { preview = null; OfflineSnapshotStore.lock(); if (runtime.alive()) api.post("/api/wallet/lock",null); }
    public String seed(String password) throws Exception { requireKnownWallet(); return api.post("/api/wallet/seed",new JSONObject().put("password",password)).getString("mnemonic"); }
    public void rescan() throws Exception { requireKnownWallet(); api.post("/api/wallet/sync",null); }
    public JSONObject sign(String message) throws Exception { requireKnownWallet(); return api.post("/api/wallet/sign",new JSONObject().put("message",message)); }
    public JSONObject verify(String address,String message,String signature) throws Exception { return api.post("/api/verify",new JSONObject().put("address",address).put("message",message).put("signature",signature)); }
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
        preview = api.post("/api/wallet/send",sendBody(address,amount,memo,true));
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
        runtime.writeMantleConfig();
        String version = runtime.mantle("version");
        if (!runtime.alive()) return version + "\nNode is stopped.";
        return version + "\n" + runtime.mantle("status");
    }
    public String nodeLog() throws Exception {
        File f=runtime.logFile(); if(!f.exists())return "No node log yet.";
        try(RandomAccessFile in=new RandomAccessFile(f,"r")) { in.seek(Math.max(0,in.length()-16000)); byte[] b=new byte[(int)(in.length()-in.getFilePointer())];in.readFully(b);return new String(b,StandardCharsets.UTF_8); }
    }
}
