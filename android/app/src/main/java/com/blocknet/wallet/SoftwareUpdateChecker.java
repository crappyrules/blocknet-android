package com.blocknet.wallet;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Network;
import com.google.gson.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only checks of the repository's published preview releases. No APK is downloaded. */
final class SoftwareUpdateChecker {
    static final String RELEASES_URL="https://api.github.com/repos/crappyrules/blocknet-android/releases?per_page=10";
    static final String PAGE_PREFIX="https://github.com/crappyrules/blocknet-android/releases/tag/";
    private static final String DOWNLOAD_PREFIX="https://github.com/crappyrules/blocknet-android/releases/download/";
    private static final Pattern TAG=Pattern.compile("^v(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})(?:-preview\\.(\\d{1,6}))?$");
    private static final long DAY=24L*60*60*1000, RETRY=6L*60*60*1000;
    private static final int JOB_ID=0x424e5501;
    private static final Object CHECK_LOCK=new Object();
    private final Context context;
    private final SharedPreferences prefs;

    static final class Release {
        final String tag, notes, url;
        final boolean signedAnnouncement;
        Release(String tag,String notes,String url){this(tag,notes,url,false);}
        Release(String tag,String notes,String url,boolean signedAnnouncement){
            this.tag=tag;this.notes=notes;this.url=url;this.signedAnnouncement=signedAnnouncement;
        }
    }
    static final class Result {
        final boolean checked;
        final Release available;
        Result(boolean checked,Release available){this.checked=checked;this.available=available;}
    }

