package com.blocknet.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.view.WindowManager;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

/** Mainnet checkpoint acceptance only. Never creates, loads, unlocks, or sends from a wallet. */
public final class WalletCheckpointInstrumentation extends Instrumentation {
    private Bundle arguments;
    private int checks;
    private String stage = "preconditions";
    @Override public void onCreate(Bundle args) { arguments=args==null?new Bundle():args;super.onCreate(args);start(); }
    private void check(boolean ok,String label) {
        if(!ok)throw new AssertionError(label);
        checks++;Bundle status=new Bundle();status.putString("stream","PASS: "+label+"\n");sendStatus(0,status);
    }
    private void waitApi(NativeRuntime runtime,WalletApi api)throws Exception {
        long end=SystemClock.elapsedRealtime()+180000;
        while(SystemClock.elapsedRealtime()<end) {
            try { api.get("/api/status");if(runtime.alive())return; } catch(Exception ignored) { }
            Thread.sleep(300);
        }
        throw new IOException("Mainnet core readiness timeout");
    }
    private Map<String,String> walletHashes(File directory)throws Exception {
        Map<String,String> hashes=new TreeMap<>();File[] files=directory.listFiles();
        if(files==null)throw new IOException("Cannot inspect existing wallet directory");
        for(File file:files) {
            if(!file.isFile())continue;
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(file)) {byte[] bytes=new byte[8192];int count;while((count=in.read(bytes))!=-1)digest.update(bytes,0,count);}
            hashes.put(file.getName(),Base64.getEncoder().encodeToString(digest.digest()));
        }
        return hashes;
    }
    private void removeOwnedTemp(File directory)throws Exception {
        File[] files=directory.listFiles();if(files==null)throw new IOException("Cannot inspect owned temporary checkpoint directory");
        for(File file:files){if(!file.isFile()||!file.delete())throw new IOException("Cannot remove owned checkpoint temporary file");}
        if(!directory.delete())throw new IOException("Cannot remove owned checkpoint temporary directory");
    }
    @Override public void onStart() {
        NativeRuntime runtime=NativeRuntime.get(getTargetContext());WalletRepository repo=new WalletRepository(getTargetContext());WalletApi api=new WalletApi(runtime);
        Bundle result=new Bundle();Activity activity=null;File temp=null;boolean started=false,success=false;Map<String,String> wallets=null;
        try {
            check(Os.uname().machine.equals("aarch64"),"Physical ARM64 device");
            check(runtime.network().equals("mainnet"),"Mainnet selected; no other network is used");
            check(!runtime.alive(),"Existing mainnet core stopped before instrumentation");
            check(!repo.hasPendingSend(),"No unresolved mainnet send can be affected");
            wallets=walletHashes(runtime.walletsDir());
            long previousHeight=Long.parseLong(arguments.getString("previousHeight","0"));
            check(previousHeight>=0,"Previous chain height argument is valid");
            stage="foreground activity for Android checkpoint download";
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity shown=activity;runOnMainSync(()->shown.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            stage="Android bundled checkpoint seed and atomic file";
            temp=Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),"checkpoint-acceptance-").toFile();
            CheckpointManager.Status first=CheckpointManager.seedBundled(temp,getTargetContext().getAssets().open("checkpoints-mainnet.dat"));
            boolean firstValid=first.entries>0&&first.maxHeight>0&&first.refreshed&&first.warning.isEmpty();
            if(!firstValid) {
                Bundle diagnostic=new Bundle();diagnostic.putString("stream","CHECKPOINT SEED DIAGNOSTIC: entries="+first.entries+", maxHeight="+first.maxHeight+", refreshed="+first.refreshed+", warning="+first.warning.replace('\n',' ').replace('\r',' ')+"\n");sendStatus(0,diagnostic);
            }
            check(firstValid,"Packaged mainnet checkpoint seed is validated on Android");
            File temporaryCheckpoints=new File(temp,"checkpoints.dat");
            check((Os.stat(temporaryCheckpoints.getAbsolutePath()).st_mode&0777)==0600,"Android atomic checkpoint file has mode 0600");
            byte[] original=Files.readAllBytes(temporaryCheckpoints.toPath());
            CheckpointManager.Status second=CheckpointManager.seedBundled(temp,getTargetContext().getAssets().open("checkpoints-mainnet.dat"));
            check(second.entries==first.entries&&second.maxHeight==first.maxHeight&&!second.refreshed&&second.warning.isEmpty(),"Repeated packaged seed preserves existing mainnet checkpoints");
            check(Arrays.equals(original,Files.readAllBytes(temporaryCheckpoints.toPath())),"Repeated seed preserves exact checkpoint file bytes");
            stage="official HTTPS checkpoint refresh with validated local fallback";
            long dnsStarted=SystemClock.elapsedRealtime();int ipv4=0,ipv6=0;String dnsError="none";
            try {
                for(java.net.InetAddress address:java.net.InetAddress.getAllByName(new java.net.URL(CheckpointManager.SOURCE).getHost())) {
                    if(address instanceof java.net.Inet4Address)ipv4++;
                    else if(address instanceof java.net.Inet6Address)ipv6++;
                }
            }catch(Exception dnsFailure){dnsError=dnsFailure.getClass().getSimpleName();}
            long dnsElapsed=SystemClock.elapsedRealtime()-dnsStarted;
            CheckpointManager.Status online=CheckpointManager.refresh(temp);
            if(!online.warning.isEmpty()) {
                Bundle diagnostic=new Bundle();
                diagnostic.putString("stream","CHECKPOINT REFRESH WARNING: dns_elapsed_ms="+dnsElapsed+", ipv4_count="+ipv4+", ipv6_count="+ipv6+", dns_error="+dnsError+", entries="+online.entries+", maxHeight="+online.maxHeight+", refreshed="+online.refreshed+", warning="+online.warning.replace('\n',' ').replace('\r',' ')+"\n");sendStatus(0,diagnostic);
                check(!online.refreshed&&Arrays.equals(original,Files.readAllBytes(temporaryCheckpoints.toPath())),"Unavailable official refresh retains exact validated bundled checkpoints");
            }
            check(online.entries>=first.entries&&online.maxHeight>=first.maxHeight,"Official refresh or offline fallback never downgrades bundled checkpoints");
            removeOwnedTemp(temp);temp=null;
            stage="existing mainnet chain startup";
            started=true;repo.startNode();waitApi(runtime,api);
            check(runtime.alive()&&runtime.network().equals("mainnet"),"Actual app foreground service starts supplied ARM mainnet core");
            JSONObject status=api.get("/api/status");
            check(status.getLong("chain_height")>=previousHeight,"Existing mainnet chain height is preserved");
            check(!status.has("wallet"),"Core starts without loading an existing wallet");
            JSONObject settings=api.get("/api/settings").getJSONObject("settings");
            check(!settings.getBoolean("full_sync"),"Mainnet full_sync is disabled for checkpoint sync");
            check(!settings.getBoolean("save_checkpoints"),"Core checkpoint writer is disabled during explicit refresh");
            stage="authoritative checkpoint load and repository cache";
            JSONObject authoritative=api.post("/api/checkpoints/load",null);
            check(authoritative.getInt("loaded")>0&&authoritative.getLong("max_height")>0,"Authenticated core API loaded mainnet checkpoints");
            check(authoritative.getBoolean("fast_sync")== (authoritative.getLong("chain_height")<authoritative.getLong("max_height")),"Core fast_sync matches chain height and loaded checkpoints");
            WalletRepository.Snapshot snapshot=repo.refresh();
            check(snapshot.checkpointEntries==authoritative.getInt("loaded")&&snapshot.checkpointHeight==authoritative.getLong("max_height"),"Repository checkpoint status matches authoritative core load");
            JSONObject cached=runtime.verifyCheckpoints();cached.put("loaded",-1);
            check(runtime.verifyCheckpoints().getInt("loaded")==authoritative.getInt("loaded"),"Checkpoint verification cache returns a defensive copy");
            stage="checkpoint refresh while mainnet core is running";
            JSONObject refreshed=repo.refreshCheckpoints();
            check(refreshed.getInt("loaded")>0&&refreshed.getInt("entries")>0&&refreshed.getLong("max_height")>=authoritative.getLong("max_height"),"Running-node refresh reloads retained mainnet checkpoints");
            JSONObject reloaded=api.post("/api/checkpoints/load",null);
            WalletRepository.Snapshot after=repo.refresh();
            check(after.checkpointEntries==reloaded.getInt("loaded")&&after.checkpointHeight==reloaded.getLong("max_height"),"Refreshed checkpoint cache matches a new authoritative API load");
            check(after.height>=previousHeight,"Mainnet sync retains previous chain progress");
            stage="graceful mainnet stop and wallet preservation";
            repo.stopNode();started=false;
            check(!runtime.alive(),"Owned mainnet core stopped before instrumentation exits");
            check(wallets.equals(walletHashes(runtime.walletsDir())),"Every existing encrypted wallet file remains unchanged");
            success=true;
        } catch(Throwable failure) {
            result.putString("stream","FAIL at "+stage+" after "+checks+" checks ("+failure.getClass().getSimpleName()+"). No wallet credentials or private filenames are logged.\n");
        } finally {
            try {
                if(started){repo.stopNode();check(!runtime.alive(),"Mainnet core stopped after interrupted checkpoint test");}
                if(temp!=null)removeOwnedTemp(temp);
                if(wallets!=null)check(wallets.equals(walletHashes(runtime.walletsDir())),"Existing wallet files preserved after cleanup");
            } catch(Throwable cleanup) {success=false;result.putString("stream",result.getString("stream","")+"FAIL cleanup ("+cleanup.getClass().getSimpleName()+"). Inspect the stopped-node state before relaunch.\n");}
            if(activity!=null){final Activity shown=activity;runOnMainSync(()->{shown.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);shown.finish();});}
        }
        if(success)result.putString("stream","OK ("+checks+" mainnet checkpoint acceptance checks). Existing wallets preserved; node stopped for normal app relaunch.\n");
        finish(success?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
