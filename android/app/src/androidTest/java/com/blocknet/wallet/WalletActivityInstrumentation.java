package com.blocknet.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Emulator-only real loopback transport with synthetic activity; no node or wallet is opened. */
public final class WalletActivityInstrumentation extends Instrumentation {
    private int checks;private volatile boolean running;private volatile String scenario="valid";
    private volatile int requests,otherRoutes;private volatile Throwable serverFailure;
    private ServerSocket server;private Thread serverThread;private String identity;private Method activityPage;private TransactionNotifier notifier;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;Bundle status=new Bundle();status.putString("stream","PASS: "+label+"\n");sendStatus(0,status);}
    private static String hash(String text)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String line(InputStream in)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;while((b=in.read())!=-1){if(b=='\n')break;if(b!='\r')bytes.write(b);if(bytes.size()>8192)throw new IOException("Oversized synthetic header");}return bytes.toString("UTF-8");}
    private JSONObject page(String kind,int offset,String mode)throws Exception{
        int count=mode.startsWith("multi")?(offset==0?100:1):mode.equals("empty")?0:1;
        JSONArray rows=new JSONArray();for(int i=0;i<count;i++){int index=offset+i;JSONObject row=new JSONObject().put("txid",String.format(Locale.ROOT,"%064x",index+1)).put("direction",kind.equals("outputs")?"received":"sent");if(kind.equals("outputs"))row.put("output_index",index).put("amount",100L+index).put("block_height",10);else row.put("total_amount",100L+index).put("recorded_height",10).put("timestamp",100L+index);rows.put(row);}
        JSONObject response=new JSONObject().put("version",1).put("wallet_id",identity).put("locked",true).put("scanning",false).put("synced_height",10).put("chain_height",10).put("kind",kind).put("count",count).put("total",mode.startsWith("multi")?101:count).put("limit",100).put("offset",offset).put("order","desc").put(kind,rows);
        switch(mode){
            case "wrong-id":response.put("wallet_id",hash("different synthetic wallet"));break;
            case "multi-wrong-id":if(offset>0)response.put("wallet_id",hash("different synthetic wallet"));break;
            case "scanning":response.put("scanning",true);break;
            case "behind":response.put("synced_height",9);break;
            case "count":response.put("count",count+1);break;
            case "offset":response.put("offset",offset+1);break;
            case "limit":response.put("limit",99);break;
            case "order":response.put("order","asc");break;
            case "version":response.put("version",2);break;
            case "missing-height":response.remove("chain_height");break;
            case "truncated":response.put("total",101);break;
        }
        return response;
    }
    private void serve(){while(running)try(Socket socket=server.accept()){
        socket.setSoTimeout(5000);InputStream in=socket.getInputStream();String request=line(in),header;boolean authorized=false;
        while(!(header=line(in)).isEmpty())if(header.equalsIgnoreCase("Authorization: Bearer synthetic-activity-token"))authorized=true;
        String[] parts=request.split(" ");URI uri=URI.create(parts[1]);String path=uri.getPath();int code=200;JSONObject response;
        requests++;
        if(!parts[0].equals("GET")||!path.equals("/api/wallet/activity")){otherRoutes++;code=404;response=new JSONObject().put("error","unexpected fixture route");}
        else if(!authorized){code=401;response=new JSONObject().put("error","synthetic authentication denied");}
        else if(scenario.equals("unloaded")){code=503;response=new JSONObject().put("error","no wallet loaded");}
        else if(scenario.equals("unsupported")){code=404;response=new JSONObject().put("error","unsupported endpoint");}
        else{String kind="",offset="";for(String pair:uri.getRawQuery().split("&")){String[] field=pair.split("=",2);if(field[0].equals("kind"))kind=field[1];if(field[0].equals("offset"))offset=field[1];}response=page(kind,Integer.parseInt(offset),scenario);}
        byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+code+" Synthetic\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
    }catch(Throwable failure){if(running){serverFailure=failure;running=false;}}}
    private JSONArray fetch(String kind)throws Exception{try{return (JSONArray)activityPage.invoke(notifier,kind,identity);}catch(InvocationTargetException wrapped){Throwable cause=wrapped.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new AssertionError(cause);}}
    private void rejected(String mode,String label)throws Exception{scenario=mode;boolean rejected=false;try{fetch("outputs");}catch(IOException|org.json.JSONException expected){rejected=true;}check(rejected,label);}
    @Override public void onStart(){
        Bundle result=new Bundle();boolean success=false,ownsFixture=false;NativeRuntime runtime=NativeRuntime.get(getTargetContext());
        SharedPreferences prefs=runtime.prefs;boolean hadPort=prefs.contains("port.mainnet");int oldPort=prefs.getInt("port.mainnet",0);
        File cookie=runtime.cookie(),data=runtime.dataDir(),network=runtime.networkDir();boolean hadCookie=cookie.exists(),hadData=data.exists(),hadNetwork=network.exists();byte[] oldCookie=null;int oldMode=0600;String bindingKey=null;
        try{
            check(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"),"Activity feed fixture is restricted to an emulator");
            check(runtime.network().equals("mainnet")&&!runtime.alive()&&!runtime.syncRequested(),"Mainnet selected and real core/sync requests are stopped");
            if(hadCookie){oldCookie=Files.readAllBytes(cookie.toPath());oldMode=Os.stat(cookie.getPath()).st_mode&0777;}
            String wallet="activity_"+UUID.randomUUID().toString().replace("-","")+".wallet.dat",address="1".repeat(70);
            bindingKey="activity-wallet-id."+hash("mainnet\0"+wallet);check(!prefs.contains(bindingKey),"Synthetic binding cannot replace an existing wallet binding");
            ownsFixture=true;TransactionNotifier.rememberWallet(getTargetContext(),"mainnet",wallet,address);identity=hash("blocknet-wallet-activity-v1\0"+address);
            check(identity.equals(prefs.getString(bindingKey,"")),"Remembered authoritative address uses the exact Core activity identity domain");
            server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));if(!prefs.edit().putInt("port.mainnet",server.getLocalPort()).commit())throw new IOException("Cannot set fixture port");
            if(!data.isDirectory()&&!data.mkdirs())throw new IOException("Cannot prepare fixture cookie directory");Files.write(cookie.toPath(),"synthetic-activity-token".getBytes(StandardCharsets.US_ASCII));Os.chmod(cookie.getPath(),0600);
            running=true;serverThread=new Thread(this::serve,"synthetic-wallet-activity");serverThread.start();
            notifier=new TransactionNotifier(getTargetContext());activityPage=TransactionNotifier.class.getDeclaredMethod("activityPage",String.class,String.class);activityPage.setAccessible(true);
            scenario="valid";JSONArray outputs=fetch("outputs");check(outputs.length()==1&&outputs.getJSONObject(0).getLong("amount")==100,"Actual WalletApi accepts a bound locked-wallet output snapshot");
            check(fetch("sends").getJSONObject(0).getLong("total_amount")==100,"Locked-wallet send projection crosses real Android JSON transport");
            scenario="empty";check(fetch("outputs").length()==0,"Valid empty locked-wallet snapshot is accepted");
            scenario="multi";outputs=fetch("outputs");check(outputs.length()==101&&outputs.getJSONObject(100).getInt("output_index")==100,"Two pages retain wallet binding and append their actual output records");
            rejected("wrong-id","Different wallet identity is rejected before observation");
            rejected("multi-wrong-id","Wallet identity change on page two rejects the entire observation");
            rejected("scanning","Scanning wallet snapshot is rejected");rejected("behind","Wallet scan behind chain is rejected");
            rejected("count","Inconsistent page count is rejected");rejected("offset","Unexpected page offset is rejected");rejected("limit","Unexpected server page limit is rejected");rejected("order","Unexpected sort order is rejected");rejected("version","Unsupported activity contract version is rejected");rejected("missing-height","Missing scan metadata is rejected");rejected("truncated","Truncated nonfinal page is rejected");
            scenario="valid";Files.write(cookie.toPath(),"wrong-synthetic-token".getBytes(StandardCharsets.US_ASCII));int before=requests;boolean denied=false;try{fetch("outputs");}catch(WalletApi.ApiException expected){denied=expected.status==401;}check(denied&&requests==before+1,"Actual cookie authorization failure propagates401 without another endpoint request");
            Files.write(cookie.toPath(),"synthetic-activity-token".getBytes(StandardCharsets.US_ASCII));scenario="unloaded";before=requests;boolean unloaded=false;try{fetch("outputs");}catch(WalletApi.ApiException expected){unloaded=expected.status==503;}check(unloaded&&requests==before+1,"Unloaded503 propagates without fallback or wallet loading");
            scenario="unsupported";boolean unsupported=false;try{fetch("outputs");}catch(WalletApi.ApiException expected){unsupported=expected.status==404;}check(unsupported,"Unsupported endpoint retains404 for caller compatibility handling");
            check(otherRoutes==0&&serverFailure==null&&!runtime.alive()&&!runtime.syncRequested(),"Only authenticated read-only activity requests occurred; no real core was started");success=true;
        }catch(Throwable failure){result.putString("stream","FAIL after "+checks+" activity checks ("+failure.getClass().getSimpleName()+": "+failure.getMessage()+").\n");}
        finally{
            running=false;try{if(server!=null)server.close();if(serverThread!=null)serverThread.join(2000);}catch(Exception ignored){}
            if(ownsFixture)try{
                if(hadCookie){Files.write(cookie.toPath(),oldCookie);Os.chmod(cookie.getPath(),oldMode);}else Files.deleteIfExists(cookie.toPath());
                SharedPreferences.Editor edit=prefs.edit().remove(bindingKey);if(hadPort)edit.putInt("port.mainnet",oldPort);else edit.remove("port.mainnet");if(!edit.commit())throw new IOException("Cannot restore fixture preferences");
                if(!hadData&&data.exists()&&data.list()!=null&&data.list().length==0)Files.delete(data.toPath());
                if(!hadNetwork&&network.exists()&&network.list()!=null&&network.list().length==0)Files.delete(network.toPath());
                check(!prefs.contains(bindingKey)&&prefs.contains("port.mainnet")==hadPort&&prefs.getInt("port.mainnet",0)==oldPort,"Own binding/port fixture restored without modifying wallet preferences");
            }catch(Throwable cleanup){success=false;result.putString("stream","FAIL activity fixture cleanup ("+cleanup.getClass().getSimpleName()+").\n");}
        }
        if(success)result.putString("stream","OK ("+checks+" locked activity loopback checks). No actual node, wallet, funds, or testnet used.\n");finish(success?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
