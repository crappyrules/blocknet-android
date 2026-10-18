package com.blocknet.wallet;

import android.content.Context;

/** Public local-node status only. Never includes wallet, peer identity or API credentials. */
final class CoreDiagnostics {
    private CoreDiagnostics() { }

    private static String heading(String version, String network) {
        return "Bundled Core " + version + "\nNetwork: " + network + "\n";
    }

    static String stopped(String version, String network) {
        return heading(version, network) + "Node stopped. Chain status is unavailable.";
    }

    static String unavailable(String version, String network) {
        return heading(version, network) + "Node process is running; current chain status is unavailable.";
    }

    static String paused(String version, String network) {
        return heading(version, network) + "Sync is paused or stopping. Chain status may be stale.";
    }

    static String running(String version, String network, long height, int peers,
                          boolean syncing, long target) {
        String result = heading(version, network) + "Node running\nChain height: " + height
            + "\nPeers: " + peers + "\nSyncing: " + (syncing ? "yes" : "no");
        return syncing && target > 0 ? result + "\nSync target: " + target : result;
    }

    static boolean currentSession(boolean requested, boolean networkAllowed, boolean foregroundAllowed,
                                  boolean alive, long expectedGeneration, long generation,
                                  String expectedNetwork, String network) {
        return requested && networkAllowed && foregroundAllowed && alive
            && expectedGeneration == generation && expectedNetwork.equals(network);
    }

    static String localized(Context context, String report) {
        StringBuilder result = new StringBuilder();
        for (String line : report.split("\\n", -1)) {
            if (result.length() > 0) result.append('\n');
            result.append(LocalizedText.get(context, line));
        }
        return result.toString();
    }
}
