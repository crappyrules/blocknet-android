package com.blocknet.wallet;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

/** Strict v1 on-chain release notice. Memo authenticity comes only from Ed25519. */
final class AnnouncementRecord {
    static final int LENGTH=122;
    static final byte KIND_RELEASE=1, APP_ANDROID=1, CHANNEL_PREVIEW=1;
    private static final byte[] MAGIC={'B','N','A','U'};
    private static final byte[] DOMAIN="Blocknet Android update/v1\0".getBytes(StandardCharsets.US_ASCII);
    final int epoch, variant, channel, signer, versionCode;
    final long sequence;
    final byte[] manifestSha256;

    private AnnouncementRecord(int epoch,int variant,int channel,int signer,long sequence,
                               int versionCode,byte[] digest) {
        this.epoch=epoch;this.variant=variant;this.channel=channel;this.signer=signer;
        this.sequence=sequence;this.versionCode=versionCode;
        this.manifestSha256=digest.clone();
    }

    static AnnouncementRecord parse(byte[] record,int expectedEpoch,int expectedVariant,
                                    Map<Integer,byte[]> pinnedSigners) throws IOException {
        if(record==null||record.length!=LENGTH||!Arrays.equals(Arrays.copyOf(record,4),MAGIC)
            ||record[4]!=1||record[5]!=KIND_RELEASE||record[10]!=APP_ANDROID
            ||(record[11]&255)!=expectedVariant||record[12]!=CHANNEL_PREVIEW)
            throw new IOException("Unsupported on-chain release record.");
        ByteBuffer header=ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN);
        int epoch=header.getInt(6),signer=record[13]&255;
        long sequence=header.getLong(14);
        int versionCode=header.getInt(22);
        if(epoch!=expectedEpoch||sequence<=0||versionCode<=0)
            throw new IOException("On-chain release scope or version is invalid.");
        byte[] publicKey=pinnedSigners.get(signer);
        if(publicKey==null||publicKey.length!=32)
            throw new IOException("On-chain release signer is not pinned.");
        byte[] signed=new byte[DOMAIN.length+58];
        System.arraycopy(DOMAIN,0,signed,0,DOMAIN.length);
        System.arraycopy(record,0,signed,DOMAIN.length,58);
        if(!verify(publicKey,signed,Arrays.copyOfRange(record,58,122)))
            throw new IOException("On-chain release signature is invalid.");
        byte[] digest=Arrays.copyOfRange(record,26,58);
        if(allZero(digest))throw new IOException("On-chain release manifest digest is invalid.");
        return new AnnouncementRecord(epoch,expectedVariant,CHANNEL_PREVIEW,signer,sequence,versionCode,digest);
    }

    static boolean verify(byte[] publicKey,byte[] message,byte[] signature) {
        if(publicKey==null||publicKey.length!=32||signature==null||signature.length!=64)return false;
        try {
            Ed25519Signer verifier=new Ed25519Signer();
            verifier.init(false,new Ed25519PublicKeyParameters(publicKey,0));
            verifier.update(message,0,message.length);
            return verifier.verifySignature(signature);
        } catch(RuntimeException invalid) { return false; }
    }

    boolean matchesManifest(byte[] raw) throws Exception {
        return raw!=null&&MessageDigest.isEqual(manifestSha256,MessageDigest.getInstance("SHA-256").digest(raw));
    }
    private static boolean allZero(byte[] data){for(byte value:data)if(value!=0)return false;return true;}
}
