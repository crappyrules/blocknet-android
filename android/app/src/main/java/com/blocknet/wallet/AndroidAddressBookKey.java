package com.blocknet.wallet;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.Key;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** A separate, device-bound app key. Wallet and biometric keys are never reused. */
final class AndroidAddressBookKey implements AddressBookCrypto.KeySource {
    private static final String ALIAS="blocknet.address-book.v1";
    private static final Object LOCK=new Object();
    private final String alias;
    AndroidAddressBookKey(){alias=ALIAS;}
    /** Restricted to the isolated instrumentation fixture; never points at the production alias. */
    AndroidAddressBookKey(String testAlias){
        if(testAlias==null||!testAlias.matches("blocknet\\.address-book\\.test\\.[0-9a-f-]{36}"))
            throw new IllegalArgumentException("Invalid address-book test key alias");
        alias=testAlias;
    }
    public Key get(boolean create)throws Exception {
        synchronized(LOCK){
            KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
            Key existing=store.getKey(alias,null);
            if(existing!=null){
                if(!(existing instanceof SecretKey))throw new IOException("Address-book key has an unexpected type");
                return existing;
            }
            if(!create)throw new IOException("Address-book encryption key is missing; the saved file was kept");
            KeyGenParameterSpec spec=new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build();
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(spec);return generator.generateKey();
        }
    }
}
