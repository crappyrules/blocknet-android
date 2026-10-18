package com.blocknet.wallet;

import org.junit.Test;
import java.util.regex.Matcher;
import static org.junit.Assert.*;

public class GeneratedTranslationsTest {
    @Test public void criticalCopyAndFallbackIdentifiersAreDistinct(){
        assertNotNull(GeneratedTranslations.EXACT.get("Review every detail. Broadcast transactions cannot be reversed."));
        assertNotNull(GeneratedTranslations.EXACT.get("Cancel"));
        assertNull(GeneratedTranslations.EXACT.get("KNNpZhPcJX92oAyvcVWJhQXJjsWRYsEiPHej6ZTM2LuSJBDS3VQebjUjUCzYqBnidt1JXYUiUgA69HeBoTuVuQYWwnhQN"));
    }
    @Test public void dynamicWarningKeepsCanonicalAddressAndShortname(){
        Matcher found=null;
        for(GeneratedTranslations.Template template:GeneratedTranslations.TEMPLATES){
            Matcher candidate=template.pattern.matcher("$alice now resolves to NEW. The address book still holds SAVED.");
            if(candidate.matches()){found=candidate;break;}
        }
        assertNotNull(found);
        assertEquals("alice",found.group(1));
        assertEquals("NEW",found.group(2));
        assertEquals("SAVED",found.group(3));
    }
    @Test public void bundledCoreAboutVersionHasTranslatedTemplate(){
        boolean found=false;
        for(GeneratedTranslations.Template template:GeneratedTranslations.TEMPLATES){
            Matcher match=template.pattern.matcher("blocknet / native Android\nBundled Core 0.22.0-mobile1\nZero-knowledge money.");
            if(match.matches()&&match.groupCount()==1&&"0.22.0-mobile1".equals(match.group(1)))found=true;
        }
        assertTrue(found);
    }
    @Test public void coreDiagnosticLinesHaveBundledTranslations(){
        String[] reports={
            CoreDiagnostics.stopped("0.22.0-mobile1","mainnet"),
            CoreDiagnostics.unavailable("0.22.0-mobile1","mainnet"),
            CoreDiagnostics.paused("0.22.0-mobile1","mainnet"),
            CoreDiagnostics.running("0.22.0-mobile1","mainnet",72,3,true,80),
            CoreDiagnostics.running("0.22.0-mobile1","mainnet",72,3,false,0)
        };
        for(String report:reports)for(String line:report.split("\\n")){
            boolean translated=GeneratedTranslations.EXACT.containsKey(line);
            for(GeneratedTranslations.Template template:GeneratedTranslations.TEMPLATES)
                if(template.pattern.matcher(line).matches())translated=true;
            assertTrue("Missing diagnostic translation: "+line,translated);
        }
    }
}
