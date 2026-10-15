package com.blocknet.wallet;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ShortnameResolverTest {
    private static byte[] hex(String value){byte[] bytes=new byte[value.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return bytes;}
    @Test public void normalizesOnlyOneOptionalPrefixAndAsciiHandle(){
        assertEquals("alice_01.-",ShortnameResolver.normalize(" $Alice_01.- "));
        assertEquals("bob",ShortnameResolver.normalize("@BOB"));
        assertEquals("carol",ShortnameResolver.normalize("Carol"));
        assertEquals("a".repeat(64),ShortnameResolver.normalize("a".repeat(64)));
        for(String invalid:new String[]{null,"","$","@","$$alice","@@alice","@$alice","$@alice","a b","a/b","a?b","a#b","a%20b","é","K","a".repeat(65)}){
            try{ShortnameResolver.normalize(invalid);fail("Invalid shortname accepted");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void verifiesRfc8032RawKeyAndEmptyMessage(){
        byte[] key=hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        byte[] signature=hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
        assertTrue(ShortnameResolver.verifySignature(key,"",signature));
        assertFalse(ShortnameResolver.verifySignature(key," ",signature));signature[0]^=1;
        assertFalse(ShortnameResolver.verifySignature(key,"",signature));
    }
    @Test public void signsExactUtf8PayloadAndRejectsAlteredPayloadOrKey(){
        Ed25519PrivateKeyParameters privateKey=new Ed25519PrivateKeyParameters(hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"),0);
        String payload="{\"handle\":\"alice\",\"address\":\"synthetic\",\"updated_at_unix\":42,\"note\":\"café\"}";
        Ed25519Signer signer=new Ed25519Signer();signer.init(true,privateKey);byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);signer.update(bytes,0,bytes.length);byte[] signature=signer.generateSignature(),key=privateKey.generatePublicKey().getEncoded();
        assertTrue(ShortnameResolver.verifySignature(key,payload,signature));
        assertFalse(ShortnameResolver.verifySignature(key,payload.replace("alice","bob"),signature));
        assertFalse(ShortnameResolver.verifySignature(key,payload+"\n",signature));
        key[0]^=1;assertFalse(ShortnameResolver.verifySignature(key,payload,signature));
    }
    @Test public void rejectsMalformedCryptoInputs(){
        assertFalse(ShortnameResolver.verifySignature(null,"x",new byte[64]));
        assertFalse(ShortnameResolver.verifySignature(new byte[31],"x",new byte[64]));
        assertFalse(ShortnameResolver.verifySignature(new byte[32],null,new byte[64]));
        assertFalse(ShortnameResolver.verifySignature(new byte[32],"x",null));
        assertFalse(ShortnameResolver.verifySignature(new byte[32],"x",new byte[63]));
    }
}
