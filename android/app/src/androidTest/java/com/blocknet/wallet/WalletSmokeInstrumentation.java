package com.blocknet.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.EditText;
import android.app.AlertDialog;
import android.text.method.TransformationMethod;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.lang.reflect.Field;
import android.graphics.Paint;
import android.os.Build;
import java.util.Arrays;

/** Native Android instrumentation without a third party test runner. Uses no wallet or funds. */
public final class WalletSmokeInstrumentation extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        checks++;
        Bundle status = new Bundle(); status.putString("stream", "PASS: " + label + "\n"); sendStatus(0, status);
    }
    private String text(View v) {
        StringBuilder b = new StringBuilder(v instanceof TextView ? ((TextView)v).getText().toString() : "");
        if (v instanceof ViewGroup) { ViewGroup group=(ViewGroup)v; for(int i=0;i<group.getChildCount();i++) b.append('\n').append(text(group.getChildAt(i))); }
        return b.toString();
    }
    private boolean allCharactersMasked(CharSequence masked,int length){
        if(masked==null||masked.length()!=length)return false;
        for(int i=0;i<masked.length();i++){char c=masked.charAt(i);if(c!='\u2022'&&c!='\u2027'&&c!='\u25cf'&&c!='*')return false;}
        return true;
    }
    private void secretCheck(boolean passed,String label,List<String> failures){
        if(passed){check(true,label);return;}failures.add(label);Bundle state=new Bundle();state.putString("stream","FAIL: "+label+"\n");sendStatus(0,state);
    }
    private void verifySecretFields(Activity activity){
        List<String> failures=new ArrayList<>();
        runOnMainSync(()->{
            secretCheck(android.provider.Settings.System.getInt(activity.getContentResolver(),android.provider.Settings.System.TEXT_SHOW_PASSWORD,1)==1,"Secret masking regression runs with system SHOW_PASSWORD enabled",failures);
            for(boolean multiline:new boolean[]{false,true}){
                String shape=multiline?"multiline":"single-line";EditText field=new WalletTheme(activity).field("Secret regression field",true,multiline);
                AlertDialog dialog=new AlertDialog.Builder(activity).setView(field).setTitle("Secret masking regression").create();dialog.show();
                try{
                    field.requestFocus();field.setText("OpaqueSecret42");field.setSelection(field.length());
                    TransformationMethod transformation=field.getTransformationMethod();
                    CharSequence liveMasked=transformation==null?null:transformation.getTransformation(field.getText(),field);
                    secretCheck(transformation!=null,"Secret "+shape+" installs a masking transformation",failures);
                    secretCheck(allCharactersMasked(liveMasked,field.length()),"Secret "+shape+" masks every existing character",failures);
                    // Editable insertion triggers the same TextWatcher path as a single typed character.
                    field.getText().append('Z');
                    secretCheck(allCharactersMasked(liveMasked,field.length()),"Secret "+shape+" never exposes the just-typed final character",failures);
                    if(multiline){field.getText().append("\nSecondLine9");secretCheck(allCharactersMasked(liveMasked,field.length()),"Secret multiline masks the complete recovery/key input",failures);}
                    secretCheck(!field.isSaveEnabled(),"Secret "+shape+" is excluded from saved Android view state",failures);
                }finally{field.setText("");dialog.dismiss();}
            }
        });
        if(!failures.isEmpty())throw new AssertionError("Secret field regression failed: "+failures);
    }
    private android.widget.Button suggestionButton(View view,String word){if(view instanceof android.widget.Button&&word.contentEquals(((android.widget.Button)view).getText()))return (android.widget.Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.widget.Button match=suggestionButton(group.getChildAt(i),word);if(match!=null)return match;}}return null;}
    private void verifyMnemonicSuggestions(Activity activity){
        List<String> failures=new ArrayList<>();runOnMainSync(()->{
            android.widget.LinearLayout content=new android.widget.LinearLayout(activity);content.setOrientation(android.widget.LinearLayout.VERTICAL);EditText phrase=new WalletTheme(activity).field("Synthetic recovery phrase",true,true);content.addView(phrase);((MainActivity)activity).installMnemonicSuggestions(content,phrase);
            AlertDialog dialog=new AlertDialog.Builder(activity).setView(content).create();dialog.show();try{phrase.requestFocus();phrase.setText("");phrase.setSelection(0);phrase.getText().append('p');
                android.widget.Button pond=suggestionButton(content,"pond");secretCheck(pond!=null,"Typing first recovery letter p exposes pond among all local matches",failures);if(pond!=null){pond.performClick();secretCheck("pond ".contentEquals(phrase.getText()),"Tapping pond fills the current token and appends a space",failures);}
                phrase.getText().append('a');android.widget.Button abandon=suggestionButton(content,"abandon");secretCheck(abandon!=null,"Next recovery token receives its own local suggestions",failures);if(abandon!=null){abandon.performClick();secretCheck("pond abandon ".contentEquals(phrase.getText()),"Second suggestion preserves the previously completed recovery word",failures);}
                TransformationMethod masking=phrase.getTransformationMethod();secretCheck(masking!=null&&allCharactersMasked(masking.getTransformation(phrase.getText(),phrase),phrase.length()),"Suggested recovery words remain fully masked in the phrase field",failures);
            }finally{phrase.setText("");dialog.dismiss();}
        });if(!failures.isEmpty())throw new AssertionError("Recovery suggestion regression failed: "+failures);
    }
    private void verifyPaymentQr(Activity activity) throws Exception {
        String address=new String(new char[92]).replace('\0','A');StringBuilder memo=new StringBuilder();for(int i=0;i<31;i++)memo.append("🔒");
        String[] payloads={address,"blocknet:$blocknet_wallet?amount=1.25","blocknet:"+address+"?amount=92233720368.54775807&memo="+Uri.encode(memo.toString())};
        String[] labels={"address","shortname payment URI","maximum amount and UTF-8 memo"};
        for(int i=0;i<payloads.length;i++){
            Bitmap image=QrRenderer.render(activity,payloads[i],600);
            try{for(int rotation:new int[]{0,90,180,270}){
                android.graphics.Matrix transform=new android.graphics.Matrix();transform.postRotate(rotation);Bitmap rotated=rotation==0?image:Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),transform,false);
                try{int width=rotated.getWidth(),height=rotated.getHeight();int[] pixels=new int[width*height];rotated.getPixels(pixels,0,width,0,0,width,height);
                    java.util.Map<com.google.zxing.DecodeHintType,Object> hints=new java.util.EnumMap<>(com.google.zxing.DecodeHintType.class);hints.put(com.google.zxing.DecodeHintType.TRY_HARDER,true);hints.put(com.google.zxing.DecodeHintType.CHARACTER_SET,"UTF-8");
                    String decoded=new com.google.zxing.MultiFormatReader().decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(width,height,pixels))),hints).getText();
                    check(payloads[i].equals(decoded),"Branded native QR decodes "+labels[i]+" at "+rotation+" degrees");
                }finally{if(rotated!=image)rotated.recycle();}
            }}finally{image.recycle();}
        }
    }
    private String sha256(File file) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
        StringBuilder out=new StringBuilder(); for(byte b:bytes)out.append(String.format("%02x",b&255)); return out.toString();
    }
    private void capture(Activity activity, String filename, boolean fixture) throws Exception {
        runOnMainSync(()->{
            View view=activity.getWindow().getDecorView();
            Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(image); view.draw(canvas);
            if(fixture){ Paint paint=new Paint();paint.setColor(0xffb7ff00);paint.setTextSize(28);canvas.drawText("DESIGN FIXTURE: SYNTHETIC WALLET DATA",24,image.getHeight()-45,paint); }
            try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),filename))){image.compress(Bitmap.CompressFormat.PNG,100,out);}
            catch(Exception e){throw new RuntimeException(e);}finally{image.recycle();}
        });
    }
    private void fixture(Activity activity, String page) throws Exception {
        WalletRepository.Snapshot state=new WalletRepository.Snapshot();
        state.running=true;state.loaded=!page.equals("onboarding");state.locked=false;state.spendable=1234567890L;state.pending=25000000L;
        state.height=25200;state.walletHeight=1200;state.syncTarget=66304;state.syncPercent="38.0%";state.syncing=true;state.checkpointEntries=662;state.checkpointHeight=66200;state.checkpointFastSync=true;state.peers=8;state.address=new String(new char[92]).replace('\0','A');state.network="mainnet";
        state.status.put("chain_height",1200);
        runOnMainSync(()->{try{
            Field snapshot=MainActivity.class.getDeclaredField("snapshot");snapshot.setAccessible(true);snapshot.set(activity,state);
            Field route=MainActivity.class.getDeclaredField("page");route.setAccessible(true);route.set(activity,page);
            Field payment=MainActivity.class.getDeclaredField("paymentText");payment.setAccessible(true);payment.set(activity,null);
            java.lang.reflect.Method render=MainActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(activity);
        }catch(Exception e){throw new RuntimeException(e);}});
        waitForIdleSync();
    }
    @Override public void onStart() {
        Activity activity=null;
        Bundle result=new Bundle();
        try {
            NativeRuntime runtime=NativeRuntime.get(getTargetContext());
            check(!runtime.alive(), "Test starts with node stopped");
            WalletRepository.Snapshot starting=new WalletRepository.Snapshot(),ready=new WalletRepository.Snapshot();
            starting.running=true;ready.running=true;ready.status.put("chain_height",0);
            check(MainActivity.needsRender(starting,ready),"API readiness redraws starting screen into onboarding");
            check(!MainActivity.needsRender(ready,ready),"Stable API readiness preserves active input views");
            check(MainActivity.needsRender(ready,starting),"API readiness loss redraws startup state");
            org.json.JSONArray oldPage=new org.json.JSONArray().put(new org.json.JSONObject().put("txid","older").put("output_index",0));
            org.json.JSONArray shiftedPage=new org.json.JSONArray().put(new org.json.JSONObject().put("txid","newer").put("output_index",0));
            check(MainActivity.pageIdentityChanged(oldPage,shiftedPage),"New first-page activity invalidates offset pagination cache");
            check(!MainActivity.pageIdentityChanged(oldPage,new org.json.JSONArray(oldPage.toString())),"Unchanged page preserves older activity cache");
            org.json.JSONArray confirmedPage=new org.json.JSONArray(oldPage.toString());confirmedPage.getJSONObject(0).put("chain_state","confirmed").put("confirmations",1);
            check(MainActivity.pageContentChanged(oldPage,confirmedPage),"Changed transaction confirmation data refreshes visible history");
            check(!MainActivity.pageIdentityChanged(oldPage,confirmedPage),"Status updates preserve older pagination boundaries");
            String explorerHash=new String(new char[64]).replace('\0','a');Intent explorer=MainActivity.explorerIntent(explorerHash);
            check(explorer!=null&&Intent.ACTION_VIEW.equals(explorer.getAction())&&("https://explorer.blocknetcrypto.com/tx/"+explorerHash).equals(explorer.getDataString()),"Transaction hash builds the exact HTTPS Blocknet explorer intent");
            for(String invalid:new String[]{"",explorerHash.substring(1),explorerHash+"0",explorerHash+"/other",new String(new char[64]).replace('\0','g')})check(MainActivity.explorerIntent(invalid)==null,"Invalid transaction hash produces no browser intent");
            check(MainActivity.explorerIntent(null)==null,"Missing transaction hash produces no browser intent");
            long generation=runtime.generation();
            File lib=new File(getTargetContext().getApplicationInfo().nativeLibraryDir);
            check(sha256(new File(lib,"libmantle.so")).equals("5587193a22c6221e4b57e45c7944357e2e6992d9b3b8e6ced80c5555c1191a04"), "Installed Mantle bytes match supplied executable");
            String expectedCore="0056d4d86446071525203f738dfdbaaa6e71be48e04f6f222e1067d3c9861143";
            if(BuildConfig.CORE_ACTIVITY_API)try(java.io.InputStream provenance=getTargetContext().getAssets().open("core-activity-build-provenance.json")){
                expectedCore=new org.json.JSONObject(WalletApi.readBounded(provenance,65536)).getString("binary_sha256");
            }
            check(sha256(new File(lib,"libblocknet_core.so")).equals(expectedCore),BuildConfig.CORE_ACTIVITY_API?"Installed activity Core bytes match explicit build provenance":"Installed Core bytes match supplied executable");
            Intent launch=new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=startActivitySync(launch); waitForIdleSync();
            final Activity current=activity;
            verifySecretFields(activity);
            verifyMnemonicSuggestions(activity);
            verifyPaymentQr(activity);
            runOnMainSync(()->{
                check((current.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"Wallet blocks system screenshots");
                String screen=text(current.getWindow().getDecorView());
                check(screen.contains("Your money.") && screen.contains("Start wallet node"),"Native stopped onboarding renders");
                View view=current.getWindow().getDecorView();
                check(view.getWidth()>0&&view.getHeight()>0,"Native interface has measured dimensions");
                Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
                view.draw(new Canvas(image));
                try(FileOutputStream stream=new FileOutputStream(new File(getTargetContext().getFilesDir(),"smoke-onboarding.png"))){ image.compress(Bitmap.CompressFormat.PNG,100,stream); }
                catch(Exception e){throw new RuntimeException(e);} finally {image.recycle();}
            });
            Intent payment=new Intent(Intent.ACTION_VIEW,Uri.parse("blocknet:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA?amount=1")).setClassName(getTargetContext(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            getTargetContext().startActivity(payment); waitForIdleSync();
            check(!runtime.alive()&&runtime.generation()==generation,"Payment intent does not start node or submit payment without confirmation");
            check(!runtime.cookie().exists(),"No authenticated core session was created by payment intent");
            for(String page:new String[]{"overview","receive","settings","onboarding"}){
                fixture(activity,page);
                runOnMainSync(()->{String content=text(current.getWindow().getDecorView());
                    check(content.contains(page.equals("overview")?"Send":page.equals("receive")?"Copy address":page.equals("onboarding")?"Create a wallet":"Export encrypted backup"),"Synthetic "+page+" layout renders controls");
                    if(page.equals("settings"))check(content.contains("Allow mobile data for sync")&&content.contains("Connection:"),"Wallet configuration exposes sync data switch and connection status");
                    if(page.equals("settings"))check(content.contains("Biometric unlock for this wallet"),"Wallet configuration exposes per-wallet biometric unlock setting");
                    if(page.equals("onboarding"))check(content.contains("Restore recovery phrase"),"Wallet setup exposes recovery phrase restore");
                });
                if(!page.equals("receive"))runOnMainSync(()->{
                    String content=text(current.getWindow().getDecorView());check(content.contains("Checkpoints loaded: 662")&&content.contains("25200 / 66304"),"Synthetic "+page+" shows actual checkpoint and sync target fields");
                });
                capture(activity,"fixture-"+page+".png",true);
            }
            if(!android.system.Os.uname().machine.equals("aarch64")){
                getTargetContext().startForegroundService(new Intent(getTargetContext(),CoreService.class).setAction(CoreService.START));
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(!runtime.message().contains("64-bit ARM")&&System.nanoTime()<deadline)Thread.sleep(50);
                check(runtime.message().contains("64-bit ARM")&&!runtime.alive(),"Unsupported ABI service reports ARM64 requirement without running a node");
            }
            result.putString("stream","OK ("+checks+" smoke checks). ARM64 process execution requires an ARM64 device.\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("stream","FAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