    SoftwareUpdateChecker(Context context){
        this.context=context.getApplicationContext();
        prefs=this.context.getSharedPreferences("software-updates",Context.MODE_PRIVATE);
    }
    static boolean githubPollingEnabled(boolean signedChannelConfigured){return !signedChannelConfigured;}
    static Release visibleRelease(boolean signedChannelConfigured,Release signed,Release legacy){
        return signedChannelConfigured?signed:legacy;
    }
    static void schedule(Context context){
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(!githubPollingEnabled(BuildConfig.ANNOUNCEMENT_ENABLED)){
            if(scheduler!=null)scheduler.cancel(JOB_ID); // remove a job retained from a legacy build
            return;
        }
        if(scheduler==null||scheduler.getPendingJob(JOB_ID)!=null)return;
        JobInfo info=new JobInfo.Builder(JOB_ID,new ComponentName(context,UpdateCheckJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(DAY).build();
        scheduler.schedule(info);
    }
    static boolean due(long now,long lastSuccess,long lastAttempt){
        if(now<=0)return false;
        return (lastSuccess<=0||now-lastSuccess>=DAY)
            &&(lastAttempt<=0||now-lastAttempt>=RETRY);
    }
    long lastChecked(){return BuildConfig.ANNOUNCEMENT_ENABLED?0:prefs.getLong("lastSuccess",0);}
    Release saved(){
        Release signed=AnnouncementScanner.saved(context);
        if(!githubPollingEnabled(BuildConfig.ANNOUNCEMENT_ENABLED))return visibleRelease(true,signed,null);
        if(!prefs.getString("variant","").equals(BuildConfig.CORE_ACTIVITY_API?"activity":"supplied"))return null;
        String tag=prefs.getString("tag","");
        String url=prefs.getString("url","");
        if(compareTags(tag,BuildConfig.RELEASE_TAG)<=0||!url.equals(PAGE_PREFIX+tag))return null;
        return visibleRelease(false,signed,new Release(tag,prefs.getString("notes",""),url));
    }
    Result check(boolean manual) throws Exception {
        synchronized(CHECK_LOCK){
            if(!githubPollingEnabled(BuildConfig.ANNOUNCEMENT_ENABLED)){
                if(manual)new AnnouncementScanner(context).poll();
                return new Result(manual,saved());
            }
            long now=System.currentTimeMillis();
            if(!manual&&!due(now,prefs.getLong("lastSuccess",0),prefs.getLong("lastAttempt",0)))return new Result(false,saved());
            SyncNetworkPolicy.State state=SyncNetworkPolicy.read(context);
            if(!state.allowed){if(manual)throw new IOException(state.reason);return new Result(false,saved());}
            Network network=SyncNetworkPolicy.allowedNetwork(context);
            if(network==null){if(manual)throw new IOException("No permitted sync connection is available.");return new Result(false,saved());}
            if(!prefs.edit().putLong("lastAttempt",now).commit())throw new IOException("Could not save update check time");
            Release latest=parseReleases(fetch(network),BuildConfig.RELEASE_TAG,BuildConfig.CORE_ACTIVITY_API);
            requireSameNetwork(network);
            SharedPreferences.Editor edit=prefs.edit().putLong("lastSuccess",System.currentTimeMillis());
            if(latest==null)edit.remove("tag").remove("notes").remove("url").remove("variant");
            else edit.putString("tag",latest.tag).putString("notes",latest.notes).putString("url",latest.url)
                .putString("variant",BuildConfig.CORE_ACTIVITY_API?"activity":"supplied");
            if(!edit.commit())throw new IOException("Could not save update check result");
            return new Result(true,latest);
        }
    }
    private String fetch(Network network) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)network.openConnection(new URL(RELEASES_URL));
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept","application/vnd.github+json");
            connection.setRequestProperty("User-Agent","BlocknetWallet-Android-UpdateCheck");
            requireSameNetwork(network);
            if(connection.getResponseCode()!=200)throw new IOException("GitHub release check returned HTTP "+connection.getResponseCode());
            requireSameNetwork(network);
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int count;
                while(true){
                    requireSameNetwork(network);
                    count=in.read(buffer);if(count<0)break;
                    requireSameNetwork(network);
                    if(out.size()>256*1024-count)throw new IOException("GitHub release response is too large");
                    out.write(buffer,0,count);
                }
                requireSameNetwork(network);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {connection.disconnect();}
    }
    private void requireSameNetwork(Network expected)throws IOException{
        Network current=SyncNetworkPolicy.allowedNetwork(context);
        if(current==null||!current.equals(expected))throw new IOException("The permitted connection changed during the update check.");
    }
    static Release parseReleases(String json,String currentTag,boolean activityCore) throws IOException {
        if(compareTags(currentTag,"v0.0.0")<0)throw new IOException("Invalid installed release version");
        final JsonArray releases;
        try {releases=JsonParser.parseString(json).getAsJsonArray();}
        catch(Exception malformed){throw new IOException("Invalid GitHub release response",malformed);}
        Release newest=null;
        for(JsonElement item:releases){
            if(!item.isJsonObject())throw new IOException("Invalid GitHub release entry");
            JsonObject entry=item.getAsJsonObject();
            String tag=string(entry,"tag_name");
            if(!validTag(tag)||compareTags(tag,currentTag)<=0)continue;
            if(booleanValue(entry,"draft"))continue;
            if(newest!=null&&compareTags(tag,newest.tag)<=0)continue;
            String page=string(entry,"html_url");
            if(!page.equals(PAGE_PREFIX+tag))throw new IOException("Release page is outside the official repository");
            JsonArray assets;
            try {assets=entry.getAsJsonArray("assets");if(assets==null)throw new Exception();}
            catch(Exception malformed){throw new IOException("Release has no valid asset list",malformed);}
            String expected=activityCore?"blocknet-wallet-arm64-activity-debug.apk":"blocknet-wallet-arm64-supplied-debug.apk";
            boolean found=false;
            for(JsonElement asset:assets){
                if(!asset.isJsonObject())throw new IOException("Invalid release asset");
                JsonObject data=asset.getAsJsonObject();
                if(!expected.equals(string(data,"name")))continue;
                String url=string(data,"browser_download_url");
                if(!url.equals(DOWNLOAD_PREFIX+tag+"/"+expected)||!"uploaded".equals(string(data,"state"))||longValue(data,"size")<1024*1024)
                    throw new IOException("The expected APK asset is invalid");
                found=true;
            }
            if(!found)throw new IOException("The release has no APK for this Core variant");
            String notes=entry.has("body")&&!entry.get("body").isJsonNull()?string(entry,"body"):"";
            if(notes.length()>3000)notes=notes.substring(0,3000)+"…";
            newest=new Release(tag,notes,page);
        }
        return newest;
    }
    static boolean validTag(String tag){return tag!=null&&TAG.matcher(tag).matches();}
    static int compareTags(String a,String b){
        Matcher left=TAG.matcher(a==null?"":a),right=TAG.matcher(b==null?"":b);
        if(!left.matches()||!right.matches())return -1;
        for(int i=1;i<=3;i++){int difference=Integer.compare(Integer.parseInt(left.group(i)),Integer.parseInt(right.group(i)));if(difference!=0)return difference;}
        if(left.group(4)==null)return right.group(4)==null?0:1;
        if(right.group(4)==null)return -1;
        return Integer.compare(Integer.parseInt(left.group(4)),Integer.parseInt(right.group(4)));
    }
    private static String string(JsonObject object,String key) throws IOException {
        try {JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new Exception();return value.getAsString();}
        catch(Exception malformed){throw new IOException("Invalid release field: "+key,malformed);}
    }
    private static boolean booleanValue(JsonObject object,String key) throws IOException {
        try {JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new Exception();return value.getAsBoolean();}
        catch(Exception malformed){throw new IOException("Invalid release field: "+key,malformed);}
    }
    private static long longValue(JsonObject object,String key) throws IOException {
        try {JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new Exception();return value.getAsLong();}
        catch(Exception malformed){throw new IOException("Invalid release field: "+key,malformed);}
    }
}
