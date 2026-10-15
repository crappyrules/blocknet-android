package com.blocknet.wallet;

import org.junit.Test;
import static org.junit.Assert.*;

public class SyncNetworkPolicyTest {
    private SyncNetworkPolicy.State state(boolean wifi,boolean mobile,boolean vpn,boolean ethernet,boolean optIn){
        return SyncNetworkPolicy.evaluate(true,true,true,wifi,mobile,vpn,ethernet,optIn);
    }
    @Test public void defaultWiFiOnlyAllowsValidatedWiFi(){
        SyncNetworkPolicy.State s=state(true,false,false,false,false);
        assertTrue(s.connected);assertTrue(s.wifi);assertTrue(s.allowed);assertEquals("Wi-Fi",s.connectionLabel);
        assertFalse(state(false,true,false,false,false).allowed);
        assertFalse(state(false,false,false,true,false).allowed);
        assertFalse(state(false,false,false,false,false).allowed);
    }
    @Test public void vpnRequiresKnownExclusivelyWiFiUnderlyingNetwork(){
        SyncNetworkPolicy.State wifi=state(true,false,true,false,false);
        assertTrue(wifi.allowed);assertTrue(wifi.vpn);assertEquals("VPN over Wi-Fi",wifi.connectionLabel);
        SyncNetworkPolicy.State mobile=state(false,true,true,false,false);
        assertFalse(mobile.allowed);assertEquals("VPN over mobile",mobile.connectionLabel);
        SyncNetworkPolicy.State unknown=state(false,false,true,false,false);
        assertFalse(unknown.allowed);assertTrue(unknown.reason.contains("unknown"));
        assertFalse(state(true,true,true,false,false).allowed);
    }
    @Test public void mobileOptInPermitsEveryValidatedDefaultInternetTransport(){
        assertTrue(state(false,true,false,false,true).allowed);
        assertTrue(state(false,false,false,true,true).allowed);
        assertTrue(state(false,false,false,false,true).allowed);
        assertTrue(state(false,false,true,false,true).allowed);
        assertTrue(state(true,true,true,false,true).allowed);
    }
    @Test public void optInNeverBypassesInternetValidation(){
        for(boolean optIn:new boolean[]{false,true}){
            assertFalse(SyncNetworkPolicy.evaluate(true,true,false,true,false,false,false,optIn).allowed);
            assertFalse(SyncNetworkPolicy.evaluate(true,false,true,true,false,false,false,optIn).allowed);
            assertFalse(SyncNetworkPolicy.evaluate(false,true,true,true,false,false,false,optIn).allowed);
        }
    }
    @Test public void cellularCapabilityAlwaysBlocksWiFiOnlyEvenWhenWiFiAlsoPresent(){
        SyncNetworkPolicy.State s=state(true,true,false,false,false);
        assertFalse(s.allowed);assertTrue(s.cellular);assertTrue(s.reason.contains("mobile data is disabled"));
    }
    @Test public void labelsDistinguishEthernetAndUnavailableInternet(){
        assertEquals("Ethernet",state(false,false,false,true,true).connectionLabel);
        SyncNetworkPolicy.State missing=SyncNetworkPolicy.evaluate(false,false,false,false,false,false,false,false);
        assertFalse(missing.connected);assertFalse(missing.allowed);assertEquals("No internet",missing.connectionLabel);
        SyncNetworkPolicy.State captive=SyncNetworkPolicy.evaluate(true,true,false,true,false,false,false,true);
        assertFalse(captive.connected);assertTrue(captive.connectionLabel.contains("no internet"));assertTrue(captive.reason.contains("sign-in"));
    }
}
