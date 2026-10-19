package com.blocknet.wallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Network;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;

/** Background, wallet-independent scan of signed on-chain release notices. */
final class AnnouncementScanner {
    private static final long MAINNET_EPOCH=0x20260215L;
    private static final long REORG_DEPTH=100;
    private static final Object SCAN_LOCK=new Object();
    private final Context context;
    private final NativeRuntime runtime;
    private final SharedPreferences state;
    private final WalletApi api;

    AnnouncementScanner(Context context) {
        this.context=context.getApplicationContext();
        runtime=NativeRuntime.get(this.context);
        api=new WalletApi(runtime);
        state=this.context.getSharedPreferences("onchain-updates-mainnet-activity-preview",Context.MODE_PRIVATE);
    }

    static SoftwareUpdateChecker.Release saved(Context context) {
        if(!BuildConfig.ANNOUNCEMENT_ENABLED)return null;
        SharedPreferences state=context.getSharedPreferences("onchain-updates-mainnet-activity-preview",Context.MODE_PRIVATE);
        if(!configId().equals(state.getString("configId","")))return null;
        String tag=state.getString("tag", ""),url=state.getString("page", "");
        if(state.getInt("versionCode",0)<=BuildConfig.VERSION_CODE||
            !url.equals("https://github.com/crappyrules/blocknet-android/releases/tag/"+tag)||
            !tag.matches("v[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}-preview\\.[0-9]{1,6}"))return null;
        String notes=state.getString("notes","");
        if(!state.getBoolean("acceptedFinal",false))notes="On-chain notice has ten confirmations and is pending finality.\n"+notes;
        return new SoftwareUpdateChecker.Release(tag,notes,url,true);
    }

    void poll() {
        if(!BuildConfig.ANNOUNCEMENT_ENABLED||!BuildConfig.CORE_ACTIVITY_API||
            !runtime.syncRequested()||(MobileCorePolicy.enabled(context)&&!MobileCorePolicy.permitted(context))||
            !runtime.alive()||!runtime.network().equals("mainnet")||
            !SyncNetworkPolicy.read(context).allowed)return;
        synchronized(SCAN_LOCK) {
            try { retryPending(); } catch(Exception ignored) { /* Retry independently on the next permitted connection. */ }
            try { scan(); } catch(Exception ignored) { /* Keep the durable cursor for the next permitted connection. */ }
        }
    }

