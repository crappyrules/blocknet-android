package com.blocknet.wallet;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Emulator-only acceptance of real Camera API frames, resource lifecycle and activity results.
 * Modes: camera (permission already granted), denied (permission denied), payment (virtual-scene QR).
 * The payment mode needs an externally configured virtual-scene QR and expectedPayment argument.
 * No node, wallet file, account, or transaction is created or opened by this runner.
 */
@SuppressWarnings("deprecation")
public final class WalletCameraInstrumentation extends Instrumentation {
    private String mode="camera",expectedPayment;
    private int checks;
    interface Probe { boolean ready() throws Exception; }
    @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null){mode=args.getString("mode","camera");expectedPayment=args.getString("expectedPayment");}start();}
    private void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;Bundle out=new Bundle();out.putString("stream","PASS: "+label+"\n");sendStatus(0,out);}
    private void await(String label,long timeout,Probe probe) throws Exception {long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(probe.ready()){check(true,label);return;}SystemClock.sleep(60);}throw new AssertionError(label+" timed out");}
    private Object field(Object target,String name) throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private <T extends View> T find(View view,Class<T> type,String text){if(type.isInstance(view)&&view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return type.cast(view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){T match=find(group.getChildAt(i),type,text);if(match!=null)return match;}}return null;}
    private String visibleText(Activity activity){StringBuilder out=new StringBuilder();runOnMainSync(()->appendText(activity.getWindow().getDecorView(),out));return out.toString();}
    private void appendText(View view,StringBuilder out){if(view instanceof TextView)out.append(((TextView)view).getText()).append('\n');if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)appendText(group.getChildAt(i),out);}}
    private void unlockedFixture(Activity activity) throws Exception {WalletRepository.Snapshot state=new WalletRepository.Snapshot();state.running=true;state.loaded=true;state.address=new String(new char[92]).replace('\0','A');state.status.put("chain_height",0);runOnMainSync(()->{try{Field f=MainActivity.class.getDeclaredField("snapshot");f.setAccessible(true);f.set(activity,state);Method render=MainActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});}
    private ScannerActivity openScanner(MainActivity parent) throws Exception {
        await("Wallet is resumed and initial refresh is complete",10000,()->(Boolean)field(parent,"resumed")&&!(Boolean)field(parent,"refreshInFlight"));
        unlockedFixture(parent);ActivityMonitor monitor=addMonitor(ScannerActivity.class.getName(),null,false);
        runOnMainSync(()->{try{Method send=MainActivity.class.getDeclaredMethod("showSend");send.setAccessible(true);send.invoke(parent);((EditText)field(parent,"sendAddress")).setText("synthetic-original-recipient");((EditText)field(parent,"sendAmount")).setText("1.25");Button scan;
            // The scan action belongs to the native dialog, rather than the activity decor.
            android.app.AlertDialog dialog=(android.app.AlertDialog)field(parent,"sendDialog");scan=find(dialog.getWindow().getDecorView(),Button.class,"Scan payment QR");if(scan==null)throw new AssertionError("Scan button missing");scan.performClick();}catch(Exception e){throw new RuntimeException(e);}});
        Activity scanner=waitForMonitorWithTimeout(monitor,10000);removeMonitor(monitor);check(scanner instanceof ScannerActivity,"Send form launches native scanner for an activity result");return (ScannerActivity)scanner;
    }
    private long cameraTimestamp(ScannerActivity activity){AtomicLong value=new AtomicLong();runOnMainSync(()->{try{TextureView texture=(TextureView)field(activity,"preview");if(texture.isAvailable()&&texture.getSurfaceTexture()!=null)value.set(texture.getSurfaceTexture().getTimestamp());}catch(Exception e){throw new RuntimeException(e);}});return value.get();}
    private boolean cameraFrameVisible(ScannerActivity activity){AtomicReference<Boolean> result=new AtomicReference<>(false);runOnMainSync(()->{try{TextureView texture=(TextureView)field(activity,"preview");Bitmap frame=texture.isAvailable()?texture.getBitmap(64,64):null;if(frame==null)return;try{int first=frame.getPixel(0,0);for(int y=0;y<64;y++)for(int x=0;x<64;x++)if(frame.getPixel(x,y)!=first){result.set(true);return;}}finally{frame.recycle();}}catch(Exception e){throw new RuntimeException(e);}});return result.get();}
    private boolean paymentFormMatches(MainActivity host,PaymentRequest request){AtomicReference<Boolean> matched=new AtomicReference<>(false);runOnMainSync(()->{try{EditText address=(EditText)field(host,"sendAddress"),amount=(EditText)field(host,"sendAmount"),memo=(EditText)field(host,"sendMemo");matched.set(address!=null&&amount!=null&&memo!=null&&request.address.contentEquals(address.getText())&&request.amount.contentEquals(amount.getText())&&request.memo.contentEquals(memo.getText()));}catch(Exception unavailable){matched.set(false);}});return matched.get();}
    private boolean cameraPaymentReturned(MainActivity host,ScannerActivity camera,PaymentRequest request){AtomicReference<Boolean> returned=new AtomicReference<>(false);runOnMainSync(()->{try{returned.set(camera.isFinishing()&&expectedPayment.equals(field(host,"paymentText")));}catch(Exception unavailable){returned.set(false);}});return returned.get()||(camera.isFinishing()&&paymentFormMatches(host,request));}
    private void cameraPaymentDiagnostics(MainActivity host,ScannerActivity camera,PaymentRequest request){
        StringBuilder message=new StringBuilder("CAMERA TIMEOUT DIAGNOSTICS: ");runOnMainSync(()->{try{
            message.append("scannerCompleted=").append(field(camera,"completed")).append(" scannerFinishing=").append(camera.isFinishing()).append(" scannerResumed=").append(field(camera,"resumed")).append(" cameraOwned=").append(field(camera,"camera")!=null).append(" parentResumed=").append(field(host,"resumed")).append(" parentDestroyed=").append(host.isDestroyed());
            Object pending=field(host,"paymentText");message.append(" pendingPaymentPresent=").append(pending!=null).append(" pendingPaymentMatches=").append(expectedPayment.equals(pending));
            TextureView texture=(TextureView)field(camera,"preview");message.append(" textureAvailable=").append(texture.isAvailable());Bitmap frame=texture.isAvailable()?texture.getBitmap():null;
            if(frame!=null)try{java.io.File destination=new java.io.File(getTargetContext().getFilesDir(),"camera-acceptance-timeout-preview.png");try(java.io.FileOutputStream stream=new java.io.FileOutputStream(destination)){message.append(" frameSaved=").append(frame.compress(Bitmap.CompressFormat.PNG,100,stream));}message.append(" frameWidth=").append(frame.getWidth()).append(" frameHeight=").append(frame.getHeight()).append(" frameFile=").append(destination.getAbsolutePath());}finally{frame.recycle();}
            else message.append(" frameUnavailable=true");
        }catch(Exception diagnosticFailure){message.append(" diagnosticFailureType=").append(diagnosticFailure.getClass().getSimpleName());}});
        message.append(" formMatches=").append(paymentFormMatches(host,request));Bundle status=new Bundle();status.putString("stream",message+"\n");sendStatus(0,status);
    }
    private int rearCamera(){Camera.CameraInfo info=new Camera.CameraInfo();for(int id=0;id<Camera.getNumberOfCameras();id++){Camera.getCameraInfo(id,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK)return id;}return -1;}
    private boolean cameraAvailableToAnotherOwner(){Camera external=null;try{int id=rearCamera();if(id<0)return false;external=Camera.open(id);return external!=null;}catch(RuntimeException busy){return false;}finally{if(external!=null)external.release();}}
    private boolean denyPermissionDialog(){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;try{for(String pkg:new String[]{"com.android.permissioncontroller","com.google.android.permissioncontroller"})for(String name:new String[]{"permission_deny_button","permission_deny_and_dont_ask_again_button"}){List<AccessibilityNodeInfo> buttons=root.findAccessibilityNodeInfosByViewId(pkg+":id/"+name);for(AccessibilityNodeInfo button:buttons)if(button.isVisibleToUser()&&button.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;}return false;}finally{root.recycle();}}
    @Override public void onStart(){MainActivity parent=null;ScannerActivity scanner=null;Bundle result=new Bundle();try{
        check(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish")||Build.FINGERPRINT.contains("emulator"),"Camera acceptance is restricted to an emulator");
        NativeRuntime runtime=NativeRuntime.get(getTargetContext());check(!runtime.alive(),"Camera test starts without a running node");long generation=runtime.generation();
        boolean granted=getTargetContext().checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
        check(mode.equals("denied")?!granted:granted,"Camera permission matches the explicit test mode");
        parent=(MainActivity)startActivitySync(new Intent(Intent.ACTION_MAIN).setClass(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();SystemClock.sleep(250);
        scanner=openScanner(parent);final MainActivity host=parent;final ScannerActivity camera=scanner;
        if(mode.equals("denied")){
            await("Android camera permission can be denied",10000,()->visibleText(camera).contains("Camera access is needed")||denyPermissionDialog());
            await("Scanner offers permission recovery after denial",5000,()->visibleText(camera).contains("Camera access is needed"));
            runOnMainSync(()->{try{Button retry=(Button)field(camera,"retry");check(retry.getVisibility()==View.VISIBLE,"Denied scanner exposes Enable camera action");}catch(Exception e){throw new RuntimeException(e);}});
        }else if(mode.equals("payment")){
            check(expectedPayment!=null&&!expectedPayment.isEmpty(),"Virtual-scene payment fixture was specified");PaymentRequest request=PaymentRequest.parse(expectedPayment);
            try{await("Actual camera QR result survives scanner return",45000,()->cameraPaymentReturned(host,camera,request));}catch(AssertionError timeout){cameraPaymentDiagnostics(host,camera,request);throw timeout;}
            // Restore the synthetic unlocked view after return; no core is present in this isolated test.
            if(!paymentFormMatches(host,request)){await("Wallet resumes before applying preserved camera result",5000,()->(Boolean)field(host,"resumed")&&!(Boolean)field(host,"refreshInFlight"));unlockedFixture(host);waitForIdleSync();}await("Camera payment prefills recipient, amount and memo for review",5000,()->paymentFormMatches(host,request));
            check(request.amount.contentEquals(((EditText)field(host,"sendAmount")).getText()),"Camera payment prefills requested amount without sending");check(request.memo.contentEquals(((EditText)field(host,"sendMemo")).getText()),"Camera payment prefills memo without sending");
        }else{
            await("Actual emulator camera produces preview frames",10000,()->cameraTimestamp(camera)>0&&cameraFrameVisible(camera));long first=cameraTimestamp(camera);
            runOnMainSync(()->callActivityOnPause(camera));await("Pausing releases camera for another Camera API owner",10000,this::cameraAvailableToAnotherOwner);
            runOnMainSync(()->callActivityOnResume(camera));await("Resuming reacquires camera and delivers new frames",10000,()->cameraTimestamp(camera)>first&&cameraFrameVisible(camera));
        }
        if(!camera.isFinishing()){runOnMainSync(()->{Button cancel=find(camera.getWindow().getDecorView(),Button.class,"Cancel");if(cancel==null)throw new AssertionError("Scanner cancel action missing");cancel.performClick();});await("Scanner cancel returns to the wallet",5000,()->camera.isFinishing());}
        if(!mode.equals("payment")){check("synthetic-original-recipient".contentEquals(((EditText)field(host,"sendAddress")).getText()),"Cancel preserves the original send recipient");check("1.25".contentEquals(((EditText)field(host,"sendAmount")).getText()),"Cancel preserves the original amount");}
        check(!runtime.alive()&&runtime.generation()==generation,"Camera acceptance never starts a node or submits a payment");result.putString("stream","OK ("+checks+" camera acceptance checks; mode="+mode+").\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream","FAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}finally{final Activity child=scanner,activity=parent;runOnMainSync(()->{if(child!=null&&!child.isFinishing())child.finish();if(activity!=null&&!activity.isFinishing())activity.finish();});}}
}
