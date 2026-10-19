package com.blocknet.wallet;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;

/** Durable per-wallet decision for the first complete chain and wallet scan. */
final class BootstrapSyncState {
    private static final String PREFIX="bootstrap-complete.";
    private static String checkedPath,checkedDigest;
    private static long checkedSize=-1,checkedModified=-1;
    private BootstrapSyncState() { }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("wallet",Context.MODE_PRIVATE); }
    static String scope(String network,String wallet) { return network+"."+wallet; }
    static String activeWallet(SharedPreferences saved,String network) { return saved.getString("active."+network,"main.wallet.dat"); }
    static boolean pending(Context context) {
        NativeRuntime runtime=NativeRuntime.get(context);
        SharedPreferences saved=prefs(context);String network=runtime.network(),wallet=activeWallet(saved,network);
        File file=new File(runtime.walletsDir(),wallet);
        boolean localWallet=file.isFile();
        return shouldKeepRunning(localWallet,localWallet&&markerMatches(
                saved.getString(PREFIX+scope(network,wallet),""),file),
            saved.contains("wallet-change."+network),saved.contains("pending-send."+network));
    }
    private static synchronized boolean markerMatches(String marker,File file) {
        if(!marker.matches("[0-9a-f]{64}\\.[0-9a-f]{64}"))return false;
        String path=file.getAbsolutePath();long size=file.length(),modified=file.lastModified();
        if(!path.equals(checkedPath)||size!=checkedSize||modified!=checkedModified){
            checkedPath=path;checkedSize=size;checkedModified=modified;checkedDigest=null;
            try { checkedDigest=WalletFileBinding.digest(file); } catch(Exception unreadable) { return false; }
        }
        return marker.substring(65).equals(checkedDigest);
    }
    static boolean shouldKeepRunning(boolean localWallet,boolean complete,boolean changing,boolean pendingSend) {
        return changing||pendingSend||(localWallet&&!complete);
    }
    static void begin(Context context,String network,String wallet) throws IOException {
        synchronized(BootstrapSyncState.class){checkedPath=null;checkedDigest=null;}
        if(!prefs(context).edit().remove(PREFIX+scope(network,wallet)).commit())
            throw new IOException("Could not save initial sync state");
    }
    static boolean ready(JSONObject progress) {
        return ready(progress.optInt("version"),progress.optString("wallet_id",""),
            progress.optLong("chain_height",-1),progress.optLong("synced_height",-1),
            progress.optBoolean("wallet_scan_ready"),progress.optBoolean("peer_tip_confirmed"),
            progress.optBoolean("syncing",true));
    }
    static boolean ready(int version,String id,long chainHeight,long syncedHeight,
                         boolean scanReady,boolean peerTipConfirmed,boolean syncing) {
        return version==1&&id!=null&&id.matches("[0-9a-f]{64}")&&chainHeight>=0
            &&syncedHeight==chainHeight&&scanReady&&peerTipConfirmed&&!syncing;
    }
    static boolean shutdownMatches(JSONObject progress, JSONObject proof) {
        return proof != null && ready(progress) && shutdownMatches(progress.optString("wallet_id"),
            progress.optLong("chain_height",-1), progress.optString("chain_hash"), proof.optInt("version"),
            proof.optString("wallet_id"), proof.optLong("chain_height",-1), proof.optString("chain_hash"));
    }
    static boolean shutdownMatches(String readyID,long readyHeight,String readyHash,
                                   int proofVersion,String proofID,long proofHeight,String proofHash) {
        return proofVersion==1&&readyID!=null&&readyID.matches("[0-9a-f]{64}")&&
            readyHeight>=0&&readyHash!=null&&readyHash.matches("[0-9a-f]{64}")&&
            readyID.equals(proofID)&&readyHeight==proofHeight&&readyHash.equals(proofHash);
    }
    static boolean completeAfterStopped(Context context,String network,String wallet,String id) throws IOException {
        NativeRuntime runtime=NativeRuntime.get(context);SharedPreferences saved=prefs(context);
        if(!id.matches("[0-9a-f]{64}")||runtime.alive()||!runtime.syncRequested()||
            !MobileCorePolicy.requested(context)||!network.equals(runtime.network())||
            !wallet.equals(activeWallet(saved,network))||saved.contains("wallet-change."+network)||
            saved.contains("pending-send."+network)||!new File(runtime.walletsDir(),wallet).isFile())return false;
        String digest;
        try { digest=WalletFileBinding.digest(new File(runtime.walletsDir(),wallet)); }
        catch(Exception unreadable) { throw new IOException("Could not bind initial sync to the saved wallet",unreadable); }
        if(!saved.edit().putString(PREFIX+scope(network,wallet),id+"."+digest).commit())
            throw new IOException("Could not save initial sync completion");
        return true;
    }
}
