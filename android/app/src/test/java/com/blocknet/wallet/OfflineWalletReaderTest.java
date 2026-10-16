package com.blocknet.wallet;

import org.junit.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class OfflineWalletReaderTest {
    @Test public void cleanupWaitsForLongRunningHelperToExit() throws Exception {
        Process helper=new ProcessBuilder("sleep","30").start();
        try {
            OfflineWalletReader.terminateAndWait(helper);
            assertFalse(helper.isAlive());
            assertTrue(helper.waitFor(1,TimeUnit.SECONDS));
        } finally {
            if(helper.isAlive())helper.destroyForcibly();
        }
    }

    @Test public void cleanupAcceptsAlreadyExitedHelper() throws Exception {
        Process helper=new ProcessBuilder("true").start();
        assertTrue(helper.waitFor(1,TimeUnit.SECONDS));
        OfflineWalletReader.terminateAndWait(helper);
        assertFalse(helper.isAlive());
    }
}