    private void scan() throws Exception {
        if(!configId().equals(state.getString("configId",""))&&
            !state.edit().clear().putString("configId",configId()).commit())
            throw new IOException("Could not scope the announcement scanner.");
        long tip=api.get("/api/status").getLong("chain_height");
        long birth=BuildConfig.ANNOUNCEMENT_BIRTH_HEIGHT;
        if(birth<=0||tip<birth||tip-birth<9)return;
        long through=tip-9;
        revalidate(tip,birth);
        AnnouncementPendingQueue pending=readPending();
        byte[] signer=decodeHex(BuildConfig.ANNOUNCEMENT_PRIMARY_PUB,32);
        for(int page=0;page<8;page++) {
            requireConnection();
            long height=state.getLong("cursorHeight",birth);
            if(height>through)return;
            int tx=state.getInt("cursorTx",0),output=state.getInt("cursorOutput",0);
            String partialHash=state.getString("partialHash","");
            JSONObject body=new JSONObject().put("cursor",new JSONObject().put("height",height)
                .put("tx_index",tx).put("output_index",output)).put("through_height",through)
                .put("expected_hash",partialHash)
                .put("view_private_key",BuildConfig.ANNOUNCEMENT_VIEW_PRIV)
                .put("spend_public_key",BuildConfig.ANNOUNCEMENT_SPEND_PUB);
            JSONObject response;
            try {response=api.request("POST","/api/updates/scan",body.toString(),null,5000);}
            catch(WalletApi.ApiException changed) {
                if(changed.status==409)rewind(birth,height);
                return;
            }
            if(response.getInt("version")!=1||response.getLong("chain_id")!=MAINNET_EPOCH||
                !response.getString("network_id").equals("blocknet_mainnet")||
                response.getLong("tip_height")<through||response.getInt("outputs_scanned")>512)
                throw new IOException("Invalid on-chain scan response.");
            JSONArray blocks=response.getJSONArray("blocks");
            if(blocks.length()>8)throw new IOException("On-chain scan page is too large.");
            long priorHeight=height-1;
            String lastHash="";
            int candidateCount=0;
            boolean backpressure=false;
            blocksLoop: for(int i=0;i<blocks.length();i++) {
                JSONObject block=blocks.getJSONObject(i);
                long blockHeight=block.getLong("height");
                String hash=block.getString("hash");
                if(blockHeight<height||blockHeight>through||blockHeight!=priorHeight+1||!isHex(hash,64))
                    throw new IOException("On-chain scan block sequence is invalid.");
                JSONArray candidates=block.getJSONArray("candidates");
                candidateCount+=candidates.length();
                if(candidateCount>16)throw new IOException("On-chain scan candidate limit exceeded.");
                for(int j=0;j<candidates.length();j++) {
                    byte[] memo=decodeHex(candidates.getString(j),AnnouncementRecord.LENGTH);
                    AnnouncementRecord record;
                    try {record=AnnouncementRecord.parse(memo,(int)MAINNET_EPOCH,2,Collections.singletonMap(1,signer));}
                    catch(IOException unsignedOrWrongScope) {continue;}
                    if(record.versionCode>BuildConfig.VERSION_CODE&&
                        record.sequence>state.getLong("acceptedSequence",0)&&
                        record.sequence>=state.getLong("floorSequence",0)&&
                        !pending.offer(new AnnouncementPendingQueue.Entry(blockHeight,
                            hash.toLowerCase(Locale.ROOT),hex(memo)))) {
                        backpressure=true;
                        break blocksLoop;
                    }
                }
                priorHeight=blockHeight;lastHash=hash;
            }
            if(backpressure) {
                // Candidate positions are not exposed by Core. Retain the page's
                // input cursor and replay it after manifest retries free space.
                persistBackpressure(state,pending);
                return;
            }
            JSONObject next=response.getJSONObject("cursor");
            long nextHeight=next.getLong("height");
            int nextTx=next.getInt("tx_index"),nextOutput=next.getInt("output_index");
            if(nextHeight<height||nextHeight>through+1||nextTx<0||nextOutput<0||
                nextHeight==height&&nextTx==tx&&nextOutput<=output||
                nextHeight>height&&blocks.length()==0)
                throw new IOException("On-chain scan cursor did not advance.");
            boolean partial=nextTx!=0||nextOutput!=0;
            if(partial&&(blocks.length()==0||nextHeight!=priorHeight))
                throw new IOException("On-chain partial cursor lacks a block hash.");
            if(!partial&&blocks.length()>0&&nextHeight!=priorHeight+1)
                throw new IOException("On-chain scan skipped a stored block.");
            SharedPreferences.Editor edit=state.edit().putLong("cursorHeight",nextHeight)
                .putInt("cursorTx",nextTx).putInt("cursorOutput",nextOutput)
                .putString("partialHash",partial?lastHash:"").putString("pending",pending.encode());
            if(!partial&&blocks.length()>0)edit.putLong("anchorHeight",priorHeight).putString("anchorHash",lastHash);
            requireConnection();
            if(!edit.commit())throw new IOException("Could not save on-chain scan cursor.");
            if(nextHeight>through)return;
        }
    }

    static void persistBackpressure(SharedPreferences state,AnnouncementPendingQueue pending) throws IOException {
        if(!state.edit().putString("pending",pending.encode()).commit())
            throw new IOException("Could not save backpressured announcement page.");
    }

    private AnnouncementPendingQueue readPending() throws IOException {
        return readPending(state,BuildConfig.ANNOUNCEMENT_BIRTH_HEIGHT);
    }
    static AnnouncementPendingQueue readPending(SharedPreferences state,long birth) throws IOException {
        try {return AnnouncementPendingQueue.decode(state.getString("pending",""));}
        catch(IOException|ClassCastException corrupt){
            recoverCorruptPending(state,birth);
            throw new IOException("Pending announcement storage was corrupt; replaying from birth height.",corrupt);
        }
    }

