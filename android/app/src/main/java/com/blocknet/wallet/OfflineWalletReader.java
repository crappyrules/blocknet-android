package com.blocknet.wallet;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Uses Core's wallet loader in a short-lived, network-free process. */
final class OfflineWalletReader {
    private static final int MAX_RESPONSE = 1024 * 1024;
    private final NativeRuntime runtime;

    OfflineWalletReader(Context context) { runtime = NativeRuntime.get(context); }

    JSONObject read(String wallet, String password) throws Exception {
        File directory=runtime.walletsDir().getCanonicalFile();
        File file=new File(directory,wallet).getCanonicalFile();
        if(!file.getParentFile().equals(directory)||!file.isFile())throw new IOException("Wallet file is unavailable.");
        File helper=runtime.binary("libblocknet_walletview.so");
        if(!helper.canExecute())throw new IOException("This build has no offline wallet reader.");
        byte[] secret=password.getBytes(StandardCharsets.UTF_8);
        if(secret.length==0||secret.length>4096){Arrays.fill(secret,(byte)0);throw new IOException("Wallet password length is unsupported.");}
        Process process=null;
        FutureTask<byte[]> response=null;
        try {
            process=new ProcessBuilder(helper.getAbsolutePath(),runtime.network(),file.getAbsolutePath())
                .directory(runtime.networkDir()).start();
            final InputStream stdout=process.getInputStream();
            response=new FutureTask<>(()->boundedRead(stdout));
            Thread reader=new Thread(response,"offline-wallet-response");reader.setDaemon(true);reader.start();
            try(DataOutputStream input=new DataOutputStream(process.getOutputStream())){
                input.writeInt(secret.length);input.write(secret);input.flush();
            }
            if(!process.waitFor(90,TimeUnit.SECONDS))throw new IOException("Offline wallet reader timed out.");
            byte[] result;
            try { result=response.get(2,TimeUnit.SECONDS); }
            catch(ExecutionException failure){throw new IOException("Offline wallet reader returned invalid data.",failure);}
            try {
                if(process.exitValue()!=0)throw new IOException("Incorrect wallet password or damaged wallet file.");
                JSONObject value=new JSONObject(new String(result,StandardCharsets.UTF_8));
                validate(value,file);
                return value;
            } finally { Arrays.fill(result,(byte)0); }
        } finally {
            Arrays.fill(secret,(byte)0);
            if(response!=null)response.cancel(true);
            if(process!=null){
                try { terminateAndWait(process); }
                finally {
                    try{process.getInputStream().close();}catch(IOException ignored){}
                    try{process.getErrorStream().close();}catch(IOException ignored){}
                }
            }
        }
    }

    static void terminateAndWait(Process process) throws IOException {
        if(process.isAlive())process.destroyForcibly();
        try {
            if(!process.waitFor(3,TimeUnit.SECONDS))
                throw new IOException("Offline wallet reader did not stop.");
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping offline wallet reader.",interrupted);
        }
    }

    private static byte[] boundedRead(InputStream input) throws IOException {
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count;
            while((count=in.read(buffer))!=-1){
                if(out.size()>MAX_RESPONSE-count)throw new IOException("Offline wallet view is too large.");
                out.write(buffer,0,count);
            }
            return out.toByteArray();
        }
    }

    private static void validate(JSONObject value,File file) throws Exception {
        if(value.getInt("version")!=1||value.getLong("savedAt")<=0||
            value.getLong("savedAt")!=file.lastModified()||
            value.getLong("spendable")<0||value.getLong("pending")<0||
            value.getLong("height")<0||value.getLong("walletHeight")<0||
            value.getString("address").isEmpty())throw new IOException("Offline wallet view is invalid.");
        value.getBoolean("viewOnly");
        if(value.getBoolean("chainHeightKnown"))throw new IOException("Offline reader cannot report a current chain height.");
        JSONArray history=value.getJSONArray("history"),sends=value.getJSONArray("sends");
        if(history.length()>100||sends.length()>100)throw new IOException("Offline wallet view is too large.");
        for(int i=0;i<history.length();i++){
            JSONObject item=history.getJSONObject(i);
            if(item.getLong("amount")<0||item.getLong("block_height")<0||item.getInt("output_index")<0)
                throw new IOException("Offline wallet activity is invalid.");
            item.getString("txid");
        }
        for(int i=0;i<sends.length();i++){
            JSONObject item=sends.getJSONObject(i);
            if(item.getLong("total_amount")<0||item.getLong("recorded_height")<0)
                throw new IOException("Offline wallet activity is invalid.");
            item.getString("txid");
        }
    }
}
