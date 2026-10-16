package com.blocknet.wallet;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class OfflineSnapshotStoreTest {
    @Test public void savedViewRequiresPasswordAndWalletScope() throws Exception {
        byte[] salt=new byte[16]; Arrays.fill(salt,(byte)7);
        byte[] key=OfflineSnapshotStore.derive("correct wallet password",salt);
        byte[] plain="last saved balance and activity".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted=OfflineSnapshotStore.encrypt(plain,"mainnet/alice.wallet.dat",salt,key);
        assertArrayEquals(plain,OfflineSnapshotStore.decrypt(encrypted,"mainnet/alice.wallet.dat",key));
        assertFalse(new String(encrypted,StandardCharsets.ISO_8859_1).contains("last saved balance"));
        byte[] wrong=OfflineSnapshotStore.derive("incorrect password",salt);
        try { OfflineSnapshotStore.decrypt(encrypted,"mainnet/alice.wallet.dat",wrong); fail("wrong password opened cache"); }
        catch (javax.crypto.AEADBadTagException expected) { }
        try { OfflineSnapshotStore.decrypt(encrypted,"testnet/alice.wallet.dat",key); fail("other network opened cache"); }
        catch (javax.crypto.AEADBadTagException expected) { }
        encrypted[encrypted.length-1]^=1;
        try { OfflineSnapshotStore.decrypt(encrypted,"mainnet/alice.wallet.dat",key); fail("tampering opened cache"); }
        catch (javax.crypto.AEADBadTagException expected) { }
    }

    @Test public void eachSaveUsesAUniqueNonce() throws Exception {
        byte[] salt=new byte[16],key=OfflineSnapshotStore.derive("password",salt);
        byte[] plain="same snapshot".getBytes(StandardCharsets.UTF_8);
        byte[] first=OfflineSnapshotStore.encrypt(plain,"mainnet/alice.wallet.dat",salt,key);
        byte[] second=OfflineSnapshotStore.encrypt(plain,"mainnet/alice.wallet.dat",salt,key);
        assertFalse(Arrays.equals(first,second));
        assertArrayEquals(plain,OfflineSnapshotStore.decrypt(second,"mainnet/alice.wallet.dat",key));
    }
}