    static void recoverCorruptPending(SharedPreferences state,long birth) throws IOException {
        if(birth<=0)throw new IOException("Announcement birth height is invalid.");
        SharedPreferences.Editor edit=state.edit().putLong("cursorHeight",birth)
            .putInt("cursorTx",0).putInt("cursorOutput",0)
            .remove("partialHash").remove("anchorHeight").remove("anchorHash")
            .remove("pending");
        // Keep the finalized sequence floor, so a full replay cannot downgrade it.
        if(!edit.commit())throw new IOException("Could not reset corrupt announcement storage.");
    }

    private void retryPending() throws Exception {
        if(!configId().equals(state.getString("configId","")))return;
        AnnouncementPendingQueue pending=readPending();
        if(pending.size()==0)return;
        long tip=api.get("/api/status").getLong("chain_height");
        byte[] signer=decodeHex(BuildConfig.ANNOUNCEMENT_PRIMARY_PUB,32);
        int attempts=Math.min(4,pending.size());
        for(int attempt=0;attempt<attempts&&pending.size()>0;attempt++) {
            requireConnection();
            AnnouncementPendingQueue.Entry entry=pending.first();
            if(!AnnouncementPendingQueue.confirmed(tip,entry.height))return;
            pending.removeFirst();
            AnnouncementRecord record;
            try {record=AnnouncementRecord.parse(decodeHex(entry.memo,AnnouncementRecord.LENGTH),
                (int)MAINNET_EPOCH,2,Collections.singletonMap(1,signer));}
            catch(IOException corrupt) {
                recoverCorruptPending(state,BuildConfig.ANNOUNCEMENT_BIRTH_HEIGHT);
                return;
            }
            boolean changed=false;
            try {
                if(!canonicalHashMatches(entry.height,entry.hash)) {
                    rewind(BuildConfig.ANNOUNCEMENT_BIRTH_HEIGHT,entry.height);
                    pending.dropAtOrAfter(AnnouncementPendingQueue.rewindHeight(
                        BuildConfig.ANNOUNCEMENT_BIRTH_HEIGHT,entry.height));
                    changed=true;
                } else accept(record,entry.height,entry.hash,tip);
            } catch(Exception retryable) {
                if(!pending.offer(entry))throw new IOException("Pending retry queue unexpectedly full.");
            }
            requireConnection();
            if(!state.edit().putString("pending",pending.encode()).commit())
                throw new IOException("Could not save pending announcement retry.");
            if(changed)return;
        }
    }

    private void accept(AnnouncementRecord record,long height,String blockHash,long tip) throws Exception {
        if(record.versionCode<=BuildConfig.VERSION_CODE)return;
        long floor=state.getLong("floorSequence",0),seen=state.getLong("acceptedSequence",0);
        String digest=hex(record.manifestSha256);
        if(record.sequence<floor||record.sequence==floor&&!digest.equals(state.getString("floorDigest",""))||
            record.sequence<seen||record.sequence==seen&&!digest.equals(state.getString("acceptedDigest","")))return;
        if(record.sequence==seen&&digest.equals(state.getString("acceptedDigest","")))return;
        byte[] raw=fetchManifest(record.manifestSha256);
        AnnouncementManifest.Release release=AnnouncementManifest.parse(raw,record.manifestSha256,
            record.epoch,record.sequence,record.versionCode,BuildConfig.VERSION_CODE,"activity",
            BuildConfig.ANNOUNCEMENT_APK_CERT_SHA256);
        SharedPreferences.Editor edit=state.edit().putLong("acceptedSequence",record.sequence)
            .putString("acceptedDigest",digest).putLong("acceptedHeight",height)
            .putString("acceptedHash",blockHash).putBoolean("acceptedFinal",tip-height>=REORG_DEPTH)
            .putInt("versionCode",release.versionCode).putString("tag",release.tag)
            .putString("page",release.page).putString("notes",release.notes);
        if(tip-height>=REORG_DEPTH)edit.putLong("floorSequence",record.sequence).putString("floorDigest",digest);
        requireConnection();
        if(!edit.commit())throw new IOException("Could not save signed announcement.");
    }

