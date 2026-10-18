package com.blocknet.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Failure injection against an in-process loopback stub; no core, wallets, or payments. */
public final class WalletIdentityInstrumentation extends Instrumentation {
    private int checks;private volatile boolean running,failActivation=true,sawMarker;private ServerSocket server;private Thread thread;private NativeRuntime runtime;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;Bundle status=new Bundle();status.putString("stream","PASS: "+label+"\n");sendStatus(0,status);}
    private static String line(InputStream in)throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;while((b=in.read())!=-1){if(b=='\n')break;if(b!='\r')bytes.write(b);if(bytes.size()>8192)throw new IOException("Header too long");}return bytes.toString("UTF-8");}
    private void serve(){
        while(running)try(Socket socket=server.accept()){
            socket.setSoTimeout(5000);InputStream in=socket.getInputStream();String[] request=line(in).split(" ");int length=0;String header;
            while(!(header=line(in)).isEmpty()){if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(15).trim());}
            if(length>8192)throw new IOException("Unexpected request body");byte[] body=new byte[length];int off=0,n;while(off<length&&(n=in.read(body,off,length-off))>0)off+=n;
            int code=200;JSONObject response=new JSONObject();String path=request[1];
            if(path.equals("/api/wallet/balance")){code=503;response.put("error","no wallet loaded");}
            else if(path.equals("/api/wallet/create")){JSONObject data=new JSONObject(new String(body,StandardCharsets.UTF_8));sawMarker=runtime.prefs.contains("wallet-change.mainnet");response.put("filename",data.getString("filename")).put("created",true);}
            else if(path.equals("/api/settings")){if(failActivation){code=500;response.put("error","injected activation failure");}else response.put("settings",new JSONObject());}
            else {code=404;response.put("error","unexpected route");}
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+code+" Test\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }catch(Exception e){if(running)running=false;}
    }
    @Override public void onStart(){
        Bundle result=new Bundle();boolean success=false,ownsFixture=false;runtime=NativeRuntime.get(getTargetContext());WalletRepository repo=new WalletRepository(getTargetContext());
        Map<String,?> prefs=new HashMap<>(runtime.prefs.getAll());byte[] cookie=null;boolean cookieExisted=runtime.cookie().exists();byte[] previousPid=null;boolean pidExisted=runtime.pidFile().exists();
        boolean dataExisted=runtime.dataDir().exists(),walletDirectoryExisted=new File(runtime.networkDir(),"wallets").exists();
        try{
            check(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"),"Failure injection is restricted to an Android emulator");
            check(runtime.network().equals("mainnet")&&!runtime.alive(),"Mainnet selected and no real core is running");
            check(!repo.hasPendingSend()&&!runtime.prefs.contains("wallet-change.mainnet"),"No existing wallet operation or payment is affected");
            if(cookieExisted)cookie=Files.readAllBytes(runtime.cookie().toPath());if(pidExisted)previousPid=Files.readAllBytes(runtime.pidFile().toPath());ownsFixture=true;
            server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));runtime.prefs.edit().putInt("port.mainnet",server.getLocalPort()).commit();runtime.dataDir().mkdirs();Files.write(runtime.cookie().toPath(),"fake-loopback-test-token".getBytes(StandardCharsets.US_ASCII));running=true;thread=new Thread(this::serve,"wallet-identity-fake-api");thread.start();
            String name="identity_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
            boolean failed=false;try{repo.create(name,"failure-injection-password");}catch(WalletApi.ApiException expected){failed=expected.status==500;}
            check(failed&&sawMarker&&runtime.prefs.contains("wallet-change.mainnet"),"Activation failure retains durable marker recorded before fake wallet open");
            boolean blocked=false;try{repo.exportBackup(new ByteArrayOutputStream());}catch(IOException expected){blocked=true;}check(blocked,"Incomplete identity blocks backup before any file is copied");
            blocked=false;try{repo.previewSend("unused","1","");}catch(IOException expected){blocked=true;}check(blocked,"Incomplete identity blocks payment preview");
            blocked=false;try{repo.send("unused","1","");}catch(IOException expected){blocked=true;}check(blocked,"Incomplete identity blocks actual payment submission");
            failActivation=false;Files.write(runtime.cookie().toPath(),"fake-loopback-test-token".getBytes(StandardCharsets.US_ASCII));
            String recovered=name+"_ok";repo.create(recovered,"failure-injection-password");
            check(!runtime.prefs.contains("wallet-change.mainnet")&&repo.activeWallet().equals(recovered+".wallet.dat"),"Successful explicit activation selects matching filename and clears marker");
            check(!runtime.alive(),"Fake-loopback test never starts an actual core");success=true;
        }catch(Throwable failure){result.putString("stream","FAIL after "+checks+" identity checks ("+failure.getClass().getSimpleName()+").\n");}
        finally{
            running=false;try{if(server!=null)server.close();if(thread!=null)thread.join(2000);}catch(Exception ignored){}
            if(ownsFixture)try{
                if(cookieExisted)Files.write(runtime.cookie().toPath(),cookie);else Files.deleteIfExists(runtime.cookie().toPath());
                if(pidExisted)Files.write(runtime.pidFile().toPath(),previousPid);else Files.deleteIfExists(runtime.pidFile().toPath());
                if(cookieExisted)android.system.Os.chmod(runtime.cookie().getPath(),0600);
                if(pidExisted)android.system.Os.chmod(runtime.pidFile().getPath(),0600);
                File wallets=new File(runtime.networkDir(),"wallets");
                if(!walletDirectoryExisted&&wallets.exists()&&Objects.requireNonNull(wallets.list()).length==0)Files.delete(wallets.toPath());
                if(!dataExisted&&runtime.dataDir().exists()&&Objects.requireNonNull(runtime.dataDir().list()).length==0)Files.delete(runtime.dataDir().toPath());
                SharedPreferences.Editor edit=runtime.prefs.edit();for(String key:new String[]{"port.mainnet","active.mainnet","wallet-change.mainnet"}){Object old=prefs.get(key);if(old==null)edit.remove(key);else if(old instanceof Integer)edit.putInt(key,(Integer)old);else edit.putString(key,(String)old);}if(!edit.commit())throw new IOException("Preference restore failed");
            }catch(Exception cleanup){success=false;result.putString("stream","FAIL fixture cleanup ("+cleanup.getClass().getSimpleName()+").\n");}
        }
        if(success)result.putString("stream","OK ("+checks+" identity failure-injection checks). Fake loopback only; no wallets or payments created.\n");finish(success?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
