package com.blocknet.wallet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/** Versioned, authenticated address-book envelope; key storage is supplied separately. */
final class AddressBookCrypto {
    interface KeySource { Key get(boolean create) throws Exception; }
    private static final byte[] HEADER={'B','N','A','B',1};
    private static final byte[] CONTEXT="com.blocknet.wallet/address-book/v1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES=12,TAG_BYTES=16;
    static final int OVERHEAD=HEADER.length+IV_BYTES+TAG_BYTES;
    private final KeySource keys;
    AddressBookCrypto(KeySource keys){this.keys=keys;}
    static boolean hasMagic(byte[] raw){
        if(raw.length<4)return false;
        for(int i=0;i<4;i++)if(raw[i]!=HEADER[i])return false;
        return true;
    }
    byte[] seal(byte[] plain)throws IOException {
        try{
            Key key=keys.get(true);
            if(key==null)throw new IOException("Address-book encryption key is unavailable");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key);
            byte[] iv=cipher.getIV();
            if(iv==null||iv.length!=IV_BYTES)throw new IOException("Address-book encryption nonce is unavailable");
            cipher.updateAAD(HEADER);cipher.updateAAD(CONTEXT);
            byte[] ciphertext=cipher.doFinal(plain);
            byte[] raw=new byte[HEADER.length+iv.length+ciphertext.length];
            System.arraycopy(HEADER,0,raw,0,HEADER.length);
            System.arraycopy(iv,0,raw,HEADER.length,iv.length);
            System.arraycopy(ciphertext,0,raw,HEADER.length+iv.length,ciphertext.length);
            return raw;
        }catch(IOException failure){throw failure;}
        catch(Exception failure){throw new IOException("Address book could not be encrypted",failure);}
    }
    byte[] open(byte[] raw)throws IOException {
        if(raw.length<OVERHEAD||!hasMagic(raw)||raw[4]!=HEADER[4])
            throw new IOException("Unsupported or damaged encrypted address book");
        try{
            Key key=keys.get(false);
            if(key==null)throw new IOException("Address-book encryption key is missing; the saved file was kept");
            byte[] iv=Arrays.copyOfRange(raw,HEADER.length,HEADER.length+IV_BYTES);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));
            cipher.updateAAD(HEADER);cipher.updateAAD(CONTEXT);
            return cipher.doFinal(raw,HEADER.length+IV_BYTES,raw.length-HEADER.length-IV_BYTES);
        }catch(AEADBadTagException damaged){throw new IOException("Encrypted address book could not be authenticated; the saved file was kept",damaged);}
        catch(IOException failure){throw failure;}
        catch(Exception failure){throw new IOException("Encrypted address book could not be opened; the saved file was kept",failure);}
    }
}
