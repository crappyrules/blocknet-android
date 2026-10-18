package com.blocknet.wallet;

import org.junit.Test;
import static org.junit.Assert.*;

public class WalletViewportPolicyTest {
    @Test public void passiveRefreshWaitsForTouchAndEditingThenRendersOnce() {
        assertFalse(WalletViewportPolicy.shouldRender(false,false,true,true,false));
        assertFalse(WalletViewportPolicy.shouldRender(false,true,false,true,false));
        assertFalse(WalletViewportPolicy.shouldRender(false,false,true,false,true));
        assertFalse(WalletViewportPolicy.shouldRender(false,true,false,false,true));
        assertTrue(WalletViewportPolicy.shouldRender(false,false,false,false,true));
        assertFalse(WalletViewportPolicy.shouldRender(false,false,false,false,false));
    }

    @Test public void privacyTransitionOverridesActiveTouchOrEdit() {
        assertTrue(WalletViewportPolicy.shouldRender(true,true,true,false,false));
    }
}
