package com.blocknet.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Destructive only to this runner's fresh testnet directory; refuses existing testnet data. Never broadcasts. */
public final class WalletDeviceInstrumentation extends Instrumentation {
    private int checks;
    private String stage="preconditions";
    private Bundle arguments;
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args;start();}
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;Bundle status=new Bundle();status.putString("stream","PASS: "+label+"\n");sendStatus(0,status);}
    private void waitApi(NativeRuntime runtime,WalletApi api) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+120000;
        while(SystemClock.elapsedRealtime()<deadline){try{api.get("/api/status");return;}catch(Exception e){Thread.sleep(250);}}
        throw new IOException("API readiness timeout");
    }
    private WalletRepository.Snapshot ready(WalletRepository repo) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+120000;
        while(SystemClock.elapsedRealtime()<deadline){WalletRepository.Snapshot snapshot=repo.refresh();if(snapshot.loaded&&!snapshot.locked&&!snapshot.scanning)return snapshot;Thread.sleep(250);}
        throw new IOException("Wallet readiness timeout");
    }
    private void remove(File file)throws Exception{if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("Cannot list cleanup directory");for(File child:children)remove(child);}if(file.exists()&&!file.delete())throw new IOException("Cannot clean test fixture");}
    @SuppressWarnings("unchecked") private void restorePreferences(SharedPreferences prefs,Map<String,?> saved){
        SharedPreferences.Editor editor=prefs.edit().clear();
        for(Map.Entry<String,?> entry:saved.entrySet()){String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,new HashSet<>((Set<String>)value));}
        if(!editor.commit())throw new IllegalStateException("Cannot restore preferences");
    }
    @Override public void onStart(){
        if(arguments!=null&&"true".equals(arguments.getString("safe-stop-only"))){safeStopOnly();return;}
        if(arguments!=null&&"true".equals(arguments.getString("language-ui-only"))){languageUiOnly();return;}
        if(arguments!=null&&"true".equals(arguments.getString("language-return-only"))){languageReturnOnly();return;}
        if(arguments!=null&&"true".equals(arguments.getString("language-restore-english"))){languageRestoreEnglish();return;}
        if(arguments!=null&&"true".equals(arguments.getString("language-persisted-only"))){languagePersistedOnly();return;}
        if(arguments!=null&&"true".equals(arguments.getString("foreground-policy-only"))){foregroundPolicyOnly();return;}
        if(arguments!=null&&"true".equals(arguments.getString("cache-binding-only"))){cacheBindingOnly();return;}
        NativeRuntime runtime=NativeRuntime.get(getTargetContext());WalletRepository repo=new WalletRepository(getTargetContext());WalletApi api=new WalletApi(runtime);
        Bundle result=new Bundle();Activity activity=null;boolean ownsFixture=false;Map<String,?> preferences=new HashMap<>(runtime.prefs.getAll());
        File fixture=new File(runtime.root,"testnet");
        try{
            check(getTargetContext().getPackageName().equals("com.blocknet.wallet.offlinetest"),"Only the isolated OFFLINETEST package may run on a physical device");
            check(Os.uname().machine.equals("aarch64"),"Physical device executes native ARM64");
            check(interactiveAndUnlocked(),"Device is interactive and unlocked for visible UI checks");
            check(!runtime.alive()&&!runtime.syncRequested()&&!MobileCorePolicy.requested(getTargetContext()),
                "No existing isolated node or saved sync request can auto-resume during the test");
            check(runtime.network().equals("mainnet"),"Original app network is mainnet");
            check(!fixture.exists(),"No existing testnet data: isolated disposable directory available");
            check(!runtime.prefs.contains("pending-send.testnet")&&!runtime.prefs.contains("pending-send.mainnet"),"No pending payment can be affected");
            ownsFixture=true;
            check(runtime.prefs.edit().putString("network","testnet").remove("active.testnet").commit()
                && runtime.network().equals("testnet"),"Disposable testnet selection persisted before Core starts");
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext().getPackageName(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity current=activity;runOnMainSync(()->current.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            String prefix="device_"+UUID.randomUUID().toString().replace("-","").substring(0,12);String password="Device-test-"+UUID.randomUUID();
            stage="start actual foreground node";repo.startNode();waitApi(runtime,api);check(runtime.alive(),"App foreground service owns actual ARM core process");
            String diagnostics=repo.diagnostics();
            check(diagnostics.contains(BuildConfig.CORE_VERSION)&&!diagnostics.contains("Mantle"),"Diagnostics use the running authenticated Core status");
            stage="create and recovery phrase";repo.create(prefix,password);String mnemonic=repo.seed(password);String address=ready(repo).address;
            check(mnemonic.split(" ").length==12&&!address.isEmpty(),"Create encrypted wallet and obtain twelve recovery words");
            check(repo.refresh().spendable==0,"Disposable wallet has zero spendable funds");
            stage="offline saved view";repo.stopNode();repo.lock();
            WalletRepository.Snapshot lockedOffline=repo.refresh();
            check(!runtime.alive()&&lockedOffline.cacheAvailable&&lockedOffline.locked&&!lockedOffline.cached,
                "Stopped Core keeps the last saved state private until wallet unlock");
            repo.unlock(password);WalletRepository.Snapshot offline=repo.refresh();
            check(!runtime.alive(),"Offline password unlock leaves Core stopped");
            check(offline.cacheAvailable,"Offline password unlock retains a saved view");
            check(repo.offlineSessionAuthorized(),"Offline password unlock authorizes this wallet's saved view");
            check(offline.cached&&offline.loaded&&!offline.locked,"Offline password unlock renders saved state");
            check(offline.savedAt>0,"Offline saved state carries a date");
            check(offline.address.equals(address),"Offline saved address matches the disposable wallet");
            check(offline.spendable==0,"Offline saved spendable balance remains zero");
            boolean sendBlocked=false;try{repo.previewSend(address,"1","");}catch(IOException expected){sendBlocked=true;}
            check(sendBlocked&&!repo.hasPendingSend(),"Stopped Core blocks payment preview without creating a send request");
            repo.startNode();waitApi(runtime,api);repo.unlock(password);check(ready(repo).address.equals(address),
                "Foreground restart reconciles the saved view with the live disposable wallet");
            stage="lock unlock";repo.lock();check(repo.refresh().locked,"Lock is reflected in native repository snapshot");repo.unlock(password);check(ready(repo).address.equals(address),"Unlock preserves wallet address");
            stage="message signing";String message="Physical Android integration test";String signature=repo.sign(message).getString("signature");check(repo.verify(address,message,signature).optBoolean("valid"),"Physical app signs and verifies a message");
            stage="dry run insufficient funds";boolean rejected=false;try{repo.previewSend(address,"1","");}catch(IOException expected){if(expected.getMessage()==null||!expected.getMessage().toLowerCase(Locale.ROOT).contains("insufficient"))throw expected;rejected=true;}check(rejected&&!repo.hasPendingSend(),"Unfunded payment preview rejects without pending send or broadcast");
            stage="recovery restore";repo.restore(prefix+"_restore",password,mnemonic);check(ready(repo).address.equals(address),"Recovery phrase restore preserves address across scanner-safe restart");
            stage="view-only import";JSONObject keys=api.post("/api/wallet/viewkeys",new JSONObject().put("password",password).put("create_file",false).put("filename",prefix+"_view_export.wallet.dat"));
            repo.importKeys(prefix+"_view",password,keys.getString("spend_pub"),keys.getString("view_priv"),true);WalletRepository.Snapshot watch=ready(repo);check(watch.viewOnly&&watch.address.equals(address),"View-only key import preserves address and spending restriction");
            stage="wallet reopen and core restart";repo.switchWallet(prefix+"_restore",password);check(ready(repo).address.equals(address),"Switching back opens original spend-capable wallet");repo.stopNode();check(!runtime.alive(),"Stopping foreground node terminates owned core");String stoppedReport=repo.diagnostics();check(stoppedReport.equals(CoreDiagnostics.localized(getTargetContext(),CoreDiagnostics.stopped(BuildConfig.CORE_VERSION,runtime.network()))),"Stopped diagnostics never claim a current chain height");repo.startNode();waitApi(runtime,api);repo.unlock(password);check(ready(repo).address.equals(address),"Stop/restart reopens persisted encrypted wallet");
            stage="encrypted backup export";ByteArrayOutputStream backup=new ByteArrayOutputStream();repo.exportBackup(backup);byte[] encrypted=backup.toByteArray();check(!runtime.alive()&&encrypted.length>0,"Encrypted backup export flushes wallet with core stopped");
            stage="encrypted backup import";repo.importBackup(new ByteArrayInputStream(encrypted),prefix+"_backup");Arrays.fill(encrypted,(byte)0);repo.startNode();waitApi(runtime,api);repo.switchWallet(prefix+"_backup",password);check(ready(repo).address.equals(address),"Imported encrypted backup reopens with same address");
            repo.stopNode();check(!runtime.alive(),"Integration ends with app core stopped");
            result.putString("stream","OK ("+checks+" physical-device integration checks). No funds or broadcast used.\n");
        }catch(Throwable failure){result.putString("stream","FAIL at "+stage+" after "+checks+" checks ("+failure.getClass().getSimpleName()+"). Secret-bearing exception text is intentionally omitted.\n");}
        finally{
            if(ownsFixture){
                try{repo.stopNode();if(runtime.alive())throw new IOException("Core still running");remove(fixture);restorePreferences(runtime.prefs,preferences);check(runtime.network().equals("mainnet")&&!runtime.alive(),"Original preferences restored; mainnet stopped");}
                catch(Throwable cleanup){result.putString("stream",result.getString("stream","")+"FAIL cleanup: "+cleanup.getClass().getSimpleName()+"; inspect disposable testnet fixture before rerunning.\n");}
            }
            if(activity!=null){final Activity current=activity;runOnMainSync(()->{current.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);current.finish();});}
            if(ownsFixture)try { Thread.sleep(900); check(!runtime.alive()&&!runtime.syncRequested()
                &&!MobileCorePolicy.requested(getTargetContext()),"No isolated node or sync request resumes after activity finish"); }
            catch(Throwable resumed){result.putString("stream",result.getString("stream","")+"FAIL post-test isolation ("+resumed.getClass().getSimpleName()+").\n");}
        }
        if(result.getString("stream","").startsWith("OK")&&!result.getString("stream","").contains("FAIL"))result.putString("stream","OK ("+checks+" physical-device integration checks). No funds or broadcast used.\n");
        finish(result.getString("stream","").startsWith("OK")&&!result.getString("stream","").contains("FAIL")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }

    /** Encrypted-cache integrity probe using only new synthetic files in OFFLINETEST. */
    private void cacheBindingOnly(){
        android.content.Context context=getTargetContext();NativeRuntime runtime=NativeRuntime.get(context);
        Bundle result=new Bundle();File wallet=null,cache=null;boolean ownsFixture=false,ownsSession=false;
        try {
            check("com.blocknet.wallet.offlinetest".equals(context.getPackageName()),"Cache probe targets only OFFLINETEST");
            check(interactiveAndUnlocked()&&!runtime.alive()&&!runtime.syncRequested()
                &&!MobileCorePolicy.requested(context),"No active isolated wallet sync can overlap the cache probe");
            java.lang.reflect.Field session=OfflineSnapshotStore.class.getDeclaredField("session");
            session.setAccessible(true);
            check(session.get(null)==null,"No existing offline wallet session can be displaced");
            String nonce=UUID.randomUUID().toString().replace("-","");
            String scope="testnet/cacheprobe_"+nonce+".wallet.dat";
            wallet=new File(context.getCacheDir(),"cacheprobe_"+nonce+".dat");
            byte[] scopeHash=java.security.MessageDigest.getInstance("SHA-256")
                .digest(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder name=new StringBuilder();for(byte b:scopeHash)name.append(String.format(Locale.ROOT,"%02x",b&255));
            cache=new File(new File(context.getNoBackupFilesDir(),"wallet-snapshots"),name+".cache");
            check(!wallet.exists()&&!cache.exists()&&!new File(cache.getPath()+".bak").exists(),
                "Synthetic wallet and cache paths are new");
            ownsFixture=true;
            java.nio.file.Files.write(wallet.toPath(),"cipher-a".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String password="Cache-probe-"+UUID.randomUUID();
            String binding=WalletFileBinding.digest(wallet);
            OfflineSnapshotStore store=new OfflineSnapshotStore(context);
            store.authorizeOnline(scope,password);
            ownsSession=true;
            store.save(scope,new JSONObject().put("scope",scope).put("savedAt",System.currentTimeMillis())
                .put("walletDigest",binding).put("marker",1));
            check(cache.isFile()&&store.has(scope),"Synthetic view was encrypted and saved");
            OfflineSnapshotStore.lock();
            boolean wrongPasswordRejected=false;
            try{store.unlockOffline(scope,"wrong-"+password);}catch(IOException expected){wrongPasswordRejected=true;}
            check(wrongPasswordRejected,"Wrong password cannot open the synthetic encrypted view");
            JSONObject saved=store.unlockOffline(scope,password);
            check(saved.getInt("marker")==1&&binding.equals(saved.getString("walletDigest")),
                "Correct password opens the originally bound encrypted view");
            long modified=wallet.lastModified();
            java.nio.file.Files.write(wallet.toPath(),"cipher-b".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            check(wallet.setLastModified(modified)&&wallet.lastModified()==modified&&wallet.length()==8,
                "Replacement preserves file size and modified time");
            check(!WalletFileBinding.matches(saved.getString("walletDigest"),WalletFileBinding.digest(wallet)),
                "Offline full-file binding rejects a same-metadata wallet replacement");
            byte[] before=java.nio.file.Files.readAllBytes(cache.toPath());
            OfflineSnapshotStore.lock();
            boolean lockedWriteRejected=false;
            try{store.save(scope,new JSONObject().put("scope",scope).put("savedAt",System.currentTimeMillis())
                .put("walletDigest",WalletFileBinding.digest(wallet)).put("marker",2));}
            catch(IOException expected){lockedWriteRejected=true;}
            check(lockedWriteRejected&&Arrays.equals(before,java.nio.file.Files.readAllBytes(cache.toPath())),
                "Lock before encrypted write rejects it without changing saved ciphertext");
            check(store.unlockOffline(scope,password).getInt("marker")==1,
                "The previously saved encrypted view remains intact after a locked write");
            result.putString("stream","OK (synthetic encrypted-cache binding and lock checks).\n");
        } catch(Throwable failure){result.putString("stream","FAIL cache binding ("+failure.getClass().getSimpleName()+").\n");}
        finally {
            if(ownsSession)OfflineSnapshotStore.lock();
            if(ownsFixture)try{remove(wallet);remove(cache);remove(new File(cache.getPath()+".bak"));remove(new File(cache.getPath()+".new"));}
            catch(Throwable cleanup){result.putString("stream",result.getString("stream","")+"FAIL synthetic cache cleanup ("+cleanup.getClass().getSimpleName()+").\n");}
        }
        finish(result.getString("stream","").startsWith("OK")&&!result.getString("stream","").contains("FAIL")
            ?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }

    /** Exercises the mobile default on a fresh, unfunded testnet wallet only. */
    private void foregroundPolicyOnly(){
        android.content.Context context=getTargetContext();NativeRuntime runtime=NativeRuntime.get(context);
        WalletRepository repo=new WalletRepository(context);WalletApi api=new WalletApi(runtime);
        Map<String,?> preferences=new HashMap<>(runtime.prefs.getAll());
        File fixture=new File(runtime.root,"testnet");Activity activity=null;boolean ownsFixture=false;
        Bundle result=new Bundle();stage="foreground policy preconditions";
        try{
            check("com.blocknet.wallet.offlinetest".equals(context.getPackageName()),"Foreground policy test targets only OFFLINETEST");
            check(interactiveAndUnlocked(),"Device is interactive and unlocked for lifecycle test");
            check(!runtime.alive()&&!runtime.syncRequested()&&!MobileCorePolicy.requested(context),"No isolated Core or saved sync request is active");
            check("mainnet".equals(runtime.network())&&!fixture.exists(),"Disposable testnet directory is free");
            check(!runtime.prefs.contains("pending-send.mainnet")&&!runtime.prefs.contains("pending-send.testnet"),"No pending payment can be affected");
            check(!runtime.prefs.contains("mobile_core_foreground_only")&&MobileCorePolicy.enabled(context),
                "Missing preference selects foreground-only without persisting a value");
            ownsFixture=true;
            check(runtime.prefs.edit().putString("network","testnet").remove("active.testnet").commit(),
                "Disposable testnet selection persisted");
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(context.getPackageName(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity first=activity;runOnMainSync(()->first.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            String prefix="policy_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
            String password="Policy-test-"+UUID.randomUUID();
            stage="default foreground start";repo.startNode();waitApi(runtime,api);
            repo.create(prefix,password);WalletRepository.Snapshot live=ready(repo);String address=live.address;
            check(runtime.alive()&&live.spendable==0,"Foreground Core opened an unfunded disposable wallet");
            stage="default background stop";
            runOnMainSync(first::finish);activity=null;
            long deadline=SystemClock.elapsedRealtime()+30000;
            while(runtime.alive()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
            check(!runtime.alive()&&MobileCorePolicy.requested(context),"Default background policy stopped Core but retained the user's sync request");
            deadline=SystemClock.elapsedRealtime()+10000;
            while(runtime.syncRequested()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
            check(!runtime.syncRequested()&&MobileCorePolicy.requested(context),
                "Paused service released its in-memory request and ended background polling");
            check(interactiveAndUnlocked(),"Device stayed unlocked during background stop");
            stage="dated offline view";repo.unlock(password);WalletRepository.Snapshot offline=repo.refresh();
            check(!runtime.alive()&&repo.offlineSessionAuthorized()&&offline.cached&&offline.loaded&&!offline.locked,
                "Background stop permits password-authorized saved view without Core");
            check(offline.savedAt>0&&offline.address.equals(address)&&offline.spendable==0,
                "Saved view is dated and matches the unfunded disposable wallet");
            stage="foreground automatic restart";
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(context.getPackageName(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity resumed=activity;runOnMainSync(()->resumed.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            waitApi(runtime,api);repo.unlock(password);
            check(runtime.alive()&&ready(repo).address.equals(address),"Foreground return restarted Core and reconciled saved wallet state");
            stage="explicit continuous preference";repo.stopNode();
            MobileCorePolicy.setEnabled(context,false);
            check(runtime.prefs.contains("mobile_core_foreground_only")&&!MobileCorePolicy.enabled(context),
                "Explicit continuous choice is persisted");
            repo.startNode();waitApi(runtime,api);
            Activity continuous=activity;runOnMainSync(continuous::finish);activity=null;
            SystemClock.sleep(2200);
            check(runtime.alive()&&runtime.syncRequested()&&!MobileCorePolicy.enabled(context),
                "Explicit continuous choice preserves background Core operation");
            check(interactiveAndUnlocked(),"Device stayed unlocked during continuous-mode check");
            stage="manual stop";repo.stopNode();
            check(!runtime.alive()&&!runtime.syncRequested()&&!MobileCorePolicy.requested(context),
                "Manual Stop clears the durable request before process death");
            result.putString("stream","OK ("+checks+" isolated foreground-policy checks). No funds or broadcast used.\n");
        }catch(Throwable failure){result.putString("stream","FAIL at "+stage+" after "+checks+" checks ("+failure.getClass().getSimpleName()+"). Secret-bearing exception text omitted.\n");}
        finally{
            if(ownsFixture)try{
                repo.stopNode();if(runtime.alive())throw new IOException("Core still running");
                remove(fixture);restorePreferences(runtime.prefs,preferences);
                check("mainnet".equals(runtime.network())&&!runtime.alive()&&!MobileCorePolicy.requested(context),
                    "Original preferences restored and isolated Core stopped");
            }catch(Throwable cleanup){result.putString("stream",result.getString("stream","")+"FAIL cleanup ("+cleanup.getClass().getSimpleName()+").\n");}
            if(activity!=null){Activity current=activity;runOnMainSync(()->{current.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);current.finish();});}
        }
        boolean passed=result.getString("stream","").startsWith("OK")&&!result.getString("stream","").contains("FAIL");
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }

    /** Isolated sidecar maintenance: request a graceful exit and never force-kill Core. */
    private void gracefulStopIsolated() throws Exception {
        android.content.Context context=getTargetContext();
        check("com.blocknet.wallet.offlinetest".equals(context.getPackageName()),"Only OFFLINETEST may be stopped");
        NativeRuntime runtime=NativeRuntime.get(context);
        check("mainnet".equals(runtime.network()),"Existing sidecar is on mainnet");
        check(!runtime.prefs.contains("pending-send.mainnet")&&!runtime.prefs.contains("pending-send.testnet"),
            "No pending payment is present");
        if(runtime.alive()){
            try {new WalletApi(runtime).post("/api/wallet/lock",null);
                throw new IOException("A wallet is loaded; automatic stop was declined");
            } catch(WalletApi.ApiException response) {
                if(response.status!=503||!"no wallet loaded".equals(response.getMessage()))throw response;
            }
        }
        MobileCorePolicy.setRequested(context,false);
        runtime.setSyncRequested(false);
        runtime.cancelCheckpointRefresh();
        runtime.signalNetworkPause();
        long deadline=SystemClock.elapsedRealtime()+15000;
        while(runtime.alive()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
        check(!runtime.alive(),"Core exited after SIGTERM without a forced kill");
        context.stopService(new Intent(context,CoreService.class));
        runtime.stop(); // The child has already exited; this only clears stale ownership files.
        check(!runtime.alive()&&!runtime.syncRequested()&&!MobileCorePolicy.requested(context),
            "OFFLINETEST sync is stopped and cannot auto-resume");
    }
    private void safeStopOnly(){
        Bundle result=new Bundle();
        try {gracefulStopIsolated();result.putString("stream","OK (isolated graceful stop; no wallet or pending payment opened).\n");finish(Activity.RESULT_OK,result);}
        catch(Throwable failure){result.putString("stream","FAIL isolated graceful stop ("+failure.getClass().getSimpleName()+": "+failure.getMessage()+").\n");finish(Activity.RESULT_CANCELED,result);}
    }
    private AccessibilityNodeInfo visibleText(String label){
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root==null)return null;
        for(String query:new String[]{label,label.toUpperCase(java.util.Locale.ROOT)})
            for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(query))
                if(node.isVisibleToUser()&&node.getText()!=null&&label.equalsIgnoreCase(node.getText().toString()))return node;
        return null;
    }
    private boolean waitForText(String label,long timeoutMs){
        long deadline=SystemClock.elapsedRealtime()+timeoutMs;
        while(SystemClock.elapsedRealtime()<deadline){if(visibleText(label)!=null)return true;SystemClock.sleep(150);}
        return false;
    }
    private boolean clickText(String label,long timeoutMs){
        long deadline=SystemClock.elapsedRealtime()+timeoutMs;
        while(SystemClock.elapsedRealtime()<deadline){
            AccessibilityNodeInfo node=visibleText(label);
            while(node!=null&&!node.isClickable())node=node.getParent();
            if(node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
            SystemClock.sleep(150);
        }
        return false;
    }
    private String languageDialogProbe(){
        return "title="+(visibleText("Language")!=null)+", English="+(visibleText("English")!=null)
            +", Simplified Chinese="+(visibleText("Simplified Chinese")!=null)
            +", 简体中文="+(visibleText("简体中文")!=null)+", Cancel="+(visibleText("Cancel")!=null);
    }
    private boolean interactiveAndUnlocked(){
        android.os.PowerManager power=(android.os.PowerManager)getTargetContext().getSystemService(android.content.Context.POWER_SERVICE);
        android.app.KeyguardManager keyguard=(android.app.KeyguardManager)getTargetContext().getSystemService(android.content.Context.KEYGUARD_SERVICE);
        return power!=null&&power.isInteractive()&&keyguard!=null&&!keyguard.isDeviceLocked();
    }
    /** Exercises the rendered picker with Core running but no wallet loaded. */
    private void languageUiOnly(){
        Bundle result=new Bundle();boolean started=false;Activity display=null;
        try {
            android.content.Context context=getTargetContext();NativeRuntime runtime=NativeRuntime.get(context);
            check("com.blocknet.wallet.offlinetest".equals(context.getPackageName()),"Language UI is isolated to OFFLINETEST");
            check(interactiveAndUnlocked(),"Device is interactive and unlocked for the language picker");
            check("mainnet".equals(runtime.network())&&!runtime.alive()&&!MobileCorePolicy.requested(context),
                "Mainnet sidecar starts stopped");
            check(!runtime.prefs.contains("pending-send.mainnet")&&!runtime.prefs.contains("pending-send.testnet"),
                "No pending payment is present");
            display=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(context.getPackageName(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity opening=display;
            runOnMainSync(()->opening.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            check(waitForText("Language / 语言",10000),"English language button is visibly rendered");
            new WalletRepository(context).startNode();started=true;waitApi(runtime,new WalletApi(runtime));
            check(clickText("Language / 语言",10000),"Visible language button opens the warning with Core running");
            check(clickText("Continue",5000),"Warning confirms before showing language choices (title="
                +(visibleText("Language")!=null)+", continue="+(visibleText("Continue")!=null)
                +", cancel="+(visibleText("Cancel")!=null)+")");
            check(waitForText("Follow phone language",5000)&&waitForText("English",1000)
                &&waitForText("Simplified Chinese",1000),"All three language choices are visible ("+languageDialogProbe()+")");
            check(clickText("Simplified Chinese",5000),"Visible Simplified Chinese item is selected ("+languageDialogProbe()+")");
            long deadline=SystemClock.elapsedRealtime()+15000;
            while(!AppLanguage.CHINESE.equals(AppLanguage.choice(context))&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(150);
            check(AppLanguage.CHINESE.equals(AppLanguage.choice(context)),"Android persisted the Chinese app locale");
            check(waitForText("语言 / Language",10000),"Recreated activity renders the Chinese language button");
            check(clickText("语言 / Language",5000),"Chinese button reopens the warning");
            check(clickText("继续",5000),"Translated warning confirms before showing choices");
            check(waitForText("简体中文",5000),"Selector renders the Chinese choice label");
            gracefulStopIsolated();started=false;
            result.putString("stream","OK (visible Chinese picker and activity recreation with unloaded Core).\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable failure){
            result.putString("stream","FAIL visible language flow ("+failure.getClass().getSimpleName()+": "+failure.getMessage()+").\n");
            if(started)try{gracefulStopIsolated();}catch(Exception stopFailure){result.putString("stream",result.getString("stream")+"FAIL graceful cleanup ("+stopFailure.getClass().getSimpleName()+").\n");}
            finish(Activity.RESULT_CANCELED,result);
        } finally {
            if(display!=null){final Activity opening=display;runOnMainSync(()->opening.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));}
        }
    }
    /** Switches Chinese to English and back through the rendered picker. */
    private void languageReturnOnly(){
        Bundle result=new Bundle();boolean started=false;Activity display=null;
        try {
            android.content.Context context=getTargetContext();NativeRuntime runtime=NativeRuntime.get(context);
            check("com.blocknet.wallet.offlinetest".equals(context.getPackageName())&&interactiveAndUnlocked(),
                "Return language test is unlocked and isolated to OFFLINETEST");
            check("mainnet".equals(runtime.network())&&!runtime.alive()&&!MobileCorePolicy.requested(context)
                &&!runtime.prefs.contains("pending-send.mainnet"),"Sidecar starts stopped with no pending payment");
            check(AppLanguage.CHINESE.equals(AppLanguage.choice(context)),"Chinese locale is initially selected");
            display=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(context.getPackageName(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity opening=display;runOnMainSync(()->opening.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            check(waitForText("语言 / Language",10000),"Chinese language button is visible");
            new WalletRepository(context).startNode();started=true;waitApi(runtime,new WalletApi(runtime));
            check(clickText("语言 / Language",10000)&&clickText("继续",5000),"Chinese warning opens the choice list with Core running");
            check(waitForText("跟随手机语言",5000)&&waitForText("英语",1000)&&waitForText("简体中文",1000),
                "All three translated choices are visible");
            check(clickText("英语",5000),"English is chosen through the Chinese picker");
            long deadline=SystemClock.elapsedRealtime()+15000;
            while(!AppLanguage.ENGLISH.equals(AppLanguage.choice(context))&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(150);
            check(AppLanguage.ENGLISH.equals(AppLanguage.choice(context))&&waitForText("Language / 语言",10000),
                "English locale persists and recreated activity renders English");
            check(clickText("Language / 语言",5000)&&clickText("Continue",5000),"English warning reopens choices");
            check(clickText("Simplified Chinese",5000),"Simplified Chinese is chosen again");
            deadline=SystemClock.elapsedRealtime()+15000;
            while(!AppLanguage.CHINESE.equals(AppLanguage.choice(context))&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(150);
            check(AppLanguage.CHINESE.equals(AppLanguage.choice(context))&&waitForText("语言 / Language",10000),
                "Chinese locale and rendered button return after recreation");
            gracefulStopIsolated();started=false;
            result.putString("stream","OK (visible Chinese-English-Chinese switching with unloaded Core).\n");finish(Activity.RESULT_OK,result);
        } catch(Throwable failure){
            result.putString("stream","FAIL language return flow ("+failure.getClass().getSimpleName()+": "+failure.getMessage()+").\n");
            if(started)try{gracefulStopIsolated();}catch(Exception stopFailure){result.putString("stream",result.getString("stream")+"FAIL graceful cleanup ("+stopFailure.getClass().getSimpleName()+").\n");}
            finish(Activity.RESULT_CANCELED,result);
        } finally {
            if(display!=null){final Activity opening=display;runOnMainSync(()->opening.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));}
        }
    }
    /** Restores the user's preferred English UI through the visible picker. */
    private void languageRestoreEnglish(){
        Bundle result=new Bundle();Activity display=null;
        try {
            android.content.Context context=getTargetContext();NativeRuntime runtime=NativeRuntime.get(context);
            check("com.blocknet.wallet.offlinetest".equals(context.getPackageName())&&interactiveAndUnlocked(),
                "English restoration is unlocked and isolated to OFFLINETEST");
            check(!runtime.alive()&&!MobileCorePolicy.requested(context),"Core is stopped before changing language");
            check(AppLanguage.CHINESE.equals(AppLanguage.choice(context)),"Chinese is currently selected");
            display=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(context.getPackageName(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity opening=display;runOnMainSync(()->opening.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            check(waitForText("语言 / Language",10000),"Chinese language button is visible");
            check(clickText("语言 / Language",5000)&&clickText("继续",5000),"Visible warning opens the language list");
            check(waitForText("英语",5000)&&clickText("英语",5000),"English is selected from the visible list");
            long deadline=SystemClock.elapsedRealtime()+15000;
            while(!AppLanguage.ENGLISH.equals(AppLanguage.choice(context))&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(150);
            check(AppLanguage.ENGLISH.equals(AppLanguage.choice(context))&&waitForText("Language / 语言",10000),
                "English is saved and the recreated activity renders English");
            result.putString("stream","OK (OFFLINETEST restored to visible English through its picker).\n");finish(Activity.RESULT_OK,result);
        } catch(Throwable failure){
            result.putString("stream","FAIL English restoration ("+failure.getClass().getSimpleName()+": "+failure.getMessage()+").\n");finish(Activity.RESULT_CANCELED,result);
        } finally {
            if(display!=null){final Activity opening=display;runOnMainSync(()->opening.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));}
        }
    }
    /** Run after a stopped-sidecar process relaunch; inspect only the language label. */
    private void languagePersistedOnly(){
        Bundle result=new Bundle();
        try {
            android.content.Context context=getTargetContext();NativeRuntime runtime=NativeRuntime.get(context);
            boolean english=arguments!=null&&"true".equals(arguments.getString("expect-english"));
            String expected=english?AppLanguage.ENGLISH:AppLanguage.CHINESE;
            String button=english?"Language / 语言":"语言 / Language";
            check("com.blocknet.wallet.offlinetest".equals(context.getPackageName())&&interactiveAndUnlocked(),
                "Persistence check is unlocked and isolated to OFFLINETEST");
            check(!runtime.alive()&&!MobileCorePolicy.requested(context),"Core remains stopped across app relaunch");
            check(expected.equals(AppLanguage.choice(context)),"Selected language survived app process relaunch");
            startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(context.getPackageName(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            check(waitForText(button,10000),"Relaunched activity visibly renders the selected language");
            result.putString("stream","OK (selected language persisted across app process relaunch).\n");finish(Activity.RESULT_OK,result);
        } catch(Throwable failure){result.putString("stream","FAIL language persistence ("+failure.getClass().getSimpleName()+": "+failure.getMessage()+").\n");finish(Activity.RESULT_CANCELED,result);}
    }
}
