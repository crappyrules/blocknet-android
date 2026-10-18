package com.blocknet.wallet;

import org.junit.Test;
import static org.junit.Assert.*;

public class WalletRepositoryLockPolicyTest {
    @Test public void onlyAnUnloadedWalletCountsAsAlreadyLocked() {
        assertTrue(WalletRepository.lockAlreadySatisfied(new WalletApi.ApiException(503,"no wallet loaded")));
        assertFalse(WalletRepository.lockAlreadySatisfied(new WalletApi.ApiException(503,"wallet database unavailable")));
        assertFalse(WalletRepository.lockAlreadySatisfied(new WalletApi.ApiException(401,"no wallet loaded")));
        assertFalse(WalletRepository.lockAlreadySatisfied(new WalletApi.ApiException(403,"no wallet loaded")));
        assertFalse(WalletRepository.lockAlreadySatisfied(new WalletApi.ApiException(500,"no wallet loaded")));
    }
}
