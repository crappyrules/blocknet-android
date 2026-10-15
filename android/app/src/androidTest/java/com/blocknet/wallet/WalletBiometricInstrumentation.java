package com.blocknet.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import android.app.Instrumentation;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import javax.crypto.Cipher;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Emulator-only actual Android Keystore/BiometricPrompt acceptance. Never starts a node or opens a wallet. */
@android.annotation.TargetApi(30)
public final class WalletBiometricInstrumentation extends Instrumentation {
    private int checks;
    private volatile CountDownLatch stopped,resumed;
    @Override public void callActivityOnStop(Activity activity){super.callActivityOnStop(activity);if(activity instanceof MainActivity&&stopped!=null)stopped.countDown();}
    @Override public void callActivityOnResume(Activity activity){super.callActivityOnResume(activity);if(activity instanceof MainActivity&&resumed!=null)resumed.countDown();}
    private EditText passwordField(View view){
        if(view instanceof EditText)return (EditText)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText match=passwordField(group.getChildAt(i));if(match!=null)return match;}}
        return null;
    }
    @SuppressWarnings("unchecked") private void enrollmentDialogBackgroundRegression(Activity activity)throws Exception{
        AtomicReference<AlertDialog> heldDialog=new AtomicReference<>();AtomicReference<EditText> heldPassword=new AtomicReference<>();
        runOnMainSync(()->{try{
            Field registry=MainActivity.class.getDeclaredField("openDialogs");registry.setAccessible(true);
            Set<AlertDialog> open=(Set<AlertDialog>)registry.get(activity);Set<AlertDialog> before=new HashSet<>(open);
            Method begin=MainActivity.class.getDeclaredMethod("beginBiometricEnrollment");begin.setAccessible(true);begin.invoke(activity);
            for(AlertDialog dialog:open)if(!before.contains(dialog)){heldDialog.set(dialog);break;}
            check(heldDialog.get()!=null&&heldDialog.get().isShowing(),"Biometric enrollment password dialog opens without submitting credentials");
            EditText password=passwordField(heldDialog.get().getWindow().getDecorView());check(password!=null,"Enrollment dialog contains its password input");password.setText("Synthetic-dialog-privacy-test");heldPassword.set(password);
            Field sensitive=MainActivity.class.getDeclaredField("sensitiveDialogs");sensitive.setAccessible(true);check(((Set<AlertDialog>)sensitive.get(activity)).contains(heldDialog.get()),"Enrollment dialog is registered as sensitive");
        }catch(Exception failure){throw new RuntimeException(failure);}});
        stopped=new CountDownLatch(1);
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        if(!stopped.await(10,TimeUnit.SECONDS))throw new AssertionError("Activity did not stop after Home");
        runOnMainSync(()->{check(!heldDialog.get().isShowing(),"Actual backgrounding dismisses biometric enrollment password dialog");check(heldPassword.get().length()==0,"Actual backgrounding wipes the held password input");});
        resumed=new CountDownLatch(1);
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        if(!resumed.await(10,TimeUnit.SECONDS))throw new AssertionError("Activity did not resume before crypto test");
        waitForIdleSync();stopped=null;resumed=null;
    }
    private String stage="preconditions";
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void status(String message){Bundle update=new Bundle();update.putString("stream",message+"\n");sendStatus(0,update);}
    private void check(boolean allowed,String message){if(!allowed)throw new AssertionError(message);checks++;status("PASS: "+message);}
    private Cipher authenticate(Activity activity,Cipher cipher,String label,boolean cancel)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Cipher> authenticated=new AtomicReference<>();AtomicInteger error=new AtomicInteger(0);CancellationSignal cancellation=new CancellationSignal();
        runOnMainSync(()->{
            BiometricPrompt prompt=new BiometricPrompt.Builder(activity).setTitle("Biometric vault acceptance")
                .setSubtitle(label+" · synthetic emulator secret")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Cancel",activity.getMainExecutor(),(dialog,which)->{error.set(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED);done.countDown();}).build();
            prompt.authenticate(new BiometricPrompt.CryptoObject(cipher),cancellation,activity.getMainExecutor(),new BiometricPrompt.AuthenticationCallback(){
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){BiometricPrompt.CryptoObject crypto=result.getCryptoObject();if(crypto!=null)authenticated.set(crypto.getCipher());done.countDown();}
                @Override public void onAuthenticationError(int code,CharSequence message){error.set(code);done.countDown();}
            });
            if(cancel)new Handler(Looper.getMainLooper()).postDelayed(cancellation::cancel,1000);
        });
        status(cancel?"STAGE: AWAIT_CANCELLATION_AUTOMATIC (do not inject a fingerprint)":"STAGE: "+(label.equals("encrypt")?"AWAIT_ENROLLMENT":"AWAIT_UNLOCK")+" (host inject emulator fingerprint 1)");
        if(!done.await(120,TimeUnit.SECONDS)){cancellation.cancel();throw new AssertionError("Biometric prompt timeout");}
        if(cancel){check(authenticated.get()==null&&error.get()!=0,"Real biometric prompt cancellation does not return authenticated crypto");return null;}
        check(error.get()==0&&authenticated.get()==cipher,"Biometric success returns the exact Keystore CryptoObject for "+label);
        return authenticated.get();
    }
    @Override public void onStart(){
        Bundle result=new Bundle();Activity activity=null;BiometricVault vault=null;BiometricVault.Enrollment pending=null;
        String scope="bio_test_"+UUID.randomUUID().toString().replace("-","").substring(0,16)+".wallet.dat";
        String other="bio_other_"+UUID.randomUUID().toString().replace("-","").substring(0,16)+".wallet.dat";
        char[] secret=("Synthetic-biometric-"+UUID.randomUUID()).toCharArray(),expected=secret.clone(),unlocked=null;
        try{
            check(Build.VERSION.SDK_INT>=30&&(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish")),"Disposable emulator with native strong-biometric APIs");
            vault=new BiometricVault(getTargetContext());check(vault.available(),"Strong biometrics enrolled in emulator");
            check(!vault.isEnabled("mainnet",scope)&&!vault.isEnabled("mainnet",other),"Unique synthetic wallet scopes have no prior enrollment");
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),"com.blocknet.wallet.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity current=activity;runOnMainSync(()->current.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            stage="enrollment dialog background privacy";enrollmentDialogBackgroundRegression(activity);
            stage="authenticated enrollment";pending=vault.prepareEnrollment("mainnet",scope);String firstAlias=pending.pendingAlias;status("STAGE: PREPARED_ENCRYPT");
            Cipher encrypted=authenticate(activity,pending.cipher,"encrypt",false);vault.finishEnrollment(pending,encrypted,secret);pending=null;
            check(vault.isEnabled("mainnet",scope),"Authenticated encryption persists an enabled scoped vault entry");
            check(Arrays.equals(secret,new char[secret.length]),"Enrollment consumes and wipes the supplied secret characters");
            stage="unauthenticated decryption";Cipher unauthenticated=vault.prepareUnlock("mainnet",scope);boolean denied=false;
            try{vault.decrypt("mainnet",scope,unauthenticated);}catch(Exception expectedDenial){denied=true;}
            check(denied,"A fresh Keystore decrypt operation cannot finish before biometric authentication");
            stage="cross-scope rejection";Cipher crossScope=vault.prepareUnlock("mainnet",scope);boolean wrongScope=false;
            try{vault.decrypt("mainnet",other,crossScope);}catch(Exception expectedDenial){wrongScope=true;}
            check(wrongScope&&!vault.isEnabled("mainnet",other),"A scoped decrypt operation cannot unlock another wallet");
            stage="authenticated decryption";Cipher decrypt=vault.prepareUnlock("mainnet",scope);unlocked=vault.decrypt("mainnet",scope,authenticate(activity,decrypt,"decrypt",false));
            check(Arrays.equals(expected,unlocked),"Authenticated Keystore decryption returns the exact synthetic secret");Arrays.fill(unlocked,'\0');unlocked=null;
            stage="disable and key removal";vault.disable("mainnet",scope);KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
            check(!vault.isEnabled("mainnet",scope)&&!store.containsAlias(firstAlias),"Disabling enrollment removes its encrypted blob and Keystore key");
            stage="prompt cancellation cleanup";pending=vault.prepareEnrollment("mainnet",other);String cancelledAlias=pending.pendingAlias;
            authenticate(activity,pending.cipher,"cancel",true);vault.cancelEnrollment(pending);pending=null;
            check(!vault.isEnabled("mainnet",other)&&!store.containsAlias(cancelledAlias),"Cancelled enrollment leaves no encrypted blob or pending Keystore key");
            result.putString("stream","OK ("+checks+" emulator biometric crypto checks). No wallet, node or funds used.\n");
        }catch(Throwable failure){result.putString("stream","FAIL at "+stage+" after "+checks+" checks ("+failure.getClass().getSimpleName()+"). No secret values logged.\n");}
        finally{
            Arrays.fill(secret,'\0');Arrays.fill(expected,'\0');if(unlocked!=null)Arrays.fill(unlocked,'\0');
            if(vault!=null){try{if(pending!=null)vault.cancelEnrollment(pending);vault.disable("mainnet",scope);vault.disable("mainnet",other);}catch(Exception cleanup){result.putString("stream",result.getString("stream","")+"FAIL synthetic vault cleanup ("+cleanup.getClass().getSimpleName()+").\n");}}
            if(activity!=null){final Activity current=activity;runOnMainSync(()->{current.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);current.finish();});}
        }
        finish(result.getString("stream","").startsWith("OK")&&!result.getString("stream","").contains("FAIL")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
