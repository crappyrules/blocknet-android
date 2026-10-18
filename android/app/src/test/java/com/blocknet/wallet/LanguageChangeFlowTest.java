package com.blocknet.wallet;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class LanguageChangeFlowTest {
    @Test public void failedLocaleWriteLeavesWalletUiRedacted() {
        List<String> steps=new ArrayList<>();
        try {
            LanguageChangeFlow.complete(()->steps.add("redact"),()->{
                steps.add("save");throw new IOException("preference write failed");
            },()->steps.add("recreate"));
            fail("failed save reported success");
        } catch(IOException expected) {
            assertEquals(Arrays.asList("redact","save"),steps);
        } catch(Exception unexpected) { throw new AssertionError(unexpected); }
    }

    @Test public void successfulChangeLocksViewBeforeRecreation() throws Exception {
        List<String> steps=new ArrayList<>();
        LanguageChangeFlow.complete(()->steps.add("redact"),()->steps.add("save"),()->steps.add("recreate"));
        assertEquals(Arrays.asList("redact","save","recreate"),steps);
    }
}
