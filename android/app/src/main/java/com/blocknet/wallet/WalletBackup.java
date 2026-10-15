package com.blocknet.wallet;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Structural checks only; the core authenticates/decrypts the backup when opened. */
final class WalletBackup {
    private static final byte[] MAGIC="BLKNTWLT".getBytes(StandardCharsets.US_ASCII);
    private WalletBackup() { }
    static void validate(File file)throws IOException {
        long length=file.length();
        // Legacy: salt16 + AES-GCM nonce12 + authentication tag16.
        if(length<44)throw new IOException("Wallet backup is truncated or invalid");
        byte[] header=new byte[9];
        try(DataInputStream input=new DataInputStream(new FileInputStream(file))){input.readFully(header);}
        if(Arrays.equals(MAGIC,Arrays.copyOf(header,8))) {
            if(header[8]!=1)throw new IOException("Unsupported wallet backup format version");
            // Version1: header22 + salt16 + nonce12 + authentication tag16.
            if(length<66)throw new IOException("Wallet backup is truncated or invalid");
        }
    }
}
