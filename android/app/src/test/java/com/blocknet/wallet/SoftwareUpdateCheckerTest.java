package com.blocknet.wallet;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class SoftwareUpdateCheckerTest {
    private static final String CURRENT="v1.0.0-preview.1";
    private static String release(String tag,String asset,String page,String download){
        return "[{\"tag_name\":\""+tag+"\",\"draft\":false,\"prerelease\":true,\"html_url\":\""+page+
            "\",\"body\":\"Preview notes\",\"assets\":[{\"name\":\""+asset+"\",\"state\":\"uploaded\",\"size\":18000000,\"browser_download_url\":\""+download+"\"}]}]";
    }
    private static String release(String tag){
        String asset="blocknet-wallet-arm64-supplied-debug.apk";
        return release(tag,asset,SoftwareUpdateChecker.PAGE_PREFIX+tag,
            "https://github.com/crappyrules/blocknet-android/releases/download/"+tag+"/"+asset);
    }
    @Test public void configuredSignedChannelNeverShowsOrPollsUnsignedGitHubResult(){
        SoftwareUpdateChecker.Release legacy=new SoftwareUpdateChecker.Release(
            "v1.0.0-preview.2","GitHub notes",SoftwareUpdateChecker.PAGE_PREFIX+"v1.0.0-preview.2");
        SoftwareUpdateChecker.Release signed=new SoftwareUpdateChecker.Release(
            "v1.0.0-preview.3","Signed notes",SoftwareUpdateChecker.PAGE_PREFIX+"v1.0.0-preview.3",true);
        assertFalse(SoftwareUpdateChecker.githubPollingEnabled(true));
        assertNull(SoftwareUpdateChecker.visibleRelease(true,null,legacy));
        assertSame(signed,SoftwareUpdateChecker.visibleRelease(true,signed,legacy));
        assertTrue(SoftwareUpdateChecker.githubPollingEnabled(false));
        assertSame(legacy,SoftwareUpdateChecker.visibleRelease(false,signed,legacy));
        assertFalse(legacy.signedAnnouncement);
        assertTrue(signed.signedAnnouncement);
    }
    @Test public void dailyThrottleAndFailedRequestBackoff(){
        long now=1_000_000_000L;
        assertTrue(SoftwareUpdateChecker.due(now,0,0));
        assertFalse(SoftwareUpdateChecker.due(now,now-1000,now-1000));
        assertFalse(SoftwareUpdateChecker.due(now,0,now-1000));
        assertTrue(SoftwareUpdateChecker.due(now,0,now-6L*60*60*1000));
        assertTrue(SoftwareUpdateChecker.due(now,now-24L*60*60*1000,now-6L*60*60*1000));
        assertFalse(SoftwareUpdateChecker.due(now,now+1000,now+1000));
    }
    @Test public void noUpdateAndNewPreviewWithExpectedVariant() throws IOException {
        assertNull(SoftwareUpdateChecker.parseReleases("[]",CURRENT,false));
        assertNull(SoftwareUpdateChecker.parseReleases(release(CURRENT),CURRENT,false));
        SoftwareUpdateChecker.Release newer=SoftwareUpdateChecker.parseReleases(release("v1.0.0-preview.2"),CURRENT,false);
        assertEquals("v1.0.0-preview.2",newer.tag);
        assertEquals("Preview notes",newer.notes);
        assertEquals(SoftwareUpdateChecker.PAGE_PREFIX+newer.tag,newer.url);
        assertTrue(SoftwareUpdateChecker.compareTags("v1.0.0","v1.0.0-preview.2")>0);
    }
    @Test public void rejectsMalformedReleaseAndMissingOrForeignAssets(){
        for(String json:new String[]{"{}","[{]",release("v1.0.0-preview.2"),
            release("v1.0.0-preview.2","blocknet-wallet-arm64-supplied-debug.apk",
                "https://example.com/release","https://example.com/file.apk"),
            release("v1.0.0-preview.2","blocknet-wallet-arm64-activity-debug.apk",
                SoftwareUpdateChecker.PAGE_PREFIX+"v1.0.0-preview.2",
                "https://example.com/other.apk")}){
            try { SoftwareUpdateChecker.parseReleases(json,CURRENT,true); fail("Accepted malformed or incompatible release: "+json); }
            catch(IOException expected) { }
        }
    }
}
