package com.blocknet.wallet;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Emulator-only synthetic notification acceptance. Never starts a node or opens a wallet. */
public final class WalletNotificationInstrumentation extends Instrumentation {
    private static final String ENABLED="alerts.transactions.enabled", SOUND="alerts.transactions.sound";
    private int checks;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean value,String label){
        if(!value)throw new AssertionError(label);
        checks++;Bundle update=new Bundle();update.putString("stream","PASS: "+label+"\n");sendStatus(0,update);
    }
    private static String digest(String value)throws Exception{
        StringBuilder result=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))result.append(String.format(Locale.ROOT,"%02x",b&255));
        return result.toString();
    }
    private static JSONObject payment(String txid)throws Exception{
        return new JSONObject().put("txid",txid).put("recipients",new JSONArray().put(new JSONObject().put("amount",123456789L)));
    }
    // Inspect only the uniquely tagged notification owned by this fixture. Never serialize notifications.
    private static Notification find(NotificationManager manager,String tag){
        for(StatusBarNotification item:manager.getActiveNotifications())if(item.getId()==1001&&tag.equals(item.getTag()))return item.getNotification();
        return null;
    }
    private static Notification await(NotificationManager manager,String tag,boolean present)throws Exception{
        long deadline=android.os.SystemClock.elapsedRealtime()+4000;
        do{Notification found=find(manager,tag);if((found!=null)==present)return found;Thread.sleep(40);}while(android.os.SystemClock.elapsedRealtime()<deadline);
        throw new AssertionError(present?"Synthetic notification was not posted":"Synthetic notification was not cancelled");
    }
    private static void restore(SharedPreferences prefs,String key,boolean existed,boolean value)throws Exception{
        SharedPreferences.Editor edit=prefs.edit();if(existed)edit.putBoolean(key,value);else edit.remove(key);
        if(!edit.commit())throw new IllegalStateException("Could not restore alert preference");
    }
    @Override public void onStart(){
        Bundle result=new Bundle();boolean success=false,ownsState=false;
        Context context=getTargetContext();NotificationManager manager=context.getSystemService(NotificationManager.class);
        SharedPreferences preferences=context.getSharedPreferences("wallet",Context.MODE_PRIVATE);
        SharedPreferences state=context.getSharedPreferences("transaction-alert-state",Context.MODE_PRIVATE);
        boolean hadEnabled=preferences.contains(ENABLED),oldEnabled=TransactionNotifier.enabled(context);
        boolean hadSound=preferences.contains(SOUND),oldSound=TransactionNotifier.soundEnabled(context);
        NotificationChannel oldSilent=manager.getNotificationChannel(TransactionNotifier.SILENT_CHANNEL),oldSoundChannel=manager.getNotificationChannel(TransactionNotifier.SOUND_CHANNEL);
        String scope=null;String[] tags=new String[4];
        try{
            check(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"),"Restricted to Android emulator");
            check(!NativeRuntime.get(context).alive(),"No real core is running");
            check(manager.areNotificationsEnabled()&&(Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED),"Actual app notification permission is granted before synthetic acceptance");
            check(state.getAll().size()<8,"Synthetic scope will not evict any existing dedup state");
            String unique=UUID.randomUUID().toString().replace("-","");String wallet="notify_"+unique+".wallet.dat";
            scope=digest("mainnet\0"+wallet);check(!state.contains(scope),"Synthetic scope is distinct from existing wallet state");
            for(int i=0;i<tags.length;i++)tags[i]=scope+":out:"+digest(unique+":"+i);
            ownsState=true;TransactionNotifier.setEnabled(context,true);TransactionNotifier.setSoundEnabled(context,false);
            TransactionNotifier notifier=new TransactionNotifier(context);JSONObject first=payment(digest(unique+":0"));
            notifier.acceptedOutgoing("mainnet",wallet,first);Notification notification=await(manager,tags[0],true);
            check(notification.visibility==Notification.VISIBILITY_PRIVATE,"Payment notification uses private lockscreen visibility");
            check("Payment sent".contentEquals(notification.extras.getCharSequence(Notification.EXTRA_TITLE))&&"1.23456789 BNT".contentEquals(notification.extras.getCharSequence(Notification.EXTRA_TEXT)),"Private notification contains the synthetic payment amount");
            Notification publicVersion=notification.publicVersion;
            check(publicVersion!=null&&"Blocknet Wallet".contentEquals(publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE))&&"New wallet activity".contentEquals(publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)),"Public lockscreen version contains only generic wallet activity");
            check(TransactionNotifier.SILENT_CHANNEL.equals(notification.getChannelId()),"Default alert selects the silent channel");
            NotificationChannel silent=manager.getNotificationChannel(TransactionNotifier.SILENT_CHANNEL),sound=manager.getNotificationChannel(TransactionNotifier.SOUND_CHANNEL);
            check(silent!=null&&silent.getSound()==null&&!silent.shouldVibrate(),"Silent channel has no sound or vibration");
            check(sound!=null&&sound.getSound()!=null&&sound.getAudioAttributes()!=null&&sound.getAudioAttributes().getUsage()==AudioAttributes.USAGE_NOTIFICATION,"Optional sound channel has a notification sound URI without posting to it");
            manager.cancel(tags[0],1001);await(manager,tags[0],false);
            new TransactionNotifier(context).acceptedOutgoing("mainnet",wallet,first);Thread.sleep(300);
            check(find(manager,tags[0])==null,"Durable dedup prevents repost after cancellation and notifier recreation");
            TransactionNotifier.setSoundEnabled(context,true);check(TransactionNotifier.soundEnabled(context),"Sound preference can be enabled independently without playing sound");TransactionNotifier.setSoundEnabled(context,false);
            TransactionNotifier.setEnabled(context,false);JSONObject disabled=payment(digest(unique+":1"));notifier.acceptedOutgoing("mainnet",wallet,disabled);
            check(find(manager,tags[1])==null,"Disabled transaction alerts do not post");
            TransactionNotifier.setEnabled(context,true);new TransactionNotifier(context).acceptedOutgoing("mainnet",wallet,disabled);
            check(find(manager,tags[1])==null,"Reenabling alerts does not replay a payment observed while disabled");
            if(Build.VERSION.SDK_INT>=33){
                Context denied=new ContextWrapper(context){
                    @Override public Context getApplicationContext(){return this;}
                    @Override public int checkSelfPermission(String permission){return Manifest.permission.POST_NOTIFICATIONS.equals(permission)?PackageManager.PERMISSION_DENIED:super.checkSelfPermission(permission);}
                };
                JSONObject deniedPayment=payment(digest(unique+":2"));new TransactionNotifier(denied).acceptedOutgoing("mainnet",wallet,deniedPayment);
                check(find(manager,tags[2])==null,"Denied POST_NOTIFICATIONS check suppresses posting without changing OS permission");
                new TransactionNotifier(context).acceptedOutgoing("mainnet",wallet,deniedPayment);
                check(find(manager,tags[2])==null,"Permission restoration does not replay a previously suppressed payment");
            }else{Bundle note=new Bundle();note.putString("stream","INFO: POST_NOTIFICATIONS runtime permission applies only on API 33+.\n");sendStatus(0,note);}
            notifier.acceptedOutgoing("mainnet",wallet,payment(digest(unique+":3")));
            check(TransactionNotifier.SILENT_CHANNEL.equals(await(manager,tags[3],true).getChannelId()),"Fresh later payment posts silently after alert settings are restored");
            check(!NativeRuntime.get(context).alive(),"Synthetic notification test never starts a core");success=true;
        }catch(Throwable failure){result.putString("stream","FAIL after "+checks+" notification checks ("+failure.getClass().getSimpleName()+": "+failure.getMessage()+").\n");}
        finally{
            if(ownsState)try{
                for(String tag:tags)if(tag!=null)manager.cancel(tag,1001);
                if(!state.edit().remove(scope).commit())throw new IllegalStateException("Cannot remove synthetic dedup scope");
                restore(preferences,ENABLED,hadEnabled,oldEnabled);restore(preferences,SOUND,hadSound,oldSound);
                if(oldSilent==null)manager.deleteNotificationChannel(TransactionNotifier.SILENT_CHANNEL);
                if(oldSoundChannel==null)manager.deleteNotificationChannel(TransactionNotifier.SOUND_CHANNEL);
                check(!state.contains(scope)&&TransactionNotifier.enabled(context)==oldEnabled&&TransactionNotifier.soundEnabled(context)==oldSound,"Synthetic dedup scope removed and original alert preferences restored");
            }catch(Throwable cleanup){success=false;result.putString("stream","FAIL notification fixture cleanup ("+cleanup.getClass().getSimpleName()+").\n");}
        }
        if(success)result.putString("stream","OK ("+checks+" synthetic notification checks). No core, wallet, funds, or audible alert used.\n");
        finish(success?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
