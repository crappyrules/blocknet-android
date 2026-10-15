package com.blocknet.wallet;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.IOException;

/** Authorizes sync only on the validated default network. Mobile data requires explicit opt-in. */
public final class SyncNetworkPolicy {
    private static final String MOBILE_KEY="sync.allow-mobile-data";
    private SyncNetworkPolicy() { }

    public static final class State {
        public final boolean connected,wifi,cellular,vpn,allowed;
        public final String connectionLabel,reason;
        private State(boolean connected,boolean wifi,boolean cellular,boolean vpn,boolean allowed,String label,String reason){
            this.connected=connected;this.wifi=wifi;this.cellular=cellular;this.vpn=vpn;this.allowed=allowed;
            this.connectionLabel=label;this.reason=reason;
        }
    }

    /** Pure decision function; active means this is the system's active default network. */
    public static State evaluate(boolean active,boolean internet,boolean validated,boolean wifi,boolean cellular,boolean vpn,boolean ethernet,boolean allowMobile){
        boolean connected=active&&internet&&validated;
        String label;
        if(!active)label="No internet";
        else if(vpn)label=wifi&&!cellular?"VPN over Wi-Fi":cellular?"VPN over mobile":"VPN (underlying network unknown)";
        else if(cellular)label="Mobile data";
        else if(wifi)label="Wi-Fi";
        else if(ethernet)label="Ethernet";
        else label="Other connection";
        if(!active)return new State(false,false,false,false,false,label,"Sync paused: no active internet connection.");
        if(!internet)return new State(false,wifi,cellular,vpn,false,label+" · no internet","Sync paused: the active connection has no internet capability.");
        if(!validated)return new State(false,wifi,cellular,vpn,false,label+" · no internet","Sync paused: the active connection has not validated internet access. Complete any network sign-in first.");
        boolean allowed=allowMobile||(wifi&&!cellular);
        String reason;
        if(allowed)reason=allowMobile?"Sync allowed on the validated active connection; mobile data is enabled.":"Sync allowed on the validated Wi-Fi connection.";
        else if(vpn&&!wifi&&!cellular)reason="Sync paused: the VPN's underlying network is unknown. Wi-Fi-only sync cannot verify this connection.";
        else if(cellular)reason="Sync paused: mobile data is disabled. Connect to Wi-Fi or enable mobile data for sync.";
        else reason="Sync paused: Wi-Fi-only sync requires the active connection to use Wi-Fi.";
        return new State(connected,wifi,cellular,vpn,allowed,label,reason);
    }

    public static boolean allowMobileData(Context context){
        try{return context.getSharedPreferences("wallet",Context.MODE_PRIVATE).getBoolean(MOBILE_KEY,false);}
        catch(RuntimeException error){return false;}
    }
    public static void setAllowMobileData(Context context,boolean allow)throws IOException{
        try{if(!context.getSharedPreferences("wallet",Context.MODE_PRIVATE).edit().putBoolean(MOBILE_KEY,allow).commit())throw new IOException("Could not save mobile data preference");}
        catch(RuntimeException error){throw new IOException("Could not save mobile data preference",error);}
    }
    private static final class Snapshot {
        final Network network;final State state;
        Snapshot(Network network,State state){this.network=network;this.state=state;}
    }
    private static Snapshot snapshot(Context context){
        try{
            ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
            if(manager==null)return unavailable();
            Network network=manager.getActiveNetwork();
            if(network==null)return new Snapshot(null,evaluate(false,false,false,false,false,false,false,allowMobileData(context)));
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            if(caps==null)return unavailable();
            State state=evaluate(true,caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),allowMobileData(context));
            return new Snapshot(network,state);
        }catch(RuntimeException error){return unavailable();}
    }
    private static Snapshot unavailable(){return new Snapshot(null,new State(false,false,false,false,false,"No internet","Sync paused: active network status is unavailable."));}
    public static State read(Context context){return snapshot(context).state;}
    /** Returns the very same network whose capabilities were checked, for Network.openConnection. */
    public static Network allowedNetwork(Context context){Snapshot current=snapshot(context);return current.state.allowed?current.network:null;}
}
