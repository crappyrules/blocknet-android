package com.blocknet.wallet;

import org.junit.Test;
import static org.junit.Assert.*;

/** A completed API read may belong to the network that just disappeared. */
public class WalletRepositoryOfflineTransitionTest {
    @Test public void wifiToBlockedCellularRejectsInFlightLiveValues() {
        assertTrue(WalletRepository.sameLiveSession(true,true,true,true,7,7,
            "mainnet","mainnet","main.wallet.dat","main.wallet.dat"));
        assertFalse(WalletRepository.sameLiveSession(false,true,true,true,7,7,
            "mainnet","mainnet","main.wallet.dat","main.wallet.dat"));
        assertFalse(WalletRepository.sameLiveSession(true,true,true,false,7,7,
            "mainnet","mainnet","main.wallet.dat","main.wallet.dat"));
    }

    @Test public void stopAndForegroundPauseRejectInFlightLiveValues() {
        assertFalse(WalletRepository.sameLiveSession(true,false,true,true,7,7,
            "mainnet","mainnet","main.wallet.dat","main.wallet.dat"));
        assertFalse(WalletRepository.sameLiveSession(true,true,false,true,7,7,
            "mainnet","mainnet","main.wallet.dat","main.wallet.dat"));
    }

    @Test public void stopAndForegroundPauseBlockPaymentEvenWhileCoreIsAlive() {
        assertTrue(WalletRepository.sendSessionAllowed(true,true,true));
        assertFalse(WalletRepository.sendSessionAllowed(true,false,true));
        assertFalse(WalletRepository.sendSessionAllowed(true,true,false));
        assertFalse(WalletRepository.sendSessionAllowed(false,true,true));
    }

    @Test public void scanningOrIncompleteBalanceCannotReplaceLastSavedView() throws Exception {
        assertFalse(WalletRepository.completeBalanceForCache(true,false,false,false,true));
        assertFalse(WalletRepository.completeBalanceForCache(false,true,true,false,true));
        assertTrue(WalletRepository.completeBalanceForCache(false,true,true,true,true));
    }

    @Test public void reconnectOrWalletChangeRejectsOldResponse() {
        assertFalse(WalletRepository.sameLiveSession(true,true,true,true,7,8,
            "mainnet","mainnet","main.wallet.dat","main.wallet.dat"));
        assertFalse(WalletRepository.sameLiveSession(true,true,true,true,7,7,
            "mainnet","testnet","main.wallet.dat","main.wallet.dat"));
        assertFalse(WalletRepository.sameLiveSession(true,true,true,true,7,7,
            "mainnet","mainnet","main.wallet.dat","other.wallet.dat"));
    }

    @Test public void blockedCellularStillRoutesToLocalUnlockOrAuthenticatedCache() {
        assertEquals(WalletEntryRoute.Screen.UNLOCK,WalletEntryRoute.decide(true,false,true,
            false,false,false,false,true));
        assertEquals(WalletEntryRoute.Screen.WALLET,WalletEntryRoute.decide(true,false,true,
            true,false,true,true,true));
        assertEquals(WalletEntryRoute.Screen.STOPPED,WalletEntryRoute.decide(false,false,false,
            false,false,false,false,false));
    }
}
