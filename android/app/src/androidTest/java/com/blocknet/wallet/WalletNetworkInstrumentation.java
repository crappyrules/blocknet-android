package com.blocknet.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.view.WindowManager;
import java.io.*;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.util.function.BooleanSupplier;

/** Emulator-only live radio/service policy acceptance; never starts a supported ARM core. */
public final class WalletNetworkInstrumentation extends Instrumentation {
    private int checks;private String stage="preconditions";
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;Bundle b=new Bundle();b.putString("stream","PASS: "+label+"\n");sendStatus(0,b);}
    private String shell(String command)throws Exception {
        try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);InputStream in=new FileInputStream(fd.getFileDescriptor());ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] bytes=new byte[1024];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>8192)throw new IOException("Unexpected shell output");out.write(bytes,0,n);}return out.toString("UTF-8").trim();
        }
    }
    private void await(BooleanSupplier condition,long timeout,String label)throws Exception {
        long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;Thread.sleep(50);}throw new IOException(label);
    }
    @Override public void onStart(){
        NativeRuntime runtime=NativeRuntime.get(getTargetContext());WalletRepository repo=new WalletRepository(getTargetContext());Bundle result=new Bundle();Activity activity=null;boolean success=false,ownsFixture=false;
        ConnectivityManager connectivity=getTargetContext().getSystemService(ConnectivityManager.class);AtomicBoolean wifiReturnObserved=new AtomicBoolean();boolean callbackRegistered=false;
        ConnectivityManager.NetworkCallback observer=new ConnectivityManager.NetworkCallback(){@Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))wifiReturnObserved.set(true);}};
        String initialWifi=null,initialMobile=null;Map<String,?> prefs=runtime.prefs.getAll();byte[] cookie=null,pid=null;boolean cookieExisted=runtime.cookie().exists(),pidExisted=runtime.pidFile().exists();
        try {
            check((android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"))&&Os.uname().machine.equals("x86_64"),"Live radio test is restricted to an x86 Android emulator");
            check(runtime.network().equals("mainnet")&&!runtime.alive()&&!runtime.syncRequested(),"Mainnet selected with no existing node or requested session");
            check(!repo.hasPendingSend(),"No pending payment is affected");
            initialWifi=shell("settings get global wifi_on");initialMobile=shell("settings get global mobile_data");
            check((initialWifi.equals("0")||initialWifi.equals("1"))&&(initialMobile.equals("0")||initialMobile.equals("1")),"Original Wi-Fi and mobile settings are known for exact restoration");
            if(cookieExisted)cookie=Files.readAllBytes(runtime.cookie().toPath());if(pidExisted)pid=Files.readAllBytes(runtime.pidFile().toPath());ownsFixture=true;
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity shown=activity;runOnMainSync(()->shown.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            connectivity.registerDefaultNetworkCallback(observer);callbackRegistered=true;
            stage="validated mobile default connection";
            SyncNetworkPolicy.setAllowMobileData(getTargetContext(),false);
            if(initialMobile.equals("0"))shell("svc data enable");
            shell("svc wifi disable");
            await(()->{SyncNetworkPolicy.State s=SyncNetworkPolicy.read(getTargetContext());return s.connected&&s.cellular&&!s.wifi&&!s.allowed;},30000,"No validated mobile default network");
            check(!SyncNetworkPolicy.read(getTargetContext()).allowed,"Wi-Fi-only policy blocks the live validated mobile connection");
            stage="start request remains paused";
            long generation=runtime.generation();repo.startNode();
            await(()->runtime.syncRequested()&&!runtime.alive(),5000,"Paused start did not retain requested session");Thread.sleep(500);
            check(runtime.syncRequested()&&!runtime.alive()&&runtime.generation()==generation,"Blocked START retains requested session without spawning any native child");
            stage="manual stop clears request";
            repo.stopNode();await(()->!runtime.syncRequested()&&!runtime.alive(),5000,"Manual stop did not clear requested session");Thread.sleep(500);
            check(!runtime.syncRequested(),"Manual Stop clears the paused sync request");
            stage="live mobile-data opt-in resumes paused request";
            repo.startNode();await(runtime::syncRequested,5000,"Mobile opt-in fixture START was not retained");
            check(!SyncNetworkPolicy.read(getTargetContext()).allowed&&runtime.syncRequested(),"Mobile opt-in begins with the same blocked live mobile connection");
            repo.setAllowMobileData(true);
            await(()->!runtime.syncRequested()&&runtime.message()!=null&&runtime.message().contains("64-bit ARM"),20000,"Mobile-data preference change did not attempt native resume");
            SyncNetworkPolicy.State mobileAllowed=SyncNetworkPolicy.read(getTargetContext());
            check(mobileAllowed.allowed&&mobileAllowed.cellular&&!mobileAllowed.wifi&&!runtime.alive()&&runtime.generation()==generation,"Enabling mobile data resumes the paused request on unchanged mobile transport without native execution");
            repo.stopNode();repo.setAllowMobileData(false);
            await(()->!runtime.syncRequested()&&!runtime.alive()&&!SyncNetworkPolicy.read(getTargetContext()).allowed,5000,"Could not restore Wi-Fi-only paused preconditions");
            stage="connection callback resume";
            repo.startNode();await(runtime::syncRequested,5000,"Second paused START was not retained");
            check(runtime.syncRequested()&&!runtime.alive()&&runtime.generation()==generation,"Second START remains paused while mobile is the default connection");
            wifiReturnObserved.set(false);shell("svc wifi enable");
            await(()->{SyncNetworkPolicy.State s=SyncNetworkPolicy.read(getTargetContext());return s.allowed&&s.wifi&&!s.cellular;},30000,"Wi-Fi did not become the validated default connection");
            await(()->!runtime.syncRequested()&&runtime.message()!=null&&runtime.message().contains("64-bit ARM"),20000,"Connection observer did not attempt native resume");
            check(wifiReturnObserved.get(),"Android default-network callback reports validated Wi-Fi return");
            check(!runtime.alive()&&runtime.generation()==generation,"Unsupported emulator ABI prevents native execution after automatic resume attempt");
            success=true;
        }catch(Throwable failure){result.putString("stream","FAIL at "+stage+" after "+checks+" network checks ("+failure.getClass().getSimpleName()+").\n");}
        finally {
            if(callbackRegistered)try{connectivity.unregisterNetworkCallback(observer);}catch(Exception ignored){}
            if(ownsFixture)try {
                repo.stopNode();
                if(cookieExisted){Files.write(runtime.cookie().toPath(),cookie);Os.chmod(runtime.cookie().getPath(),0600);}else Files.deleteIfExists(runtime.cookie().toPath());
                if(pidExisted){Files.write(runtime.pidFile().toPath(),pid);Os.chmod(runtime.pidFile().getPath(),0600);}else Files.deleteIfExists(runtime.pidFile().toPath());
                SharedPreferences.Editor edit=runtime.prefs.edit();Object allow=prefs.get("sync.allow-mobile-data");if(allow==null)edit.remove("sync.allow-mobile-data");else edit.putBoolean("sync.allow-mobile-data",(Boolean)allow);if(!edit.commit())throw new IOException("Could not restore policy setting");
                shell(initialWifi.equals("1")?"svc wifi enable":"svc wifi disable");
                if(initialMobile.equals("0"))shell("svc data disable");
                await(()->!runtime.syncRequested()&&!runtime.alive(),3000,"Cleanup left a requested session");
                check(shell("settings get global wifi_on").equals(initialWifi)&&shell("settings get global mobile_data").equals(initialMobile),"Original Wi-Fi/mobile settings restored exactly");
                check(!runtime.syncRequested()&&!runtime.alive(),"Cleanup leaves service stopped and no native child");
            }catch(Throwable cleanup){success=false;result.putString("stream",result.getString("stream","")+"FAIL cleanup ("+cleanup.getClass().getSimpleName()+"). Restore emulator radios before continuing.\n");}
            if(activity!=null){final Activity shown=activity;runOnMainSync(()->{shown.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);shown.finish();});}
        }
        if(success)result.putString("stream","OK ("+checks+" live network policy checks). Emulator only; no wallets or native core used.\n");finish(success?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
