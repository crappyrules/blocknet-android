package com.blocknet.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Optional transport integration against an isolated exact-binary ARM testnet core via adb reverse. */
public final class WalletApiInstrumentation extends Instrumentation {
    private int checks;
    private Bundle arguments;
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args;start();}
    private void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;Bundle b=new Bundle();b.putString("stream","PASS: "+name+"\n");sendStatus(0,b);}
    @Override public void onStart(){
        Bundle result=new Bundle();NativeRuntime runtime=NativeRuntime.get(getTargetContext());
        String previousNetwork=runtime.network();int previousPort=runtime.prefs.getInt("port.testnet",0);
        byte[] previousCookie=null;boolean cookieExisted=false;boolean created=false;
        try{
            check(!runtime.alive(),"App-owned node remains stopped during external core test");
            runtime.prefs.edit().putString("network","testnet").putInt("port.testnet",Integer.parseInt(arguments.getString("port","18392"))).commit();
            runtime.dataDir().mkdirs();cookieExisted=runtime.cookie().exists();if(cookieExisted)previousCookie=Files.readAllBytes(runtime.cookie().toPath());
            byte[] cookie=Files.readAllBytes(new java.io.File(getTargetContext().getFilesDir(),"integration-cookie").toPath());
            Files.write(runtime.cookie().toPath(),cookie);
            WalletApi api=new WalletApi(runtime);
            check(api.get("/api/status").has("chain_height"),"Android WalletApi reads exact ARM core status through loopback");
            Files.write(runtime.cookie().toPath(),"invalid-test-token".getBytes(StandardCharsets.UTF_8));
            try{api.get("/api/wallet/balance");throw new AssertionError("Invalid token accepted");}catch(WalletApi.ApiException e){check(e.status==401,"Android WalletApi preserves authentication failure status");}
            Files.write(runtime.cookie().toPath(),cookie);
            String password="Android-transport-test-"+UUID.randomUUID();
            api.post("/api/wallet/create",new JSONObject().put("filename","android-transport-"+UUID.randomUUID()+".wallet.dat").put("password",password));created=true;
            String address=api.get("/api/wallet/address").getString("address");
            check(!address.isEmpty(),"Android WalletApi creates an empty encrypted test wallet and reads address");
            JSONObject balance=api.get("/api/wallet/balance");check(balance.getLong("spendable")==0,"Android WalletApi reads zero spendable test balance");
            JSONObject signed=api.post("/api/wallet/sign",new JSONObject().put("message","Android transport integration"));
            JSONObject verified=api.post("/api/verify",new JSONObject().put("address",address).put("message","Android transport integration").put("signature",signed.getString("signature")));
            check(verified.optBoolean("valid"),"Android WalletApi signs and verifies through real core");
            api.post("/api/wallet/unload",null);created=false;
            check(!runtime.alive(),"External transport test never claimed an app-owned core process");
            result.putString("stream","OK ("+checks+" exact-core transport checks). No payments broadcast.\n");
        }catch(Throwable e){result.putString("stream","FAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(e));}
        finally{
            try{if(created)new WalletApi(runtime).post("/api/wallet/unload",null);}catch(Exception ignored){}
            try{if(cookieExisted)Files.write(runtime.cookie().toPath(),previousCookie);else runtime.cookie().delete();}catch(Exception ignored){}
            new java.io.File(getTargetContext().getFilesDir(),"integration-cookie").delete();
            runtime.prefs.edit().putString("network",previousNetwork).putInt("port.testnet",previousPort).commit();
        }
        finish(result.getString("stream","").startsWith("OK")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
