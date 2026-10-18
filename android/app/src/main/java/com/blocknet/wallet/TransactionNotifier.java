package com.blocknet.wallet;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import org.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Local unlocked-wallet activity alerts. Never unlocks a wallet or starts a core. */
public final class TransactionNotifier {
    public static final String SILENT_CHANNEL="transactions.silent.v1",SOUND_CHANNEL="transactions.sound.v1";
    private static final String ENABLED="alerts.transactions.enabled",SOUND="alerts.transactions.sound";
    private static final int MAX_IDS=4096,MAX_SCOPES=8,PAGE=100,MAX_RECORDS=1000;
    private static final Object LOCK=new Object();
    private final Context context;private final NativeRuntime runtime;private final WalletApi api;
    public TransactionNotifier(Context context){this.context=context.getApplicationContext();runtime=NativeRuntime.get(context);api=new WalletApi(runtime);}
    public static boolean enabled(Context c){return c.getSharedPreferences("wallet",Context.MODE_PRIVATE).getBoolean(ENABLED,true);}
    public static boolean soundEnabled(Context c){return c.getSharedPreferences("wallet",Context.MODE_PRIVATE).getBoolean(SOUND,false);}
    interface AlertToggleStore {
        boolean enabled();
        boolean saveEnabled(boolean value);
        boolean clearSeen();
    }
    static void changeEnabled(AlertToggleStore store,boolean value)throws IOException {
        if(store.enabled()==value)return;
        if(!value){
            // Commit the disabled state first. A failed commit must leave the active
            // alert baseline untouched, so the next poll cannot silently absorb activity.
            if(!store.saveEnabled(false))throw new IOException("Could not save transaction alert preference");
            return;
        }
        // Only a successful reset may enable alerts. The first valid observation
        // after re-enable establishes a quiet baseline for the disabled interval.
        if(!store.clearSeen())throw new IOException("Could not reset transaction alert baseline");
        if(!store.saveEnabled(true))throw new IOException("Could not save transaction alert preference");
    }
    public static void setEnabled(Context c,boolean value)throws IOException {
        synchronized(LOCK){
            SharedPreferences wallet=c.getSharedPreferences("wallet",Context.MODE_PRIVATE);
            SharedPreferences seen=c.getSharedPreferences("transaction-alert-state",Context.MODE_PRIVATE);
            changeEnabled(new AlertToggleStore(){
                @Override public boolean enabled(){return wallet.getBoolean(ENABLED,true);}
                @Override public boolean saveEnabled(boolean next){return wallet.edit().putBoolean(ENABLED,next).commit();}
                @Override public boolean clearSeen(){return seen.edit().clear().commit();}
            },value);
        }
    }
    public static void setSoundEnabled(Context c,boolean value)throws IOException {if(!c.getSharedPreferences("wallet",Context.MODE_PRIVATE).edit().putBoolean(SOUND,value).commit())throw new IOException("Could not save transaction alert sound preference");}
    static final class Event {
        final String key,txid;final boolean incoming;final long amount;
        Event(String txid,int outputIndex,boolean incoming,long amount){this.txid=txid;this.incoming=incoming;this.amount=amount;key=incoming?"in:"+txid+":"+outputIndex:"out:"+txid;}
    }
    static final class Seen {
        boolean initialized;final LinkedHashSet<String> ids=new LinkedHashSet<>();
        void touch(String id){ids.remove(id);ids.add(id);while(ids.size()>MAX_IDS)ids.remove(ids.iterator().next());}
    }
    /** First complete valid observation is a quiet baseline, including an empty wallet. */
    static List<Event> observe(Seen state,List<Event> events){
        List<Event> fresh=new ArrayList<>();Set<String> outgoing=new HashSet<>();
        for(Event e:events)if(!e.incoming)outgoing.add(e.txid);
        for(Event e:events){boolean ownChange=e.incoming&&(outgoing.contains(e.txid)||state.ids.contains("out:"+e.txid));if(state.initialized&&!state.ids.contains(e.key)&&!ownChange)fresh.add(e);state.touch(e.key);}
        state.initialized=true;return fresh;
    }
    private SharedPreferences store(){return context.getSharedPreferences("transaction-alert-state",Context.MODE_PRIVATE);}
    /** Public-address binding established only after the user opens the authoritative wallet. */
    static String walletIdentity(String address)throws Exception {
        if(address==null||!address.matches("[1-9A-HJ-NP-Za-km-z]{64,100}"))throw new IOException("Invalid wallet activity identity");
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(("blocknet-wallet-activity-v1\0"+address).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    static void rememberWallet(Context c,String network,String wallet,String address)throws Exception {
        String key="activity-wallet-id."+scope(network,wallet),identity=walletIdentity(address);
        SharedPreferences prefs=c.getSharedPreferences("wallet",Context.MODE_PRIVATE);
        if(!identity.equals(prefs.getString(key,""))&&!prefs.edit().putString(key,identity).commit())throw new IOException("Could not save wallet activity binding");
    }
    private static String scope(String network,String wallet)throws Exception {
        if(!Arrays.asList("mainnet","testnet").contains(network)||wallet==null||!wallet.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,47}\\.wallet\\.dat"))throw new IOException("Invalid wallet alert scope");
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest((network+"\0"+wallet).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private Seen read(String scope)throws Exception {
        Seen state=new Seen();String raw=store().getString(scope,null);if(raw==null)return state;
        try {
            JSONObject saved=new JSONObject(raw);if(saved.getInt("version")!=1)return state;state.initialized=saved.getBoolean("initialized");JSONArray ids=saved.getJSONArray("seen");if(ids.length()>MAX_IDS)return new Seen();for(int i=0;i<ids.length();i++){String id=ids.getString(i);if(!id.matches("(?:in:[a-f0-9]{64}:[0-9]+|out:[a-f0-9]{64})"))return new Seen();state.touch(id);}return state;
        }catch(Exception corrupt){return new Seen();} // Re-establish a quiet baseline instead of replaying old history.
    }
    private void save(String scope,Seen state)throws Exception {
        JSONObject saved=new JSONObject().put("version",1).put("initialized",state.initialized).put("updated",System.currentTimeMillis()).put("seen",new JSONArray(state.ids));SharedPreferences.Editor edit=store().edit().putString(scope,saved.toString());
        Map<String,?> all=store().getAll();if(!all.containsKey(scope)&&all.size()>=MAX_SCOPES){String oldest=null;long oldestAt=Long.MAX_VALUE;for(Map.Entry<String,?> item:all.entrySet()){long at=0;try{at=new JSONObject(String.valueOf(item.getValue())).optLong("updated");}catch(Exception ignored){}if(at<oldestAt){oldestAt=at;oldest=item.getKey();}}if(oldest!=null)edit.remove(oldest);}
        if(!edit.commit())throw new IOException("Could not save transaction alert state");
    }
    private JSONObject get(String path)throws Exception {return api.request("GET",path,null,null,3000);}
    private JSONArray page(String path,String array)throws Exception {
        JSONArray result=new JSONArray();int offset=0;
        while(offset<MAX_RECORDS){JSONObject response=get(path+"?limit="+PAGE+"&order=desc&offset="+offset);JSONArray rows=response.getJSONArray(array);int total=response.getInt("total");if(total<0||rows.length()>PAGE||response.getInt("count")!=rows.length()||response.getInt("offset")!=offset||response.getInt("limit")!=PAGE||(rows.length()==0&&offset<total))throw new IOException("Invalid wallet activity page");for(int i=0;i<rows.length();i++)result.put(rows.getJSONObject(i));offset+=rows.length();if(rows.length()<PAGE||offset>=total)break;}
        return result;
    }
    private static String txid(JSONObject row)throws Exception {String tx=row.getString("txid").toLowerCase(Locale.ROOT);if(!tx.matches("[a-f0-9]{64}"))throw new IOException("Invalid wallet transaction ID");return tx;}
    private static long amount(JSONObject row,String field)throws Exception {long amount=row.getLong(field);if(amount<0)throw new IOException("Invalid wallet transaction amount");return amount;}
    private JSONArray activityPage(String kind,String identity)throws Exception {
        JSONArray result=new JSONArray();int offset=0;
        while(offset<MAX_RECORDS){
            JSONObject response=get("/api/wallet/activity?kind="+kind+"&limit="+PAGE+"&order=desc&offset="+offset);
            if(response.getInt("version")!=1||!identity.equals(response.getString("wallet_id"))||response.getBoolean("scanning")||response.getLong("synced_height")<response.getLong("chain_height"))throw new IOException("Wallet activity is not ready for this wallet");
            JSONArray rows=response.getJSONArray(kind);int total=response.getInt("total");
            if(total<0||rows.length()>PAGE||response.getInt("count")!=rows.length()||response.getInt("offset")!=offset||response.getInt("limit")!=PAGE||!"desc".equals(response.getString("order"))||(rows.length()<PAGE&&offset+rows.length()<total))throw new IOException("Invalid wallet activity page");
            for(int i=0;i<rows.length();i++)result.put(rows.getJSONObject(i));offset+=rows.length();if(rows.length()<PAGE||offset>=total)break;
        }
        return result;
    }
    interface PollAction { void run() throws Exception; }
    static boolean pollWhenEnabled(boolean enabled,PollAction action) throws Exception {
        if(!enabled)return false;
        action.run();return true;
    }
    /** Call only on a dedicated worker; locked/unloaded/scanning responses never establish a baseline. */
    public void poll(){
        try{pollWhenEnabled(enabled(context),this::pollEnabled);}
        catch(Exception unavailable){/* No unlocked valid observation: retain durable baseline, never log wallet data. */}
    }
    private void pollEnabled() throws Exception {
            if(!runtime.alive())return;long generation=runtime.generation();String network=runtime.network(),wallet=runtime.prefs.getString("active."+network,"main.wallet.dat");
            if(runtime.prefs.contains("wallet-change."+network))return;
            JSONObject status=get("/api/status");if(status.optBoolean("syncing"))return;
            JSONArray sends,outputs;
            String identity=runtime.prefs.getString("activity-wallet-id."+scope(network,wallet),"");
            try {
                if(identity.isEmpty())throw new IOException("Open this wallet once to establish its activity binding");
                sends=activityPage("sends",identity);outputs=activityPage("outputs",identity);
            }catch(WalletApi.ApiException unsupported){
                if(unsupported.status!=404)throw unsupported;
                // The supplied Core has no activity extension; retain unlocked-wallet compatibility.
                JSONObject balance=get("/api/wallet/balance");
                if(balance.getBoolean("scanning")||balance.getLong("synced_height")<balance.getLong("chain_height"))return;
                sends=page("/api/wallet/sends","sends");outputs=page("/api/wallet/history/page","outputs");
            }
            List<Event> events=new ArrayList<>();
            for(int i=0;i<sends.length();i++){JSONObject row=sends.getJSONObject(i);events.add(new Event(txid(row),-1,false,amount(row,"total_amount")));}
            for(int i=0;i<outputs.length();i++){JSONObject row=outputs.getJSONObject(i);int index=row.getInt("output_index");if(index<0)throw new IOException("Invalid wallet output index");events.add(new Event(txid(row),index,true,amount(row,"amount")));}
            if(!runtime.alive()||generation!=runtime.generation()||!network.equals(runtime.network())||!wallet.equals(runtime.prefs.getString("active."+network,"main.wallet.dat"))||runtime.prefs.contains("wallet-change."+network))return;
            String scope=scope(network,wallet);
            synchronized(LOCK){
                if(!enabled(context))return;
                Seen seen=read(scope);List<Event> fresh=observe(seen,events);save(scope,seen);publish(scope,fresh);
            }
    }
    /** Accepted broadcast hook: failures here must never turn a successful send into a retry. */
    public void acceptedOutgoing(String network,String wallet,JSONObject result){
        try{JSONArray recipients=result.getJSONArray("recipients");long total=0;for(int i=0;i<recipients.length();i++)total=Math.addExact(total,amount(recipients.getJSONObject(i),"amount"));Event event=new Event(txid(result),-1,false,total);String scope=scope(network,wallet);synchronized(LOCK){if(!enabled(context))return;Seen seen=read(scope);boolean fresh=!seen.ids.contains(event.key);seen.touch(event.key);save(scope,seen);if(fresh)publish(scope,Collections.singletonList(event));}}catch(Exception unavailable){/* Never alter send outcome or expose wallet contents. */}
    }
    private void channels(NotificationManager manager){
        NotificationChannel silent=new NotificationChannel(SILENT_CHANNEL,LocalizedText.get(context,"Transaction alerts · silent"),NotificationManager.IMPORTANCE_DEFAULT);silent.setDescription(LocalizedText.get(context,"New wallet payments without sound"));silent.setSound(null,null);silent.enableVibration(false);silent.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(silent);
        NotificationChannel sound=new NotificationChannel(SOUND_CHANNEL,LocalizedText.get(context,"Transaction alerts · sound"),NotificationManager.IMPORTANCE_DEFAULT);sound.setDescription(LocalizedText.get(context,"New wallet payments with notification sound"));sound.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());sound.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(sound);
    }
    private void publish(String scope,List<Event> events){
        if(events.isEmpty()||!enabled(context)||(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED))return;
        NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager==null||!manager.areNotificationsEnabled())return;channels(manager);String channel=soundEnabled(context)?SOUND_CHANNEL:SILENT_CHANNEL;
        PendingIntent open=PendingIntent.getActivity(context,0,new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification publicVersion=new Notification.Builder(context,SILENT_CHANNEL).setSmallIcon(R.drawable.ic_wallet).setContentTitle(context.getString(R.string.app_name)).setContentText(LocalizedText.get(context,"New wallet activity")).build();
        int limit=Math.min(10,events.size());for(int i=0;i<limit;i++){Event event=events.get(i);Notification notification=new Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_wallet).setContentTitle(LocalizedText.get(context,event.incoming?"Payment received":"Payment sent")).setContentText(Amounts.format(event.amount)+" BNT").setCategory(Notification.CATEGORY_EVENT).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).setAutoCancel(true).setContentIntent(open).build();manager.notify(scope+":"+event.key,1001,notification);}
        if(events.size()>limit){Notification summary=new Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_wallet).setContentTitle(LocalizedText.get(context,"Wallet activity updated")).setContentText(LocalizedText.get(context,events.size()+" new wallet activity entries. Open the wallet to review.")).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).setAutoCancel(true).setContentIntent(open).build();manager.notify(scope+":summary",1002,summary);}
    }
}
