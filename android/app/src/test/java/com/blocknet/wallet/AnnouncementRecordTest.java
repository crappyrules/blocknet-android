package com.blocknet.wallet;

import org.junit.Test;
import java.io.IOException;
import java.util.Collections;
import static org.junit.Assert.*;

public class AnnouncementRecordTest {
    private static byte[] hex(String s){byte[] out=new byte[s.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return out;}
    // Independently signed with Python cryptography using RFC 8032's published
    // test seed. Only this public key and signed bytes are retained in the test.
    private static final byte[] GOLDEN_PUB=hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    private static final byte[] GOLDEN_RECORD=hex(
        "424e41550101202602150102010100000000000000070000000272fe0d961c89cdc8cf6a0c8663484fa8d2259521a0ecbdf00ad49be1a3a07b788"
        +"b72d28464b4cd6e4318ecdc833179b8f694eb1c14ca28e8ad93652943e4ce52ba288deed64ff1cd07571995137b708cb54e728042917bb5f6a34acde6cce801");

    @Test public void fullSignedRecordGoldenVectorAndScopeMutations() throws Exception {
        assertEquals(122,GOLDEN_RECORD.length);
        AnnouncementRecord parsed=AnnouncementRecord.parse(GOLDEN_RECORD,0x20260215,2,
            Collections.singletonMap(1,GOLDEN_PUB));
        assertEquals(7,parsed.sequence);
        assertEquals(2,parsed.versionCode);
        assertEquals(0x20260215,parsed.epoch);
        for(int offset:new int[]{0,4,5,6,10,11,12,13,21,25,26,57,58,121}){
            byte[] changed=GOLDEN_RECORD.clone();changed[offset]^=1;
            try {AnnouncementRecord.parse(changed,0x20260215,2,Collections.singletonMap(1,GOLDEN_PUB));
                fail("Accepted modified signed record byte "+offset);}
            catch(IOException expected) { }
        }
        try {AnnouncementRecord.parse(GOLDEN_RECORD,0x20260216,2,Collections.singletonMap(1,GOLDEN_PUB));fail();}
        catch(IOException expected) { }
        try {AnnouncementRecord.parse(GOLDEN_RECORD,0x20260215,1,Collections.singletonMap(1,GOLDEN_PUB));fail();}
        catch(IOException expected) { }
        try {AnnouncementRecord.parse(java.util.Arrays.copyOf(GOLDEN_RECORD,121),0x20260215,2,
            Collections.singletonMap(1,GOLDEN_PUB));fail();}
        catch(IOException expected) { }
    }

    @Test public void ed25519MatchesPublishedRfc8032Vector() {
        byte[] publicKey=hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        byte[] signature=hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
            +"5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
        assertTrue(AnnouncementRecord.verify(publicKey,new byte[0],signature));
        signature[0]^=1;
        assertFalse(AnnouncementRecord.verify(publicKey,new byte[0],signature));
    }

    @Test public void malformedAndUnpinnedRecordsNeverOpen() throws Exception {
        byte[] record=new byte[AnnouncementRecord.LENGTH];
        try { AnnouncementRecord.parse(record,0x20260215,1,Collections.emptyMap());fail(); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("Unsupported")); }
        System.arraycopy(new byte[]{'B','N','A','U',1,1},0,record,0,6);
        record[6]=0x20;record[7]=0x26;record[8]=0x02;record[9]=0x15;
        record[10]=1;record[11]=1;record[12]=1;record[13]=1;
        record[21]=1;record[25]=2;
        try { AnnouncementRecord.parse(record,0x20260215,1,Collections.emptyMap());fail(); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("pinned")); }
        try { AnnouncementRecord.parse(record,0x20260216,1,Collections.emptyMap());fail(); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("scope")); }
        try { AnnouncementRecord.parse(record,0x20260215,2,Collections.emptyMap());fail(); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("Unsupported")); }
    }
}
