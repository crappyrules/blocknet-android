package com.blocknet.wallet;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WalletEntryRouteTest {
    @Test public void cellularBlockedColdLaunchStillOffersLocalAuthentication() {
        assertEquals(WalletEntryRoute.Screen.UNLOCK,
            WalletEntryRoute.decide(false,false,false,true,true,false,true,true));
        assertEquals(WalletEntryRoute.Screen.WALLET,
            WalletEntryRoute.decide(false,false,false,true,false,true,true,true));
    }

    @Test public void existingWalletWithoutSnapshotCanUnlockWithoutPermittedNetwork() {
        assertEquals(WalletEntryRoute.Screen.UNLOCK,
            WalletEntryRoute.decide(false,false,false,false,false,false,false,true));
    }

    @Test public void firstRunWithoutWalletShowsStoppedNodeAndReconnectUsesLiveUnlock() {
        assertEquals(WalletEntryRoute.Screen.STOPPED,
            WalletEntryRoute.decide(false,false,false,false,false,false,false,false));
        assertEquals(WalletEntryRoute.Screen.UNLOCK,
            WalletEntryRoute.decide(true,true,true,true,true,false,true,true));
        assertEquals(WalletEntryRoute.Screen.ONBOARDING,
            WalletEntryRoute.decide(true,true,true,false,false,false,false,false));
    }
}
