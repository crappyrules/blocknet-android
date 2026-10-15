package com.blocknet.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Emulator-only signed public-fixture binding tests. Optional live lookup; no wallet, node, or payment. */
public final class ShortnameVerificationInstrumentation extends Instrumentation {
    private Bundle arguments;
    private int checks;
    private String stage="preconditions";
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args;start();}
    private void status(String message){Bundle update=new Bundle();update.putString("stream",message+"\n");sendStatus(0,update);}
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;status("PASS: "+message);}
    private JSONObject fixture(String name)throws Exception{
        try(InputStream in=getContext().getAssets().open("shortname-blocknet-public-"+name+".json")){
            ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;
            while((n=in.read(buffer))!=-1){if(data.size()+n>128*1024)throw new IOException("Fixture too large");data.write(buffer,0,n);}
            return new JSONObject(data.toString(StandardCharsets.UTF_8.name()));
        }
    }
    private JSONObject copy(JSONObject response)throws Exception{return new JSONObject(response.toString());}
    private void rejects(String requested,JSONObject response,byte[] key,String label)throws Exception{
        boolean denied=false;try{ShortnameResolver.verifyResponse(requested,response,key);}catch(IOException expected){denied=true;}
        check(denied,label);
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            check(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"),"Shortname fixture verification is emulator-only");
            stage="published signed fixture";
            JSONObject response=fixture("resolve"),wellKnown=fixture("wellknown");
            byte[] key=Base64.getDecoder().decode(wellKnown.getString("signing_pubkey"));
            check(key.length==32,"Published service fixture contains a raw 32-byte Ed25519 key");
            ShortnameResolver.Result valid=ShortnameResolver.verifyResponse("$blocknet",response,key);
            check(valid.verified&&valid.handle.equals("blocknet")&&valid.address.equals(response.getString("address")),"Android JSON and packaged Bouncy Castle verify the real published blocknet signature");
            stage="signed response binding rejection";
            rejects("other-handle",copy(response),key,"Signed payload rejects a different requested handle");
            rejects("blocknet",copy(response).put("handle","other-handle"),key,"Signed payload rejects an altered response handle");
            rejects("blocknet",copy(response).put("address",new String(new char[88]).replace('\0','A')),key,"Signed payload rejects an altered response address");
            rejects("blocknet",copy(response).put("updated_at",response.getLong("updated_at")+1),key,"Signed payload rejects an altered response timestamp");
            rejects("blocknet",copy(response).put("updated_at",Long.toString(response.getLong("updated_at"))),key,"Signed response timestamp must retain its integer JSON type");
            byte[] signature=Base64.getDecoder().decode(response.getString("sig"));signature[0]^=1;
            rejects("blocknet",copy(response).put("sig",Base64.getEncoder().encodeToString(signature)),key,"Altered Ed25519 signature is rejected");
            rejects("blocknet",copy(response).put("payload",response.getString("payload")+"\n"),key,"Signature verification uses exact raw payload bytes");
            byte[] wrongKey=key.clone();wrongKey[0]^=1;
            rejects("blocknet",copy(response),wrongKey,"Signed payload rejects an incorrect service public key");
            for(String field:new String[]{"payload","sig","handle","address","updated_at"}){
                JSONObject missing=copy(response);missing.remove(field);rejects("blocknet",missing,key,"Missing signed response field is rejected: "+field);
            }
            boolean live=arguments!=null&&Boolean.parseBoolean(arguments.getString("live","false"));
            if(live){
                stage="optional live pinned HTTPS lookup";
                SyncNetworkPolicy.State connection=SyncNetworkPolicy.read(getTargetContext());
                check(connection.allowed&&connection.wifi&&!connection.cellular,"Optional live lookup uses validated default Wi-Fi under sync policy");
                status("STAGE: OPTIONAL_LIVE_SHORTNAME_GET");
                ShortnameResolver.Result resolved=new ShortnameResolver(getTargetContext()).resolve("$blocknet");
                check(resolved.verified&&resolved.handle.equals("blocknet")&&resolved.address.matches("[1-9A-HJ-NP-Za-km-z]{64,100}"),"Live pinned HTTPS resolution verifies the published shortname signature and binding");
            }
            result.putString("stream","OK ("+checks+" Android signed-shortname checks; live="+live+"). No wallet, node, or payment used.\n");
        }catch(Throwable failure){result.putString("stream","FAIL at "+stage+" after "+checks+" checks ("+failure.getClass().getSimpleName()+"). No response parameters or keys logged.\n");}
        finish(result.getString("stream","").startsWith("OK")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
