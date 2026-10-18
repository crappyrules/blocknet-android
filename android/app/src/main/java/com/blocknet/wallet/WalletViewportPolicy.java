package com.blocknet.wallet;

/** Keep passive refreshes from replacing a view while someone is using it. */
final class WalletViewportPolicy {
    private WalletViewportPolicy() {}

    static boolean shouldRender(boolean privacyChange, boolean editing, boolean touching,
                                boolean contentChanged, boolean deferred) {
        return privacyChange || (!editing && !touching && (contentChanged || deferred));
    }
}
