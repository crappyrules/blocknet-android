package com.blocknet.wallet;

import android.content.Context;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Public BNT/USDT last-trade indicator, scoped to a visible unlocked wallet. */
final class SafeTradePrice {
    static final String ENDPOINT="https://safe.trade/api/v2/trade/public/tickers/bntusdt";
    interface Listener { void changed(Quote quote,String error); }
    static final class Quote {
        final BigDecimal last;
        final long fetchedElapsed;
        Quote(BigDecimal last,long fetchedElapsed){this.last=last;this.fetchedElapsed=fetchedElapsed;}
        BigDecimal estimatedUsdt(long atomicUnits){return last.multiply(BigDecimal.valueOf(atomicUnits,8));}
    }
    private final Context context;
    private final BooleanSupplier sessionAllowed;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor io=new ScheduledThreadPoolExecutor(1,r->{Thread thread=new Thread(r,"public-bnt-price");thread.setDaemon(true);return thread;});
    private ScheduledFuture<?> next;
    private HttpURLConnection connection;
    private boolean enabled;
    private int epoch,failures;
    SafeTradePrice(Context context,BooleanSupplier sessionAllowed,Listener listener){this.context=context.getApplicationContext();this.sessionAllowed=sessionAllowed;this.listener=listener;io.setRemoveOnCancelPolicy(true);}
    synchronized void setEnabled(boolean allow){
        if(allow==enabled)return;
        enabled=allow;epoch++;failures=0;
        if(next!=null)next.cancel(true);
        if(connection!=null)connection.disconnect();
        if(allow)schedule(epoch,0);
    }
    synchronized void close(){setEnabled(false);io.shutdownNow();}
    private synchronized void schedule(int token,long delay){
        if(enabled&&token==epoch&&!io.isShutdown())next=io.schedule(()->poll(token),delay,TimeUnit.MILLISECONDS);
    }
    private void poll(int token){
        Quote quote=null;String error="";long retry=10_000;
        try {
            Network network=SyncNetworkPolicy.allowedNetwork(context);
            if(network==null||!MobileCorePolicy.permitted(context)||!sessionAllowed.getAsBoolean())
                throw new IOException("Price paused: wallet or permitted internet unavailable");
            quote=fetch(network,token);
            synchronized(this){if(token==epoch)failures=0;}
        } catch(Exception failure) {
            error=failure.getMessage()==null?"Price unavailable":failure.getMessage();
            synchronized(this){if(token==epoch){failures=Math.min(failures+1,6);retry=Math.min(300_000,10_000L<<failures);}}
            if(failure instanceof RetryLater)retry=Math.max(retry,((RetryLater)failure).delayMs);
        }
        final Quote result=quote;final String message=error;
        main.post(()->{synchronized(SafeTradePrice.this){if(!enabled||token!=epoch)return;}listener.changed(result,message);});
        schedule(token,retry);
    }
    private static final class RetryLater extends IOException {
        final long delayMs;
        RetryLater(long delayMs){super("Price temporarily unavailable");this.delayMs=delayMs;}
    }
    private Quote fetch(Network network,int token) throws Exception {
        HttpURLConnection c=(HttpURLConnection)network.openConnection(new URL(ENDPOINT),Proxy.NO_PROXY);
        synchronized(this){if(!enabled||token!=epoch){c.disconnect();throw new IOException("Price paused");}connection=c;}
        try {
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(5000);
            c.setRequestMethod("GET");c.setRequestProperty("Accept","application/json");
            if(!sessionAllowed.getAsBoolean()||!MobileCorePolicy.permitted(context))throw new IOException("Price paused");
            int status=c.getResponseCode();
            if(status==429){long seconds=30;try{seconds=Long.parseLong(c.getHeaderField("Retry-After"));}catch(Exception ignored){}throw new RetryLater(Math.min(300,Math.max(10,seconds))*1000);}
            if(status!=200)throw new IOException("Price feed HTTP "+status);
            JSONObject body=new JSONObject(WalletApi.readBounded(c.getInputStream(),8192));
            return parse(body,SystemClock.elapsedRealtime());
        } finally {synchronized(this){if(connection==c)connection=null;}c.disconnect();}
    }
    static Quote parse(JSONObject body,long fetchedElapsed) throws IOException {
        return parseFields(body.optString("id"),body.optString("base_unit"),body.optString("quote_unit"),
            body.optString("name"),body.optString("last"),body.optString("volume"),fetchedElapsed);
    }
    static Quote parseFields(String id,String base,String quote,String name,String lastValue,String volumeValue,long fetchedElapsed) throws IOException {
        if(!"bntusdt".equals(id)||!"bnt".equals(base)
            ||!"usdt".equals(quote)||!"BNT/USDT".equals(name))
            throw new IOException("Price market identity changed");
        try {
            BigDecimal last=new BigDecimal(lastValue);
            BigDecimal volume=new BigDecimal(volumeValue);
            if(last.signum()<=0||last.precision()>24||last.compareTo(new BigDecimal("1000000000"))>0
                ||volume.signum()<=0)throw new NumberFormatException();
            return new Quote(last,fetchedElapsed);
        }catch(Exception invalid){throw new IOException("Price feed has no usable last trade");}
    }
}
