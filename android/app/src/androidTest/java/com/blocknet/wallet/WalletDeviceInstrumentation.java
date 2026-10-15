package com.blocknet.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.view.WindowManager;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Destructive only to this runner's fresh testnet directory; refuses existing testnet data. Never broadcasts. */
public final class WalletDeviceInstrumentation extends Instrumentation {
    private int checks;
    private String stage="preconditions";
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
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
    private void snapshot(File root,File file,Map<String,byte[]> files)throws Exception{
        if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("Cannot preserve config");for(File child:children)snapshot(root,child,files);}else files.put(root.toPath().relativize(file.toPath()).toString(),Files.readAllBytes(file.toPath()));
    }
    @SuppressWarnings("unchecked") private void restorePreferences(SharedPreferences prefs,Map<String,?> saved){
        SharedPreferences.Editor editor=prefs.edit().clear();
        for(Map.Entry<String,?> entry:saved.entrySet()){String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,new HashSet<>((Set<String>)value));}
        if(!editor.commit())throw new IllegalStateException("Cannot restore preferences");
    }
    @Override public void onStart(){
        NativeRuntime runtime=NativeRuntime.get(getTargetContext());WalletRepository repo=new WalletRepository(getTargetContext());WalletApi api=new WalletApi(runtime);
        Bundle result=new Bundle();Activity activity=null;boolean ownsFixture=false;Map<String,?> preferences=new HashMap<>(runtime.prefs.getAll());
        File fixture=new File(runtime.root,"testnet"),mantle=new File(runtime.root,"mantle");boolean mantleExisted=mantle.exists();Map<String,byte[]> mantleFiles=new HashMap<>();
        try{
            check(Os.uname().machine.equals("aarch64"),"Physical device executes native ARM64");
            check(!runtime.alive(),"Existing app node is stopped before isolated test");
            check(runtime.network().equals("mainnet"),"Original app network is mainnet");
            check(!fixture.exists(),"No existing testnet data: isolated disposable directory available");
            check(!runtime.prefs.contains("pending-send.testnet")&&!runtime.prefs.contains("pending-send.mainnet"),"No pending payment can be affected");
            snapshot(mantle,mantle,mantleFiles);ownsFixture=true;
            runtime.prefs.edit().putString("network","testnet").remove("active.testnet").commit();
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity current=activity;runOnMainSync(()->current.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            String prefix="device_"+UUID.randomUUID().toString().replace("-","").substring(0,12);String password="Device-test-"+UUID.randomUUID();
            stage="start actual foreground node";repo.startNode();waitApi(runtime,api);check(runtime.alive(),"App foreground service owns actual ARM core process");
            check(runtime.mantle("version").contains("1.2.0"),"Bundled ARM Mantle version executes inside app sandbox");
            stage="create and recovery phrase";repo.create(prefix,password);String mnemonic=repo.seed(password);String address=ready(repo).address;
            check(mnemonic.split(" ").length==12&&!address.isEmpty(),"Create encrypted wallet and obtain twelve recovery words");
            check(repo.refresh().spendable==0,"Disposable wallet has zero spendable funds");
            stage="lock unlock";repo.lock();check(repo.refresh().locked,"Lock is reflected in native repository snapshot");repo.unlock(password);check(ready(repo).address.equals(address),"Unlock preserves wallet address");
            stage="message signing";String message="Physical Android integration test";String signature=repo.sign(message).getString("signature");check(repo.verify(address,message,signature).optBoolean("valid"),"Physical app signs and verifies a message");
            stage="dry run insufficient funds";boolean rejected=false;try{repo.previewSend(address,"1","");}catch(IOException expected){if(expected.getMessage()==null||!expected.getMessage().toLowerCase(Locale.ROOT).contains("insufficient"))throw expected;rejected=true;}check(rejected&&!repo.hasPendingSend(),"Unfunded payment preview rejects without pending send or broadcast");
            stage="recovery restore";repo.restore(prefix+"_restore",password,mnemonic);check(ready(repo).address.equals(address),"Recovery phrase restore preserves address across scanner-safe restart");
            stage="view-only import";JSONObject keys=api.post("/api/wallet/viewkeys",new JSONObject().put("password",password).put("create_file",false).put("filename",prefix+"_view_export.wallet.dat"));
            repo.importKeys(prefix+"_view",password,keys.getString("spend_pub"),keys.getString("view_priv"),true);WalletRepository.Snapshot watch=ready(repo);check(watch.viewOnly&&watch.address.equals(address),"View-only key import preserves address and spending restriction");
            stage="wallet reopen and core restart";repo.switchWallet(prefix+"_restore",password);check(ready(repo).address.equals(address),"Switching back opens original spend-capable wallet");repo.stopNode();check(!runtime.alive(),"Stopping foreground node terminates owned core");repo.startNode();waitApi(runtime,api);repo.unlock(password);check(ready(repo).address.equals(address),"Stop/restart reopens persisted encrypted wallet");
            stage="encrypted backup export";ByteArrayOutputStream backup=new ByteArrayOutputStream();repo.exportBackup(backup);byte[] encrypted=backup.toByteArray();check(!runtime.alive()&&encrypted.length>0,"Encrypted backup export flushes wallet with core stopped");
            stage="encrypted backup import";repo.importBackup(new ByteArrayInputStream(encrypted),prefix+"_backup");Arrays.fill(encrypted,(byte)0);repo.startNode();waitApi(runtime,api);repo.switchWallet(prefix+"_backup",password);check(ready(repo).address.equals(address),"Imported encrypted backup reopens with same address");
            repo.stopNode();check(!runtime.alive(),"Integration ends with app core stopped");
            result.putString("stream","OK ("+checks+" physical-device integration checks). No funds or broadcast used.\n");
        }catch(Throwable failure){result.putString("stream","FAIL at "+stage+" after "+checks+" checks ("+failure.getClass().getSimpleName()+"). Secret-bearing exception text is intentionally omitted.\n");}
        finally{
            if(ownsFixture){
                try{repo.stopNode();if(runtime.alive())throw new IOException("Core still running");remove(fixture);remove(mantle);if(mantleExisted)mantle.mkdirs();for(Map.Entry<String,byte[]> entry:mantleFiles.entrySet()){File target=new File(mantle,entry.getKey());target.getParentFile().mkdirs();Files.write(target.toPath(),entry.getValue());Os.chmod(target.getAbsolutePath(),0600);}restorePreferences(runtime.prefs,preferences);check(runtime.network().equals("mainnet")&&!runtime.alive(),"Original preferences/config restored; mainnet stopped");}
                catch(Throwable cleanup){result.putString("stream",result.getString("stream","")+"FAIL cleanup: "+cleanup.getClass().getSimpleName()+"; inspect disposable testnet fixture before rerunning.\n");}
            }
            if(activity!=null){final Activity current=activity;runOnMainSync(()->{current.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);current.finish();});}
        }
        if(result.getString("stream","").startsWith("OK")&&!result.getString("stream","").contains("FAIL"))result.putString("stream","OK ("+checks+" physical-device integration checks). No funds or broadcast used.\n");
        finish(result.getString("stream","").startsWith("OK")&&!result.getString("stream","").contains("FAIL")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
