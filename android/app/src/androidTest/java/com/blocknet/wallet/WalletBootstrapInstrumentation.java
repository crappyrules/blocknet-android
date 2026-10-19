package com.blocknet.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.system.Os;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Sidecar UI/persistence checks and an explicit disposable ARM64 screen-off phase. */
public final class WalletBootstrapInstrumentation extends Instrumentation {
    private static boolean hasExactText(View root,String wanted){
        if(root instanceof TextView&&wanted.equals(((TextView)root).getText().toString()))return true;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)
            if(hasExactText(group.getChildAt(i),wanted))return true;}
        return false;
    }
    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        Bundle status = new Bundle();
        status.putString("stream", "PASS " + label + "\n");
        sendStatus(0, status);
    }

    private String phase;
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments=arguments;
        phase = arguments == null ? "" : arguments.getString("phase", "");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        try {
            String target = getTargetContext().getPackageName();
            check("com.blocknet.wallet.offlinetest".equals(target), "isolated sidecar package");
            PackageManager packages=getTargetContext().getPackageManager();
            check("Blocknet OFFLINETEST".contentEquals(packages.getApplicationLabel(
                packages.getApplicationInfo(target,0)))
                &&packages.getApplicationInfo(target,0).icon==R.mipmap.ic_launcher_offlinetest,
                "sidecar launcher label and icon differ from production");
            Intent payment=new Intent(Intent.ACTION_VIEW,Uri.parse("blocknet:fixture"))
                .addCategory(Intent.CATEGORY_DEFAULT).setPackage(target);
            check(packages.queryIntentActivities(payment,PackageManager.MATCH_DEFAULT_ONLY).isEmpty(),
                "sidecar does not handle production payment links");
            NativeRuntime runtime = NativeRuntime.get(getTargetContext());
            if ("wake-lock-api".equals(phase)) {
                wakeLockApiSelfTest();
                return;
            }
            if ("qa-state-prepare".equals(phase) || "qa-state-verify".equals(phase)) {
                qaStateSelfTest();
                return;
            }
            if ("pause-sidecar-request".equals(phase) || "restore-sidecar-request".equals(phase)) {
                syncRequestMaintenance(runtime);
                return;
            }
            check(!runtime.alive() && !runtime.syncRequested() && !MobileCorePolicy.requested(getTargetContext()),
                "no pre-existing node or sync request");
            if ("prepare".equals(phase) || "verify".equals(phase)) {
                persistentDecision(runtime);
                result.putString("stream", "OK bootstrap persistence " + phase + "\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("screen-off".equals(phase)) {
                screenOffAcceptance(runtime);
                return;
            }
            check(!BootstrapSyncState.pending(getTargetContext()), "first run has no bootstrap wallet");
            activity = startActivitySync(new Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("blocknet:fixture"))
                .setClassName(target, "com.blocknet.wallet.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            final Activity current = activity;
            Field paymentText=MainActivity.class.getDeclaredField("paymentText");
            paymentText.setAccessible(true);
            check(paymentText.get(current)==null,
                "explicit production payment link is ignored by sidecar activity");
            Field snapshot = MainActivity.class.getDeclaredField("snapshot");
            Field page = MainActivity.class.getDeclaredField("page");
            Field scrollField = MainActivity.class.getDeclaredField("pageScroll");
            Method render = MainActivity.class.getDeclaredMethod("render");
            snapshot.setAccessible(true); page.setAccessible(true);
            scrollField.setAccessible(true); render.setAccessible(true);
            WalletRepository.Snapshot fixture = new WalletRepository.Snapshot();
            fixture.running = true; fixture.loaded = true; fixture.locked = false;
            fixture.network = "mainnet"; fixture.height = 1200; fixture.syncTarget = 2000;
            fixture.syncing = true; fixture.peers = 1;
            runOnMainSync(() -> {
                try {
                    snapshot.set(current, fixture);
                    page.set(current, "settings");
                    render.invoke(current);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            waitForIdleSync();
            ScrollView scroll = (ScrollView) scrollField.get(current);
            int maximum = Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
            check(maximum >= 100, "settings page has a measured scroll range");
            runOnMainSync(() -> scroll.scrollTo(0, 100));
            runOnMainSync(() -> {
                try {
                    fixture.height = 1210; fixture.syncTarget = 2000;
                    render.invoke(current);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            waitForIdleSync();
            ScrollView redrawn = (ScrollView) scrollField.get(current);
            check(Math.abs(redrawn.getScrollY() - 100) <= 2,
                "sync progress redraw preserves same-page viewport");
            runOnMainSync(() -> {
                try { fixture.running=false;fixture.syncAllowed=false;render.invoke(current); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            waitForIdleSync();
            ScrollView stopped=(ScrollView)scrollField.get(current);
            int stoppedMaximum=Math.max(0,stopped.getChildAt(0).getHeight()-stopped.getHeight());
            check(stopped.getScrollY()<=stoppedMaximum&&!hasExactText(stopped,"1210"),
                "node loss clamps the stopped view and removes stale settings rows");
            fixture.running=true;fixture.syncAllowed=true;
            fixture.status=new JSONObject().put("chain_height",1200);
            fixture.walletHeight=1100;fixture.peers=8;
            runOnMainSync(() -> {try {page.set(current,"settings");render.invoke(current);}
                catch(Exception error){throw new RuntimeException(error);}});
            waitForIdleSync();
            ScrollView settings=(ScrollView)scrollField.get(current);
            check(hasExactText(settings,"1210")&&hasExactText(settings,"1100")&&hasExactText(settings,"8"),
                "settings starts with synthetic chain, wallet and peer rows");
            Method updateStatus=MainActivity.class.getDeclaredMethod("updateStatus");updateStatus.setAccessible(true);
            ScrollView liveSettings=settings;
            runOnMainSync(() -> liveSettings.scrollTo(0,100));
            int liveScrollY=settings.getScrollY();
            fixture.height=1220;fixture.walletHeight=1120;fixture.peers=9;
            runOnMainSync(() -> {try {updateStatus.invoke(current);}
                catch(Exception error){throw new RuntimeException(error);}});
            waitForIdleSync();
            check(hasExactText(settings,"1220")&&hasExactText(settings,"1120")&&hasExactText(settings,"9")
                &&!hasExactText(settings,"1210")&&!hasExactText(settings,"1100")&&!hasExactText(settings,"8"),
                "normal status refresh updates settings numeric rows without rebuilding the page");
            check(scrollField.get(current)==settings&&Math.abs(settings.getScrollY()-liveScrollY)<=2,
                "live numeric refresh keeps the same settings viewport and scroll position");
            Field touchField=MainActivity.class.getDeclaredField("scrollTouchActive");touchField.setAccessible(true);
            View consumingChild=new View(current);
            AtomicInteger childEvents=new AtomicInteger();
            consumingChild.setOnTouchListener((view,event)->{
                childEvents.incrementAndGet();
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN)
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            });
            runOnMainSync(()->{
                ((ViewGroup)liveSettings.getChildAt(0)).addView(consumingChild,0,
                    new LinearLayout.LayoutParams(-1,200));
                liveSettings.scrollTo(0,0);
            });
            waitForIdleSync();
            boolean[] trackedDown={false};
            runOnMainSync(()->{
                int[] where=new int[2];consumingChild.getLocationInWindow(where);
                long at=SystemClock.uptimeMillis();
                MotionEvent down=MotionEvent.obtain(at,at,MotionEvent.ACTION_DOWN,where[0]+10,where[1]+10,0);
                MotionEvent up=MotionEvent.obtain(at,at+20,MotionEvent.ACTION_UP,where[0]+10,where[1]+10,0);
                try{
                    current.dispatchTouchEvent(down);
                    trackedDown[0]=touchField.getBoolean(current);
                    current.dispatchTouchEvent(up);
                }catch(IllegalAccessException error){throw new RuntimeException(error);}
                finally{down.recycle();up.recycle();}
            });
            check(childEvents.get()==2&&trackedDown[0]&&!touchField.getBoolean(current),
                "child-consumed gesture releases deferred refresh tracking");
            runOnMainSync(() -> {try {render.invoke(current);}
                catch(Exception error){throw new RuntimeException(error);}});
            waitForIdleSync();
            settings=(ScrollView)scrollField.get(current);
            check(hasExactText(settings,"1220")&&hasExactText(settings,"1120")&&hasExactText(settings,"9"),
                "manual redraw reveals the newer settings numeric values");
            fixture.running=false;fixture.syncAllowed=false;fixture.cached=true;
            fixture.savedAt=System.currentTimeMillis();
            runOnMainSync(() -> {try {render.invoke(current);}
                catch(Exception error){throw new RuntimeException(error);}});
            waitForIdleSync();
            ScrollView paused=(ScrollView)scrollField.get(current);
            check(hasExactText(paused,"LAST SAVED WALLET VIEW")&&!hasExactText(paused,"1220")
                &&!hasExactText(paused,"1120")&&!hasExactText(paused,"9"),
                "background pause shows a dated saved view without live numeric rows");
            fixture.cached=false;fixture.locked=true;fixture.running=true;fixture.syncAllowed=true;
            runOnMainSync(() -> {try {render.invoke(current);}
                catch(Exception error){throw new RuntimeException(error);}});
            waitForIdleSync();
            ScrollView locked=(ScrollView)scrollField.get(current);
            check(!hasExactText(locked,"1220")&&!hasExactText(locked,"1120")
                &&!hasExactText(locked,"9")
                &&(current.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,
                "lock removes live settings and keeps screenshot protection");
            fixture.locked=false;fixture.cached=false;fixture.running=true;fixture.syncAllowed=true;
            JSONArray initial=new JSONArray();
            for(int i=0;i<24;i++)initial.put(new JSONObject().put("txid",String.format("%064x",i+1))
                .put("output_index",i).put("amount",100).put("block_height",1000-i));
            fixture.history=initial;
            runOnMainSync(() -> {
                try { snapshot.set(current,fixture);page.set(current,"history");render.invoke(current); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            waitForIdleSync();
            ScrollView history=(ScrollView)scrollField.get(current);
            runOnMainSync(() -> history.scrollTo(0,Math.min(350,history.getChildAt(0).getHeight()-history.getHeight())));
            ViewGroup before=(ViewGroup)history.getChildAt(0);
            View anchor=null;
            for(int i=0;i<before.getChildCount();i++){
                View child=before.getChildAt(i);
                if(child.getTag() instanceof String&&((String)child.getTag()).startsWith("history:")&&
                    child.getBottom()>history.getScrollY()){anchor=child;break;}
            }
            check(anchor!=null,"synthetic history has a visible anchor");
            String key=(String)anchor.getTag();int offset=anchor.getTop()-history.getScrollY();
            JSONArray prepended=new JSONArray();
            prepended.put(new JSONObject().put("txid","f".repeat(64)).put("output_index",99)
                .put("amount",100).put("block_height",2000));
            for(int i=0;i<initial.length();i++)prepended.put(initial.get(i));
            fixture.history=prepended;
            runOnMainSync(() -> {try {render.invoke(current);}catch(Exception error){throw new RuntimeException(error);}});
            waitForIdleSync();
            ScrollView updated=(ScrollView)scrollField.get(current);
            ViewGroup after=(ViewGroup)updated.getChildAt(0);
            View retained=null;
            for(int i=0;i<after.getChildCount();i++)if(key.equals(after.getChildAt(i).getTag()))retained=after.getChildAt(i);
            check(retained!=null&&Math.abs(retained.getTop()-updated.getScrollY()-offset)<=2,
                "new history above viewport preserves the visible transaction anchor");
            check(!runtime.alive() && !runtime.syncRequested(), "UI check did not start Core");
            result.putString("stream", "OK bootstrap UI checks\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL bootstrap UI: " + failure.getClass().getSimpleName() +
                " · " + (failure.getMessage()==null?"":failure.getMessage()) + "\n");
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (activity != null) {
                Activity close = activity;
                runOnMainSync(close::finish);
            }
        }
    }

    private static final String QA_RECEIPT="bootstrap-qa-request";
    private static final String QA_MARKER="qa-owner";

    /** Emulator-only API/permission smoke check; does not open a wallet or start Core. */
    private void wakeLockApiSelfTest() {
        PowerManager power=getTargetContext().getSystemService(PowerManager.class);
        check(power!=null,"PowerManager is available");
        PowerManager.WakeLock nativeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "com.blocknet.wallet:InitialSyncTest");
        nativeLock.setReferenceCounted(false);
        BootstrapWakeLease lease=new BootstrapWakeLease(new BootstrapWakeLease.Lock() {
            @Override public void acquire(long timeoutMs) { nativeLock.acquire(timeoutMs); }
            @Override public void release() { nativeLock.release(); }
            @Override public boolean isHeld() { return nativeLock.isHeld(); }
        });
        try {
            check(lease.update(SystemClock.elapsedRealtime(),true,false)>0&&!nativeLock.isHeld(),
                "interactive background tracks stall without a CPU lock");
            check(lease.update(SystemClock.elapsedRealtime(),true,true)>0&&nativeLock.isHeld(),
                "bounded partial wake lock can be acquired");
            lease.update(SystemClock.elapsedRealtime(),false,false);
            check(!nativeLock.isHeld(),"partial wake lock is released after policy loss");
        } finally { lease.close(); }
        Bundle result=new Bundle();
        result.putString("stream","OK bounded wake-lock API check\n");
        finish(Activity.RESULT_OK,result);
    }

    /** Captures and restores every changed OFFLINETEST preference, even after test process death. */
    private void syncRequestMaintenance(NativeRuntime runtime) throws Exception {
        android.content.Context target=getTargetContext();
        SharedPreferences saved=runtime.prefs;
        SharedPreferences receipt=target.getSharedPreferences(QA_RECEIPT,0);
        boolean restore="restore-sidecar-request".equals(phase);
        if (!restore) {
            check(!runtime.alive()&&!runtime.syncRequested(),
                "sidecar Core is stopped before changing its saved test settings");
            PowerManager power=target.getSystemService(PowerManager.class);
            KeyguardManager keyguard=target.getSystemService(KeyguardManager.class);
            SyncNetworkPolicy.State connection=SyncNetworkPolicy.read(target);
            check(power!=null&&power.isInteractive()&&keyguard!=null&&!keyguard.isKeyguardLocked()
                &&connection.allowed&&connection.wifi&&!connection.cellular,
                "phone is unlocked on validated Wi-Fi before temporary settings change");
            check(!receipt.contains("priorRequest")&&MobileCorePolicy.requested(target)
                &&SyncNetworkPolicy.allowMobileData(target)&&!MobileCorePolicy.enabled(target)
                &&"mainnet".equals(runtime.network())
                &&!new File(runtime.root,"testnet").exists()
                &&!saved.contains("active.testnet")&&!saved.contains("pending-send.testnet")
                &&!saved.contains("wallet-change.testnet"),
                "three original OFFLINETEST preferences and empty testnet state are verified");
            String nonce=UUID.randomUUID().toString();
            check(receipt.edit().putBoolean("priorRequest",true)
                .putBoolean("requestPresent",saved.contains("mobile_core_sync_requested"))
                .putBoolean("priorAllowMobile",true)
                .putBoolean("allowPresent",saved.contains("sync.allow-mobile-data"))
                .putBoolean("priorForegroundOnly",false)
                .putBoolean("foregroundPresent",saved.contains("mobile_core_foreground_only"))
                .putString("priorNetwork",runtime.network())
                .putBoolean("networkPresent",saved.contains("network"))
                .putString("nonce",nonce).commit(),
                "three original preferences and network are saved in a durable test receipt");
            boolean changed=saved.edit().putBoolean("mobile_core_sync_requested",false)
                .putBoolean("sync.allow-mobile-data",false)
                .putBoolean("mobile_core_foreground_only",true).commit();
            if(!changed||MobileCorePolicy.requested(target)||SyncNetworkPolicy.allowMobileData(target)
                ||!MobileCorePolicy.enabled(target)||runtime.alive()) {
                if(restoreRecordedPreferences(saved,receipt,true))receipt.edit().clear().commit();
                throw new IOException("Could not safely prepare temporary OFFLINETEST test settings");
            }
            check(!MobileCorePolicy.requested(target)&&!SyncNetworkPolicy.allowMobileData(target)
                &&MobileCorePolicy.enabled(target)&&!runtime.alive(),
                "only OFFLINETEST test settings were changed");
            Bundle result=new Bundle();
            result.putString("stream","OK OFFLINETEST test settings prepared\n");
            finish(Activity.RESULT_OK,result);
            return;
        }
        check(receipt.getBoolean("priorRequest",false)
            &&receipt.getBoolean("priorAllowMobile",false)
            &&!receipt.getBoolean("priorForegroundOnly",true)
            &&"mainnet".equals(receipt.getString("priorNetwork",""))
            &&receipt.getString("nonce","").length()>20,
            "durable OFFLINETEST preference receipt is available for restoration");
        File fixture=new File(runtime.root,"testnet");
        if(runtime.alive()||runtime.syncRequested()) {
            check("testnet".equals(runtime.network())
                &&ownedFixture(fixture,receipt.getString("nonce","")),
                "only the marked disposable testnet Core may be stopped during restoration");
            new WalletRepository(target).stopNode();
        }
        check(!runtime.alive()&&!runtime.syncRequested(),
            "disposable Core is stopped before original settings return");
        boolean cleanupOk=true;
        if(fixture.exists()) {
            if(ownedFixture(fixture,receipt.getString("nonce",""))) {
                try {
                    String wallet=receipt.getString("ownedWallet","");
                    if(!wallet.isEmpty())removeOwnedCache(target,"testnet/"+wallet);
                    removeOwnedFixture(fixture);
                } catch(Exception cleanupFailure) {cleanupOk=false;}
            } else cleanupOk=false;
        }
        check(restoreRecordedPreferences(saved,receipt,cleanupOk),
            "original OFFLINETEST preferences are durably restored");
        check(MobileCorePolicy.requested(target)&&SyncNetworkPolicy.allowMobileData(target)
            &&!MobileCorePolicy.enabled(target)&&"mainnet".equals(runtime.network())
            &&!runtime.alive()&&!runtime.syncRequested(),
            "all three original OFFLINETEST preferences restored with Core stopped");
        check(cleanupOk,"only marked disposable fixture was removed");
        check(receipt.edit().clear().commit(),"temporary test receipt removed");
        Bundle result=new Bundle();
        result.putString("stream","OK OFFLINETEST test settings restored\n");
        finish(Activity.RESULT_OK,result);
    }

    private static void restoreBoolean(SharedPreferences.Editor edit,String key,SharedPreferences receipt,
                                       String presentKey,String valueKey) {
        if(receipt.getBoolean(presentKey,false))edit.putBoolean(key,receipt.getBoolean(valueKey,false));
        else edit.remove(key);
    }

    private static boolean restoreRecordedPreferences(SharedPreferences saved,SharedPreferences receipt,
                                                      boolean fixtureClean) {
        SharedPreferences.Editor original=saved.edit();
        restoreBoolean(original,"mobile_core_sync_requested",receipt,"requestPresent","priorRequest");
        restoreBoolean(original,"sync.allow-mobile-data",receipt,"allowPresent","priorAllowMobile");
        restoreBoolean(original,"mobile_core_foreground_only",receipt,"foregroundPresent","priorForegroundOnly");
        if(receipt.getBoolean("networkPresent",false))original.putString("network",receipt.getString("priorNetwork","mainnet"));
        else original.remove("network");
        if(fixtureClean) {
            original.remove("active.testnet").remove("port.testnet")
                .remove("wallet-change.testnet").remove("pending-send.testnet");
            String wallet=receipt.getString("ownedWallet","");
            if(!wallet.isEmpty())original.remove("bootstrap-complete.testnet."+wallet);
        }
        return original.commit();
    }

    private static boolean ownedFixture(File fixture,String nonce) {
        if(nonce==null||nonce.length()<20||!fixture.isDirectory()
            ||Files.isSymbolicLink(fixture.toPath()))return false;
        File marker=new File(fixture,QA_MARKER);
        try{return marker.isFile()&&marker.length()<=128&&!Files.isSymbolicLink(marker.toPath())
            &&nonce.equals(new String(Files.readAllBytes(marker.toPath()),StandardCharsets.US_ASCII));}
        catch(Exception unreadable){return false;}
    }

    /** Uses only test-owned preferences/files; verify runs in a second instrumentation process. */
    private void qaStateSelfTest() throws Exception {
        android.content.Context target=getTargetContext();
        SharedPreferences saved=target.getSharedPreferences("bootstrap-qa-state-test",0);
        SharedPreferences receipt=target.getSharedPreferences("bootstrap-qa-receipt-test",0);
        File fixture=new File(target.getCacheDir(),"bootstrap-qa-state-test");
        if("qa-state-prepare".equals(phase)) {
            check(!fixture.exists()&&!receipt.contains("nonce"),
                "QA restoration self-test starts without a prior fixture");
            check(saved.edit().clear().putBoolean("mobile_core_sync_requested",true)
                .putBoolean("sync.allow-mobile-data",false).commit(),
                "QA self-test saved the original key presence and values");
            String nonce=UUID.randomUUID().toString();
            check(receipt.edit().putString("nonce",nonce)
                .putBoolean("requestPresent",true).putBoolean("priorRequest",true)
                .putBoolean("allowPresent",true).putBoolean("priorAllowMobile",false)
                .putBoolean("foregroundPresent",false).putBoolean("priorForegroundOnly",false)
                .putBoolean("networkPresent",false).putString("priorNetwork","mainnet").commit(),
                "QA self-test receipt survived a durable commit");
            NativeRuntime.writePrivate(new File(fixture,QA_MARKER),nonce);
            check(saved.edit().putBoolean("mobile_core_sync_requested",false)
                .putBoolean("sync.allow-mobile-data",true)
                .putBoolean("mobile_core_foreground_only",true)
                .putString("network","testnet").commit(),
                "QA self-test changed its isolated temporary preferences");
            check(ownedFixture(fixture,nonce),"QA self-test fixture has its ownership marker");
        } else {
            String nonce=receipt.getString("nonce","");
            check(nonce.length()>20&&!saved.getBoolean("mobile_core_sync_requested",true)
                &&saved.getBoolean("sync.allow-mobile-data",false)
                &&saved.getBoolean("mobile_core_foreground_only",false)
                &&"testnet".equals(saved.getString("network","")),
                "QA temporary state persisted across instrumentation processes");
            check(!ownedFixture(fixture,UUID.randomUUID().toString())
                &&ownedFixture(fixture,nonce),
                "QA cleanup rejects an unowned marker");
            removeOwnedFixture(fixture);
            check(restoreRecordedPreferences(saved,receipt,true),
                "QA original preferences restored after process restart");
            check(saved.getBoolean("mobile_core_sync_requested",false)
                &&!saved.getBoolean("sync.allow-mobile-data",true)
                &&!saved.contains("mobile_core_foreground_only")&&!saved.contains("network")
                &&!fixture.exists(),
                "QA restoration preserves original values and absent keys");
            check(receipt.edit().clear().commit()&&saved.edit().clear().commit(),
                "QA self-test receipt and preferences removed");
        }
        Bundle result=new Bundle();
        result.putString("stream","OK QA restoration self-test "+phase+"\n");
        finish(Activity.RESULT_OK,result);
    }

    /**
     * Runs only after an owner approves an ARM64 sidecar test. The supplied testnet peer must
     * hold at least two blocks until this phase turns the screen off, then make them available.
     * No address, wallet ID, password, peer address, or cookie is included in test output.
     */
    private void screenOffAcceptance(NativeRuntime runtime) {
        android.content.Context context=getTargetContext();
        WalletRepository repo=new WalletRepository(context);
        WalletApi api=new WalletApi(runtime);
        SharedPreferences saved=runtime.prefs;
        Map<String,?> prior=new HashMap<>(saved.getAll());
        File fixture=new File(runtime.root,"testnet");
        Activity activity=null;
        boolean ownsFixture=false,screenSlept=false;
        String ownedWallet=null;
        String nonce="";
        String stage="preconditions";
        Bundle result=new Bundle();
        try {
            check(BuildConfig.CORE_MOBILE_CLIENT&&BuildConfig.BOOTSTRAP_QA
                &&"aarch64".equals(Os.uname().machine),
                "ARM64 controlled-peer QA Core sidecar is required");
            PowerManager power=context.getSystemService(PowerManager.class);
            KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
            check(power!=null&&power.isInteractive()&&keyguard!=null&&!keyguard.isKeyguardLocked(),
                "device starts interactive and unlocked");
            check("mainnet".equals(runtime.network())&&!fixture.exists()
                &&!saved.contains("active.testnet")&&!saved.contains("pending-send.testnet")
                &&!saved.contains("wallet-change.testnet")
                &&!OfflineSnapshotStore.authorized("mainnet/"+
                    BootstrapSyncState.activeWallet(saved,"mainnet")),
                "no pre-existing sidecar testnet state or active mainnet view is affected");
            check(!SyncNetworkPolicy.allowMobileData(context)&&MobileCorePolicy.enabled(context)
                &&SyncNetworkPolicy.read(context).allowed&&SyncNetworkPolicy.read(context).wifi
                &&!SyncNetworkPolicy.read(context).cellular,
                "foreground-only policy and validated Wi-Fi are active");
            SharedPreferences receipt=context.getSharedPreferences(QA_RECEIPT,0);
            nonce=receipt.getString("nonce","");
            check(receipt.getBoolean("priorRequest",false)&&nonce.length()>20,
                "durable test receipt exists before creating disposable data");
            String peerAddress=arguments==null?"":arguments.getString("peerMultiaddr","");
            String peerID=peerAddress.substring(Math.max(0,peerAddress.lastIndexOf("/p2p/")+5));
            check(peerAddress.matches("^/(ip4|ip6|dns4|dns6)/[^\\s]+/tcp/[0-9]{1,5}/p2p/[A-Za-z0-9]+$")
                &&peerID.length()>20,"a dedicated TCP testnet peer was supplied");
            long targetHeight=Long.parseLong(arguments.getString("targetHeight",""));
            int timeoutSeconds=Integer.parseInt(arguments.getString("timeoutSeconds","900"));
            check(targetHeight>=2&&timeoutSeconds>=60&&timeoutSeconds<=1800,
                "bounded target height and test timeout were supplied");

            // Only this absent testnet directory may be created and later removed.
            ownsFixture=true;
            check(saved.edit().putString("network","testnet").commit(),
                "disposable sidecar testnet selected");
            NativeRuntime.writePrivate(new File(fixture,QA_MARKER),nonce);
            check(ownedFixture(fixture,nonce),"fresh disposable testnet directory is marked for cleanup");
            NativeRuntime.writePrivate(new File(fixture,"data/settings.json"),
                new JSONObject().put("extra_peers",new JSONArray().put(peerAddress)).toString());
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN)
                .setClassName(context.getPackageName(),"com.blocknet.wallet.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            stage="start disposable wallet";
            repo.startNode();
            long deadline=SystemClock.elapsedRealtime()+120000;
            while(SystemClock.elapsedRealtime()<deadline) {
                try { api.get("/api/settings"); break; }
                catch(Exception starting) { SystemClock.sleep(250); }
            }
            check(runtime.alive()&&api.get("/api/settings")!=null,
                "app-private authenticated Core API is ready");
            String name="bootstrap_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
            ownedWallet=name+".wallet.dat";
            check(!cacheFileForScope(context,"testnet/"+ownedWallet).exists(),
                "disposable wallet has no prior saved-view cache");
            check(receipt.edit().putString("ownedWallet",ownedWallet).commit(),
                "disposable wallet scope is saved for process-death cleanup");
            String password="Bootstrap-test-"+UUID.randomUUID();
            repo.create(name,password);
            check(BootstrapSyncState.pending(context),"new disposable wallet requires initial scan");
            JSONObject before=api.get("/api/wallet/bootstrap");
            check(before.optBoolean("wallet_loaded")&&before.optLong("chain_height",-1)>=0
                &&before.optLong("synced_height",-1)>=0
                &&before.optLong("chain_height",targetHeight)<=targetHeight-2,
                "authenticated wallet starts below the held test-peer tip");
            deadline=SystemClock.elapsedRealtime()+30000;
            boolean peerConnected=false;
            while(SystemClock.elapsedRealtime()<deadline&&!peerConnected) {
                JSONArray peers=api.get("/api/peers").optJSONArray("peers");
                if(peers!=null)for(int i=0;i<peers.length();i++)
                    peerConnected|=peerID.equals(peers.getJSONObject(i).optString("peer_id"));
                if(!peerConnected)SystemClock.sleep(250);
            }
            check(peerConnected,"dedicated testnet peer is connected");

            stage="screen-off progress";
            check(context.getPackageName().equals("com.blocknet.wallet.offlinetest")
                &&"testnet".equals(runtime.network()),"all observations remain bound to sidecar testnet");
            screenSlept=true;
            try(android.os.ParcelFileDescriptor ignored=getUiAutomation().executeShellCommand("input keyevent 223")) { }
            deadline=SystemClock.elapsedRealtime()+10000;
            while(SystemClock.elapsedRealtime()<deadline) {
                if(!power.isInteractive()&&!MobileCorePolicy.permitted(context)
                    &&BootstrapSyncState.pending(context)&&MobileCorePolicy.syncPermitted(context))break;
                SystemClock.sleep(100);
            }
            stage="screen-off power";
            check(!power.isInteractive(),"screen transitioned off");
            stage="screen-off foreground callback";
            check(!MobileCorePolicy.permitted(context),"foreground policy applied after screen-off");
            stage="screen-off first-sync state";
            check(BootstrapSyncState.pending(context),"first-sync decision remains pending");
            stage="screen-off sync exception";
            check(MobileCorePolicy.syncPermitted(context),"pending first sync may continue off-screen");
            stage="screen-off baseline";
            JSONObject off=api.get("/api/wallet/bootstrap");
            long offChain=off.optLong("chain_height",-1),offWallet=off.optLong("synced_height",-1);
            check(off.optBoolean("wallet_loaded")&&offChain>=0&&offWallet>=0
                &&offChain<=targetHeight-2,"wallet was observed two blocks below target after screen-off");
            long maxChain=offChain,maxWallet=offWallet;
            deadline=SystemClock.elapsedRealtime()+timeoutSeconds*1000L;
            while(SystemClock.elapsedRealtime()<deadline) {
                stage="unexpected screen wake";
                if(power.isInteractive())throw new AssertionError("screen woke");
                stage="unexpected network selection";
                if(!"testnet".equals(runtime.network()))throw new AssertionError("testnet selection changed");
                stage="validated Wi-Fi loss";
                if(!SyncNetworkPolicy.read(context).allowed)throw new AssertionError("validated Wi-Fi lost");
                stage="off-screen chain and scan polling";
                if(runtime.alive())try {
                    JSONObject progress=api.request("GET","/api/wallet/bootstrap",null,null,2500);
                    if(progress.optBoolean("wallet_loaded")) {
                        maxChain=Math.max(maxChain,progress.optLong("chain_height",-1));
                        maxWallet=Math.max(maxWallet,progress.optLong("synced_height",-1));
                    }
                } catch(Exception stopping) { /* Core may close the API during verified stop. */ }
                if(!runtime.alive()&&!BootstrapSyncState.pending(context))break;
                SystemClock.sleep(1000);
            }
            stage="off-screen chain and scan advancement";
            check(maxChain>offChain&&maxWallet>offWallet,
                "chain and wallet scan both advanced while screen was off");
            stage="verified catch-up stop";
            check(!runtime.alive()&&!BootstrapSyncState.pending(context)
                &&MobileCorePolicy.requested(context),
                "verified catch-up stopped Core and saved completion without clearing sync intent");
            stage="shutdown receipt";
            JSONObject proof=runtime.shutdownProof();
            check(proof!=null&&proof.optLong("chain_height",-1)>=targetHeight,
                "nonce-bound shutdown receipt reached the controlled peer tip");
            deadline=SystemClock.elapsedRealtime()+20000;
            stage="post-catch-up background stop";
            while(SystemClock.elapsedRealtime()<deadline) {
                if(runtime.alive()||power.isInteractive())
                    throw new AssertionError("completed background node restarted before screen wake");
                SystemClock.sleep(1000);
            }
            check(!runtime.alive()&&!power.isInteractive(),
                "completed background node stays stopped with screen off");
            result.putString("stream","OK screen-off bootstrap: package-bound chain/scan advancement and post-catch-up stop\n");
        } catch(Throwable failure) {
            result.putString("stream","FAIL screen-off bootstrap at "+stage+" ("
                +failure.getClass().getSimpleName()+"). Secret-bearing details omitted.\n");
        } finally {
            if(screenSlept)try(android.os.ParcelFileDescriptor ignored=
                getUiAutomation().executeShellCommand("input keyevent 224")) { }
            catch(Throwable wakeFailure) { result.putString("stream",result.getString("stream","")
                +"FAIL screen wake ("+wakeFailure.getClass().getSimpleName()+").\n"); }
            if(activity!=null)try { Activity close=activity;runOnMainSync(close::finish); }
            catch(Throwable closeFailure) { result.putString("stream",result.getString("stream","")
                +"FAIL activity cleanup ("+closeFailure.getClass().getSimpleName()+").\n"); }
            if(ownsFixture)try {
                repo.stopNode();
                if(runtime.alive())throw new IOException("Disposable Core remains active");
                if(fixture.exists()) {
                    if(!ownedFixture(fixture,nonce))throw new IOException("Disposable fixture marker is missing");
                    if(ownedWallet!=null)removeOwnedCache(context,"testnet/"+ownedWallet);
                    removeOwnedFixture(fixture);
                }
                restoreFixturePreferences(saved,prior,ownedWallet);
                if(!"mainnet".equals(runtime.network())||runtime.alive()
                    ||MobileCorePolicy.requested(context))throw new IOException("Sidecar state did not restore");
            } catch(Throwable cleanup) { result.putString("stream",result.getString("stream","")
                +"FAIL disposable cleanup ("+cleanup.getClass().getSimpleName()
                +"). Inspect only the sidecar testnet fixture before retrying.\n"); }
        }
        finish(result.getString("stream","").startsWith("OK")
            &&!result.getString("stream","").contains("FAIL")
            ?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }

    private static void removeOwnedFixture(File file) throws IOException {
        if(Files.isSymbolicLink(file.toPath()))throw new IOException("Disposable fixture contains a symlink");
        if(file.isDirectory()) {
            File[] children=file.listFiles();
            if(children==null)throw new IOException("Cannot list disposable fixture");
            for(File child:children)removeOwnedFixture(child);
        }
        if(file.exists()&&!file.delete())throw new IOException("Cannot remove disposable fixture");
    }
    private static File cacheFileForScope(android.content.Context context,String scope) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
        StringBuilder name=new StringBuilder();
        for(byte b:digest)name.append(String.format(java.util.Locale.US,"%02x",b&255));
        return new File(new File(context.getNoBackupFilesDir(),"wallet-snapshots"),name+".cache");
    }
    private static void removeOwnedCache(android.content.Context context,String scope) throws Exception {
        OfflineSnapshotStore.lock();
        File cache=cacheFileForScope(context,scope);
        for(String suffix:new String[]{"",".bak",".new"}) {
            File file=new File(cache.getPath()+suffix);
            if(file.exists()&&!file.delete())throw new IOException("Cannot remove disposable saved view");
        }
        if(!context.getSharedPreferences("wallet-snapshot-state",0).edit()
            .remove("invalid."+cache.getName()).commit())
            throw new IOException("Cannot clear disposable saved-view state");
    }
    @SuppressWarnings("unchecked") private static void restoreFixturePreferences(
        SharedPreferences prefs,Map<String,?> before,String wallet)
        throws IOException {
        SharedPreferences.Editor edit=prefs.edit();
        String[] keys={"network","active.testnet","port.testnet","mobile_core_sync_requested",
            "wallet-change.testnet","pending-send.testnet",
            wallet==null?"":("bootstrap-complete.testnet."+wallet)};
        for(String key:keys) {
            if(key.isEmpty())continue;
            Object value=before.get(key);
            if(value==null){edit.remove(key);continue;}
            if(value instanceof String)edit.putString(key,(String)value);
            else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)edit.putInt(key,(Integer)value);
            else if(value instanceof Long)edit.putLong(key,(Long)value);
            else if(value instanceof Float)edit.putFloat(key,(Float)value);
            else if(value instanceof java.util.Set)edit.putStringSet(key,new HashSet<>((java.util.Set<String>)value));
        }
        if(!edit.commit())throw new IOException("Could not restore sidecar preferences");
    }

    private void persistentDecision(NativeRuntime runtime) throws Exception {
        SharedPreferences saved = getTargetContext().getSharedPreferences("wallet", 0);
        String network = runtime.network();
        String wallet = "bootstrap-disposable.wallet.dat";
        File file = new File(runtime.walletsDir(), wallet);
        if ("prepare".equals(phase)) {
            check(!file.exists(), "disposable wallet path is unused");
            String prior = saved.getString("active." + network, "main.wallet.dat");
            check(saved.edit().putString("bootstrap-test-prior", prior)
                .putString("active." + network, wallet).commit(), "fixture selection saved");
            check(file.createNewFile(), "disposable file created");
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){output.write(new byte[]{1,2,3});}
            BootstrapSyncState.begin(getTargetContext(), network, wallet);
            check(BootstrapSyncState.pending(getTargetContext()), "first scan decision persisted");
            return;
        }
        check(wallet.equals(BootstrapSyncState.activeWallet(saved, network)) && file.isFile(),
            "new instrumentation process reopened disposable state");
        check(BootstrapSyncState.pending(getTargetContext()), "pending scan survived process restart");
        try {
            MobileCorePolicy.setRequested(getTargetContext(), true);
            runtime.setSyncRequested(true);
            check(BootstrapSyncState.completeAfterStopped(getTargetContext(), network, wallet,
                "a".repeat(64)), "stopped and saved completion commits");
            check(!BootstrapSyncState.pending(getTargetContext()), "completed wallet returns to pause policy");
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(file,true)){output.write(4);}
            check(BootstrapSyncState.pending(getTargetContext()), "same-name replacement invalidates completion");
            BootstrapSyncState.begin(getTargetContext(), network, wallet);
            check(BootstrapSyncState.pending(getTargetContext()), "restoring same wallet clears completion");
        } finally {
            runtime.setSyncRequested(false);
            MobileCorePolicy.setRequested(getTargetContext(), false);
            BootstrapSyncState.begin(getTargetContext(), network, wallet);
            if (!file.delete()) throw new AssertionError("disposable file cleanup failed");
            String prior = saved.getString("bootstrap-test-prior", "main.wallet.dat");
            if (!saved.edit().putString("active." + network, prior)
                .remove("bootstrap-test-prior").commit())
                throw new AssertionError("disposable preference cleanup failed");
        }
        check(!runtime.alive() && !runtime.syncRequested(), "persistence checks did not start Core");
    }
}
