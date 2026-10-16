package com.blocknet.wallet;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class WalletFileBindingTest {
    @Test public void sameLengthWalletReplacementInvalidatesSavedView() throws Exception {
        File wallet=File.createTempFile("wallet-binding", ".wallet.dat");
        try {
            Files.write(wallet.toPath(),"encrypted-wallet-a".getBytes(StandardCharsets.UTF_8));
            String saved=WalletFileBinding.digest(wallet);
            assertTrue(WalletFileBinding.matches(saved,WalletFileBinding.digest(wallet)));
            Files.write(wallet.toPath(),"encrypted-wallet-b".getBytes(StandardCharsets.UTF_8));
            assertFalse(WalletFileBinding.matches(saved,WalletFileBinding.digest(wallet)));
            assertFalse(WalletFileBinding.matches("",WalletFileBinding.digest(wallet)));
        } finally { assertTrue(wallet.delete()); }
    }

    @Test public void missingWalletCannotAuthorizeSavedView() throws Exception {
        File absent=new File(System.getProperty("java.io.tmpdir"),"absent-wallet-"+System.nanoTime());
        try { WalletFileBinding.digest(absent); fail("Expected missing wallet rejection"); }
        catch(java.io.IOException expected) { assertTrue(expected.getMessage().contains("unavailable")); }
    }
}
