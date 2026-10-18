package com.blocknet.wallet;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class WalletRepositoryCacheWriteGateTest {
    private static String view(long balance) { return "mainnet/main.wallet.dat|"+balance+"|height=10|activity=[]"; }

    @Test public void unchangedPollsAvoidDigestAndWriteButChangesPersist() throws Exception {
        File wallet=File.createTempFile("cache-gate", ".wallet.dat");
        try {
            Files.write(wallet.toPath(),"encrypted-wallet-a".getBytes(StandardCharsets.UTF_8));
            WalletRepository.CacheWriteGate gate=new WalletRepository.CacheWriteGate();
            AtomicInteger hashes=new AtomicInteger(),writes=new AtomicInteger();
            WalletRepository.CacheWriteGate.Digest digest=file->{hashes.incrementAndGet();return WalletFileBinding.digest(file);};
            WalletRepository.CacheWriteGate.Writer writer=(binding,savedAt)->{
                assertEquals(64,binding.length());assertTrue(savedAt>0);
                writes.incrementAndGet();
            };
            String scope="mainnet/main.wallet.dat";
            assertTrue(gate.saveIfNeeded(scope,1,wallet,view(20),false,1_000,digest,writer));
            for(int n=1;n<=40;n++)
                assertFalse(gate.saveIfNeeded(scope,1,wallet,view(20),true,1_000+n*5_000,digest,writer));
            assertEquals(1,hashes.get());assertEquals(1,writes.get());
            assertTrue(gate.saveIfNeeded(scope,1,wallet,view(21),true,202_000,digest,writer));
            assertEquals(2,hashes.get());assertEquals(2,writes.get());
            Files.write(wallet.toPath(),"encrypted-wallet-new-and-longer".getBytes(StandardCharsets.UTF_8));
            assertTrue(gate.saveIfNeeded(scope,1,wallet,view(21),true,207_000,digest,writer));
            assertEquals(3,hashes.get());assertEquals(3,writes.get());
            assertTrue(gate.saveIfNeeded(scope,1,wallet,view(21),true,207_001+WalletRepository.CacheWriteGate.RECHECK_MS,digest,writer));
            assertEquals(4,hashes.get());assertEquals(4,writes.get());
            assertTrue(gate.saveIfNeeded(scope,2,wallet,view(21),true,900_000,digest,writer));
            assertEquals(5,hashes.get());assertEquals(5,writes.get());
        } finally { assertTrue(wallet.delete()); }
    }

    @Test public void failedWriteRetriesAndMissingCacheForcesNewBinding() throws Exception {
        File wallet=File.createTempFile("cache-gate", ".wallet.dat");
        try {
            Files.write(wallet.toPath(),"encrypted-wallet".getBytes(StandardCharsets.UTF_8));
            WalletRepository.CacheWriteGate gate=new WalletRepository.CacheWriteGate();
            AtomicInteger hashes=new AtomicInteger(),writes=new AtomicInteger();
            WalletRepository.CacheWriteGate.Digest digest=file->{hashes.incrementAndGet();return WalletFileBinding.digest(file);};
            WalletRepository.CacheWriteGate.Writer writer=(binding,savedAt)->{
                if(writes.incrementAndGet()==1)throw new java.io.IOException("synthetic storage failure");
            };
            String scope="mainnet/main.wallet.dat";
            try {gate.saveIfNeeded(scope,1,wallet,view(1),false,1_000,digest,writer);fail("write must fail");}
            catch(java.io.IOException expected){assertEquals("synthetic storage failure",expected.getMessage());}
            assertTrue(gate.saveIfNeeded(scope,1,wallet,view(1),false,1_001,digest,writer));
            assertFalse(gate.saveIfNeeded(scope,1,wallet,view(1),true,1_002,digest,writer));
            assertTrue(gate.saveIfNeeded(scope,1,wallet,view(1),false,1_003,digest,writer));
            assertEquals(3,hashes.get());assertEquals(3,writes.get());
        } finally { assertTrue(wallet.delete()); }
    }

    @Test public void retainedStateIsOnlyAFixedDigestAndLockWipesIt() throws Exception {
        File wallet=File.createTempFile("cache-gate", ".wallet.dat");
        try {
            Files.write(wallet.toPath(),"encrypted-wallet".getBytes(StandardCharsets.UTF_8));
            WalletRepository.CacheWriteGate gate=new WalletRepository.CacheWriteGate();
            String privateView="address-and-private-activity-marker-123";
            gate.saveIfNeeded("mainnet/main.wallet.dat",1,wallet,privateView,false,1_000,
                WalletFileBinding::digest,(binding,savedAt)->{});
            Field field=WalletRepository.CacheWriteGate.class.getDeclaredField("contentFingerprint");
            field.setAccessible(true);
            byte[] retained=(byte[])field.get(gate);
            assertEquals(32,retained.length);
            for(Field candidate:WalletRepository.CacheWriteGate.class.getDeclaredFields())
                if(candidate.getType()==String.class){
                    candidate.setAccessible(true);
                    assertNotEquals(privateView,candidate.get(gate));
                }
            gate.reset();
            assertNull(field.get(gate));
            assertArrayEquals(new byte[32],retained);
        } finally { assertTrue(wallet.delete()); }
    }

    @Test public void sameSizeAndMtimeReplacementCannotPassOfflineBinding() throws Exception {
        File wallet=File.createTempFile("cache-gate", ".wallet.dat");
        try {
            Files.write(wallet.toPath(),"encrypted-wallet-a".getBytes(StandardCharsets.UTF_8));
            WalletRepository.CacheWriteGate gate=new WalletRepository.CacheWriteGate();
            String[] savedBinding={null};
            gate.saveIfNeeded("mainnet/main.wallet.dat",1,wallet,view(1),false,1_000,
                WalletFileBinding::digest,(binding,savedAt)->savedBinding[0]=binding);
            long modified=wallet.lastModified();
            Files.write(wallet.toPath(),"encrypted-wallet-b".getBytes(StandardCharsets.UTF_8));
            assertTrue(wallet.setLastModified(modified));
            assertEquals(modified,wallet.lastModified());
            assertFalse(gate.saveIfNeeded("mainnet/main.wallet.dat",1,wallet,view(1),true,2_000,
                file->{fail("metadata-only shortcut should skip a live rehash");return "";},
                (binding,savedAt)->fail("unchanged metadata skips the live write")));
            // Offline reading always hashes the current file, independent of the live shortcut.
            assertFalse(WalletFileBinding.matches(savedBinding[0],WalletFileBinding.digest(wallet)));
        } finally { assertTrue(wallet.delete()); }
    }

    @Test public void lockDuringWriteCannotRecordSuccessAndNextUnlockRetries() throws Exception {
        File wallet=File.createTempFile("cache-gate", ".wallet.dat");
        try {
            Files.write(wallet.toPath(),"encrypted-wallet".getBytes(StandardCharsets.UTF_8));
            WalletRepository.CacheWriteGate gate=new WalletRepository.CacheWriteGate();
            try {
                gate.saveIfNeeded("mainnet/main.wallet.dat",1,wallet,view(1),false,1_000,
                    WalletFileBinding::digest,(binding,savedAt)->{throw new java.io.IOException("session locked");});
                fail("a locked session cannot save");
            } catch(java.io.IOException expected){assertEquals("session locked",expected.getMessage());}
            Field field=WalletRepository.CacheWriteGate.class.getDeclaredField("contentFingerprint");
            field.setAccessible(true);assertNull(field.get(gate));
            assertTrue(gate.saveIfNeeded("mainnet/main.wallet.dat",1,wallet,view(1),false,1_001,
                WalletFileBinding::digest,(binding,savedAt)->{}));
        } finally { assertTrue(wallet.delete()); }
    }
}