    private void revalidate(long tip,long birth) throws Exception {
        long anchor=state.getLong("anchorHeight",-1);
        if(anchor>=birth&&!canonicalHashMatches(anchor,state.getString("anchorHash",""))) {
            rewind(birth,anchor);return;
        }
        long accepted=state.getLong("acceptedHeight",-1);
        if(accepted>=birth&&!state.getBoolean("acceptedFinal",false)) {
            if(!canonicalHashMatches(accepted,state.getString("acceptedHash",""))) {
                rewind(birth,accepted);return;
            }
            if(tip-accepted>=REORG_DEPTH) {
                if(!state.edit().putBoolean("acceptedFinal",true)
                    .putLong("floorSequence",state.getLong("acceptedSequence",0))
                    .putString("floorDigest",state.getString("acceptedDigest","")).commit())
                    throw new IOException("Could not finalize announcement sequence.");
            }
        }
    }

    private String blockHash(long height) throws Exception {
        requireConnection();String hash=api.get("/api/block/"+height).getString("hash");
        if(!isHex(hash,64))throw new IOException("Invalid canonical block hash.");return hash;
    }
    private boolean canonicalHashMatches(long height,String expected) throws Exception {
        try {return blockHash(height).equals(expected);}
        catch(WalletApi.ApiException missing) {if(missing.status==404)return false;throw missing;}
    }
    private void rewind(long birth,long height) throws IOException {
        long back=AnnouncementPendingQueue.rewindHeight(birth,height);
        AnnouncementPendingQueue pending=readPending();
        pending.dropAtOrAfter(back);
        SharedPreferences.Editor edit=state.edit().putLong("cursorHeight",back)
            .putInt("cursorTx",0).putInt("cursorOutput",0).remove("partialHash")
            .remove("anchorHeight").remove("anchorHash").putString("pending",pending.encode());
        if(!state.getBoolean("acceptedFinal",false))edit.remove("acceptedSequence").remove("acceptedDigest")
            .remove("acceptedHeight").remove("acceptedHash").remove("tag").remove("page")
            .remove("notes").remove("versionCode");
        if(!edit.commit())throw new IOException("Could not rewind on-chain scan after reorganization.");
    }

    private byte[] fetchManifest(byte[] digest) throws Exception {
        Network network=requireConnection();
        URL url=new URL(AnnouncementManifest.urlForDigest(digest));
        HttpURLConnection connection=(HttpURLConnection)network.openConnection(url);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept","application/json");
            if(connection.getResponseCode()!=200)throw new IOException("Announcement manifest is unavailable.");
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int count;
                while((count=input.read(buffer))!=-1){
                    if(!network.equals(requireConnection()))throw new IOException("Permitted network changed.");
                    if(output.size()>32768-count)throw new IOException("Announcement manifest is too large.");
                    output.write(buffer,0,count);
                }
                if(!network.equals(requireConnection()))throw new IOException("Permitted network changed.");
                return output.toByteArray();
            }
        } finally {connection.disconnect();}
    }
    private Network requireConnection() throws IOException {
        if(!runtime.syncRequested()||(MobileCorePolicy.enabled(context)&&!MobileCorePolicy.permitted(context)))
            throw new IOException("Announcement scan paused.");
        if(!SyncNetworkPolicy.read(context).allowed)throw new IOException("No permitted announcement connection.");
        Network network=SyncNetworkPolicy.allowedNetwork(context);
        if(network==null)throw new IOException("No permitted announcement connection.");
        return network;
    }
    private static boolean isHex(String value,int length){return value!=null&&value.length()==length&&value.matches("[0-9a-fA-F]+");}
    private static byte[] decodeHex(String value,int bytes) throws IOException {
        if(!isHex(value,bytes*2))throw new IOException("Invalid announcement field encoding.");
        byte[] out=new byte[bytes];
        for(int i=0;i<bytes;i++)out[i]=(byte)Integer.parseInt(value.substring(2*i,2*i+2),16);
        return out;
    }
    private static String hex(byte[] value){StringBuilder out=new StringBuilder(value.length*2);for(byte b:value)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
    private static String configId() {
        try {
            byte[] source=(BuildConfig.ANNOUNCEMENT_PRIMARY_PUB+":"+BuildConfig.ANNOUNCEMENT_VIEW_PRIV+":"+
                BuildConfig.ANNOUNCEMENT_SPEND_PUB+":"+BuildConfig.ANNOUNCEMENT_BIRTH_HEIGHT+":"+
                BuildConfig.ANNOUNCEMENT_APK_CERT_SHA256).getBytes(StandardCharsets.US_ASCII);
            return hex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch(Exception unavailable) {return "";}
    }
}
