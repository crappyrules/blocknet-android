package com.blocknet.wallet;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.Assert.*;

public class AnnouncementManifestTest {
    private static final String CERT="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static String json(String tag){return "{\"schema\":1,\"epoch\":539361813,\"app\":\"blocknet-android\",\"variant\":\"activity\",\"channel\":\"preview\",\"sequence\":7,\"version_code\":2,\"tag\":\""+tag+"\",\"release_page\":\"https://github.com/crappyrules/blocknet-android/releases/tag/"+tag+"\",\"apk_url\":\"https://github.com/crappyrules/blocknet-android/releases/download/"+tag+"/blocknet-wallet-arm64-activity-release.apk\",\"apk_sha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"signing_cert_sha256\":\""+CERT+"\",\"notes\":\"Back up first\"}";}
    @Test public void exactSignedScopeOpensManifest() throws Exception {
        byte[] raw=json("v1.0.0-preview.2").getBytes(StandardCharsets.UTF_8);
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(raw);
        assertEquals("https://raw.githubusercontent.com/crappyrules/blocknet-android/master/android/updates/"+
            hex(digest)+".json",AnnouncementManifest.urlForDigest(digest));
        AnnouncementManifest.Release result=AnnouncementManifest.parse(raw,digest,0x20260215,7,2,1,"activity",CERT);
        assertEquals("v1.0.0-preview.2",result.tag);
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}
    @Test public void mismatchOrExtraFieldIsRejected() throws Exception {
        byte[] raw=json("v1.0.0-preview.2").getBytes(StandardCharsets.UTF_8);
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(raw);
        raw[raw.length-2]^=1;
        try {AnnouncementManifest.parse(raw,digest,0x20260215,7,2,1,"activity",CERT);fail();}
        catch(IOException expected) {assertTrue(expected.getMessage().contains("hash"));}
        raw=json("v1.0.0-preview.2").replace("\"notes\"","\"unexpected\":0,\"notes\"").getBytes(StandardCharsets.UTF_8);
        digest=MessageDigest.getInstance("SHA-256").digest(raw);
        try {AnnouncementManifest.parse(raw,digest,0x20260215,7,2,1,"activity",CERT);fail();}
        catch(IOException expected) {assertTrue(expected.getMessage().contains("fields"));}
        raw=json("v1.0.0-preview.2").getBytes(StandardCharsets.UTF_8);
        digest=MessageDigest.getInstance("SHA-256").digest(raw);
        try {AnnouncementManifest.parse(raw,digest,0x20260215,7,2,1,"supplied",CERT);fail();}
        catch(IOException expected) {assertTrue(expected.getMessage().contains("scope"));}
        raw=json("v1.0.0-preview.2").replace("\"sequence\":7","\"sequence\":\"7\"").getBytes(StandardCharsets.UTF_8);
        digest=MessageDigest.getInstance("SHA-256").digest(raw);
        try {AnnouncementManifest.parse(raw,digest,0x20260215,7,2,1,"activity",CERT);fail();}
        catch(IOException expected) {assertTrue(expected.getMessage().contains("number"));}
    }
}
