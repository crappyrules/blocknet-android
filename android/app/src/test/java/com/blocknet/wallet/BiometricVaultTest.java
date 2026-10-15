package com.blocknet.wallet;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class BiometricVaultTest {
    @Test public void bindsScopeToExactKnownNetworkAndWalletWithoutExposingItsName()throws Exception {
        String one=BiometricVault.scopeFor("mainnet","main.wallet.dat"),two=BiometricVault.scopeFor("mainnet","other.wallet.dat");
        assertTrue(one.matches("[a-f0-9]{64}"));assertNotEquals(one,two);assertNotEquals(one,BiometricVault.scopeFor("testnet","main.wallet.dat"));assertFalse(one.contains("wallet"));
        for(String wallet:new String[]{"../main.wallet.dat","main","","a".repeat(49)+".wallet.dat"})try{BiometricVault.scopeFor("mainnet",wallet);fail("Unsafe wallet scope accepted");}catch(IllegalArgumentException expected){}
        try{BiometricVault.scopeFor("other","main.wallet.dat");fail("Unrequested network scope accepted");}catch(IllegalArgumentException expected){}
    }
    @Test public void preservesPasswordUnicodeExactlyWithoutTrimming()throws Exception {
        char[] chars={' ','A','\u03bb','\ud83d','\ude03','\u0000',' '};byte[] bytes=BiometricVault.encodePassword(chars);
        try{assertArrayEquals(chars,BiometricVault.decodePassword(bytes));}finally{Arrays.fill(bytes,(byte)0);Arrays.fill(chars,'\0');}
    }
    @Test public void rejectsMalformedOrUnboundedPasswordBytes()throws Exception {
        for(char[] chars:new char[][]{new char[0],new char[]{'\ud800'},new char[4097]})try{BiometricVault.encodePassword(chars);fail("Invalid password accepted");}catch(IOException expected){}
        for(byte[] bytes:new byte[][]{new byte[0],new byte[]{(byte)0xff},new byte[4097]})try{BiometricVault.decodePassword(bytes);fail("Invalid password bytes accepted");}catch(IOException expected){}
    }
    @Test public void validatesEnvelopeVersionScopeAndExactCiphertextLengths()throws Exception {
        String scope=BiometricVault.scopeFor("mainnet","main.wallet.dat");String alias="blocknet.biometric.v1."+scope+".12345678-1234-1234-1234-123456789abc";
        BiometricVault.Envelope e=new BiometricVault.Envelope(scope,alias,new byte[12],new byte[32]);byte[] encoded=BiometricVault.encodeEnvelope(e);
        BiometricVault.Envelope decoded=BiometricVault.decodeEnvelope(scope,encoded);assertEquals(alias,decoded.alias);assertArrayEquals(e.iv,decoded.iv);assertArrayEquals(e.ciphertext,decoded.ciphertext);
        try{BiometricVault.decodeEnvelope(BiometricVault.scopeFor("mainnet","other.wallet.dat"),encoded);fail("Cross-wallet envelope accepted");}catch(IOException expected){}
        byte[] badVersion=encoded.clone();badVersion[0]='x';
        for(byte[] invalid:new byte[][]{badVersion,Arrays.copyOf(encoded,encoded.length-1),Arrays.copyOf(encoded,encoded.length+1),new byte[8193]})try{BiometricVault.decodeEnvelope(scope,invalid);fail("Malformed envelope accepted");}catch(IOException expected){}
        for(BiometricVault.Envelope invalid:new BiometricVault.Envelope[]{new BiometricVault.Envelope(scope,alias,new byte[11],new byte[32]),new BiometricVault.Envelope(scope,"wrong-alias",new byte[12],new byte[32]),new BiometricVault.Envelope(scope,alias,new byte[12],new byte[16])})try{BiometricVault.encodeEnvelope(invalid);fail("Invalid envelope fields accepted");}catch(IOException expected){}
    }
}
