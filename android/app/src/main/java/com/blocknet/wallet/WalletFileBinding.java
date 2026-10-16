package com.blocknet.wallet;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;

/** Binds an encrypted display cache to the exact wallet file Core saved. */
final class WalletFileBinding {
    private static final long MAX_WALLET_BYTES = 64L * 1024 * 1024;

    private WalletFileBinding() {}

    static String digest(File file) throws Exception {
        if (!file.isFile() || file.length() < 1 || file.length() > MAX_WALLET_BYTES)
            throw new IOException("Wallet file is unavailable or too large.");
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_WALLET_BYTES) throw new IOException("Wallet file is too large.");
                sha256.update(buffer, 0, count);
            }
        }
        if (total < 1) throw new IOException("Wallet file is empty.");
        byte[] hash = sha256.digest();
        char[] hex = new char[hash.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < hash.length; i++) {
            hex[2 * i] = digits[(hash[i] >>> 4) & 15];
            hex[2 * i + 1] = digits[hash[i] & 15];
        }
        return new String(hex);
    }

    static boolean matches(String savedDigest, String currentDigest) {
        return savedDigest != null && savedDigest.length() == 64 &&
            savedDigest.equals(currentDigest);
    }
}
