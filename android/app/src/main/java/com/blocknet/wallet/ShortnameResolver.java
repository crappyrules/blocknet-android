package com.blocknet.wallet;

import android.content.Context;
import android.net.Network;
import android.os.SystemClock;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-origin, signed Blocknet ID lookup. It never accepts an unsigned resolution. */
public final class ShortnameResolver {
    public static final class NotFoundException extends IOException { NotFoundException(){super("Blocknet shortname was not found");} }
    private static final String ORIGIN="https://blocknet.id";
    private static final int MAX_RESPONSE=128*1024;
    private static final long LOOKUP_DEADLINE_NS=TimeUnit.SECONDS.toNanos(12);
    private static final long KEY_TTL=10*60*1000L;
    private static final Object KEY_LOCK=new Object();
    private static final AtomicInteger CANCEL_EPOCH=new AtomicInteger();
    private static final Map<RequestGuard,Network> ACTIVE=new ConcurrentHashMap<>();
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(task->{
        Thread thread=new Thread(task,"shortname-deadlines");thread.setDaemon(true);return thread;
    });
    private static byte[] cachedKey;
    private static long cachedAt;
    private final Context context;
    public ShortnameResolver(Context context){Context application=context.getApplicationContext();this.context=application==null?context:application;}
    public static final class Result {
        public final String handle,address;
        public final boolean verified=true;
        private Result(String handle,String address){this.handle=handle;this.address=address;}
    }
    public static String normalize(String value){
        if(value==null)throw new IllegalArgumentException("Enter a Blocknet shortname");
        String handle=value.trim();
        if(handle.startsWith("$")||handle.startsWith("@"))handle=handle.substring(1);
        if(!handle.matches("[A-Za-z0-9_.-]{1,64}"))throw new IllegalArgumentException("Shortnames use 1–64 letters, numbers, dots, dashes or underscores");
        return handle.toLowerCase(Locale.ROOT);
    }
    /** Pure raw-key Ed25519 verification, without registering a JCA provider. */
    public static boolean verifySignature(byte[] key,String payload,byte[] signature){
        if(key==null||key.length!=32||payload==null||signature==null||signature.length!=64)return false;
        try{
            byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
            Ed25519Signer verifier=new Ed25519Signer();verifier.init(false,new Ed25519PublicKeyParameters(key,0));verifier.update(bytes,0,bytes.length);
            return verifier.verifySignature(signature);
        }catch(RuntimeException invalid){return false;}
    }
    static int cancellationEpoch(){return CANCEL_EPOCH.get();}
    static void requireEpoch(int expected)throws IOException {
        if(expected!=CANCEL_EPOCH.get())throw new IOException("Shortname lookup was cancelled");
    }
    public Result resolve(String input)throws IOException{return resolve(input,cancellationEpoch());}
    Result resolve(String input,int expectedEpoch)throws IOException{
        String requested=normalize(input);requireEpoch(expectedEpoch);
        Network network=SyncNetworkPolicy.allowedNetwork(context);
        if(network==null)throw new IOException(SyncNetworkPolicy.read(context).reason);
        requireEpoch(expectedEpoch);
        int epoch=expectedEpoch;long deadline=System.nanoTime()+LOOKUP_DEADLINE_NS;
        try{
            JSONObject response=new JSONObject(get(network,"/api/v1/resolve/"+requested,epoch,deadline));
            Result verified=verifyResponse(requested,response,publicKey(network,epoch,deadline));
            requireEpoch(epoch);
            if(System.nanoTime()-deadline>=0)throw new IOException("Shortname lookup timed out");
            requireNetwork(network);
            return verified;
        }catch(IOException failure){throw failure;}
        catch(Exception invalid){throw new IOException("Invalid signed shortname response",invalid);}
    }
    /** Package-visible signed JSON binding seam for Android instrumentation, without network access. */
    static Result verifyResponse(String requested,JSONObject response,byte[] publicKey)throws IOException{
        requested=normalize(requested);
        try{
            String payload=requiredString(response,"payload"),signature=requiredString(response,"sig");
            byte[] decoded;
            try{decoded=Base64.getDecoder().decode(signature);}catch(IllegalArgumentException invalid){throw new IOException("Invalid shortname signature");}
            if(!verifySignature(publicKey,payload,decoded))throw new IOException("Shortname signature verification failed");
            JSONObject signed=new JSONObject(payload);
            String signedHandle=requiredString(signed,"handle"),responseHandle=requiredString(response,"handle");
            String address=requiredString(signed,"address"),responseAddress=requiredString(response,"address");
            if(!signedHandle.trim().equalsIgnoreCase(requested)||!responseHandle.trim().equalsIgnoreCase(requested))throw new IOException("Signed shortname handle does not match the request");
            if(!address.equals(responseAddress)||!address.matches("[1-9A-HJ-NP-Za-km-z]{64,100}"))throw new IOException("Signed shortname address does not match the response");
            if(requiredTimestamp(signed,"updated_at_unix")!=requiredTimestamp(response,"updated_at"))throw new IOException("Signed shortname timestamp does not match the response");
            return new Result(requested,address);
        }catch(IOException failure){throw failure;}
        catch(Exception invalid){throw new IOException("Invalid signed shortname response",invalid);}
    }
    private static String requiredString(JSONObject object,String name)throws Exception{
        Object value=object.get(name);if(!(value instanceof String)||((String)value).trim().isEmpty())throw new IOException("Incomplete signed shortname response");return (String)value;
    }
    private static long requiredTimestamp(JSONObject object,String name)throws Exception{
        Object value=object.get(name);if(!(value instanceof Integer)&&!(value instanceof Long))throw new IOException("Invalid signed shortname timestamp");return ((Number)value).longValue();
    }
    private byte[] publicKey(Network network,int epoch,long deadline)throws Exception{
        long now=SystemClock.elapsedRealtime();
        synchronized(KEY_LOCK){if(cachedKey!=null&&now>=cachedAt&&now-cachedAt<KEY_TTL)return cachedKey.clone();}
        JSONObject document=new JSONObject(get(network,"/.well-known/blocknet-id.json",epoch,deadline));
        byte[] key;
        try{key=Base64.getDecoder().decode(requiredString(document,"signing_pubkey").trim());}catch(IllegalArgumentException invalid){throw new IOException("Invalid shortname signing key");}
        if(key.length!=32)throw new IOException("Invalid shortname signing key");
        synchronized(KEY_LOCK){cachedKey=key.clone();cachedAt=SystemClock.elapsedRealtime();}
        return key;
    }
    static final class RequestGuard {
        private final int epoch;
        private final long deadline;
        private final Runnable disconnect;
        private final AtomicBoolean timedOut=new AtomicBoolean(),cancelled=new AtomicBoolean();
        private final ScheduledFuture<?> timer;
        RequestGuard(int epoch,long deadline,Runnable disconnect){
            this.epoch=epoch;this.deadline=deadline;this.disconnect=disconnect;
            long remaining=Math.max(0,deadline-System.nanoTime());
            timer=DEADLINES.schedule(()->cancel(true),remaining,TimeUnit.NANOSECONDS);
        }
        void cancel(boolean timeout){if(timeout)timedOut.set(true);else cancelled.set(true);disconnect.run();}
        void check()throws IOException {
            if(cancelled.get()||epoch!=CANCEL_EPOCH.get())throw new IOException("Shortname lookup was cancelled");
            if(timedOut.get()||System.nanoTime()-deadline>=0)throw new IOException("Shortname lookup timed out");
        }
        void finish(){timer.cancel(false);ACTIVE.remove(this);}
    }
    static void cancelActive(){CANCEL_EPOCH.incrementAndGet();for(RequestGuard guard:ACTIVE.keySet())guard.cancel(false);}
    static void cancelDisallowed(Context context){
        Network allowed=SyncNetworkPolicy.allowedNetwork(context);
        for(Network inUse:ACTIVE.values())if(allowed==null||!allowed.equals(inUse)){cancelActive();return;}
    }
    private void requireNetwork(Network expected)throws IOException{
        Network allowed=SyncNetworkPolicy.allowedNetwork(context);
        if(allowed==null||!expected.equals(allowed))throw new IOException("The permitted connection changed. Try the shortname lookup again.");
    }
    private String get(Network network,String path,int epoch,long deadline)throws IOException{
        requireEpoch(epoch);
        requireNetwork(network);
        HttpsURLConnection connection=null;
        RequestGuard guard=null;
        try{
            requireEpoch(epoch);
            connection=(HttpsURLConnection)network.openConnection(new URL(ORIGIN+path),Proxy.NO_PROXY);
            HttpsURLConnection active=connection;
            guard=new RequestGuard(epoch,deadline,active::disconnect);ACTIVE.put(guard,network);
            guard.check();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");connection.setRequestProperty("Accept","application/json");
            int status=connection.getResponseCode();
            guard.check();
            requireNetwork(network);
            if(status==404&&path.startsWith("/api/v1/resolve/"))throw new NotFoundException();
            if(status!=200)throw new IOException("Shortname service returned HTTP "+status);
            if(connection.getContentLengthLong()>MAX_RESPONSE)throw new IOException("Shortname response exceeds the size limit");
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()){
                byte[] buffer=new byte[4096];int count;
                while((count=in.read(buffer))!=-1){guard.check();requireNetwork(network);if(count>MAX_RESPONSE-output.size())throw new IOException("Shortname response exceeds the size limit");output.write(buffer,0,count);}
            }
            guard.check();
            requireNetwork(network);
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
        }catch(IOException failure){if(guard!=null)guard.check();throw failure;}
        finally{if(guard!=null)guard.finish();if(connection!=null)connection.disconnect();}
    }
}
