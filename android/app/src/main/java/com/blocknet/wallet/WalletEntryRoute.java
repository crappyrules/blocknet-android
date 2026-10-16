package com.blocknet.wallet;

/** Entry routing keeps a saved view reachable even when network sync is disallowed. */
final class WalletEntryRoute {
    enum Screen { STOPPED, STARTING, ONBOARDING, UNLOCK, WALLET }

    static Screen decide(boolean running, boolean syncAllowed, boolean statusReady,
                         boolean loaded, boolean locked, boolean cached, boolean cacheAvailable,
                         boolean localWalletAvailable) {
        if (cacheAvailable && locked) return Screen.UNLOCK;
        if (cached) return Screen.WALLET;
        if (localWalletAvailable && (!running || !syncAllowed)) return Screen.UNLOCK;
        if (!running || !syncAllowed) return Screen.STOPPED;
        if (!statusReady) return Screen.STARTING;
        if (!loaded) return Screen.ONBOARDING;
        return locked ? Screen.UNLOCK : Screen.WALLET;
    }

    private WalletEntryRoute() { }
}
