package com.blocknet.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/** Disposable sidecar checks; never opens a wallet or starts Core. */
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
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        phase = arguments == null ? "" : arguments.getString("phase", "");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        try {
            String target = getTargetContext().getPackageName();
            check("com.blocknet.wallet.offlinetest".equals(target), "isolated sidecar package");
            NativeRuntime runtime = NativeRuntime.get(getTargetContext());
            check(!runtime.alive() && !runtime.syncRequested() && !MobileCorePolicy.requested(getTargetContext()),
                "no pre-existing node or sync request");
            if ("prepare".equals(phase) || "verify".equals(phase)) {
                persistentDecision(runtime);
                result.putString("stream", "OK bootstrap persistence " + phase + "\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            check(!BootstrapSyncState.pending(getTargetContext()), "first run has no bootstrap wallet");
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                .setClassName(target, "com.blocknet.wallet.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            final Activity current = activity;
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
            check(Math.abs(((ScrollView)scrollField.get(current)).getScrollY()-100)<=2,
                "network and node transitions preserve the settings viewport");
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
