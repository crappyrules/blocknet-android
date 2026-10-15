package com.blocknet.wallet;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class MnemonicWordsTest {
    private String asset()throws Exception{
        for(String path:new String[]{"src/main/assets/mnemonic-english.txt","app/src/main/assets/mnemonic-english.txt","android/app/src/main/assets/mnemonic-english.txt"}){
            File file=new File(path);if(file.isFile())return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
        }
        throw new FileNotFoundException("Bundled mnemonic dictionary");
    }
    private MnemonicWords words()throws Exception{return MnemonicWords.read(new StringReader(asset()));}
    @Test public void exactDictionaryHas2048UniqueSortedWordsAndKnownEndpoints()throws Exception{
        MnemonicWords dictionary=words();assertEquals(2048,dictionary.size());assertTrue(dictionary.contains("abandon"));assertTrue(dictionary.contains("zoo"));
        List<String> all=new ArrayList<>();for(char letter='a';letter<='z';letter++)all.addAll(dictionary.prefix(String.valueOf(letter),dictionary.size()));
        assertEquals(2048,all.size());assertEquals(2048,new HashSet<>(all).size());
        List<String> sorted=new ArrayList<>(all);Collections.sort(sorted);assertEquals(sorted,all);assertEquals("abandon",all.get(0));assertEquals("zoo",all.get(all.size()-1));
    }
    @Test public void firstLetterSuggestionsIncludePondAndNarrowByTypedPrefix()throws Exception{
        MnemonicWords dictionary=words();List<String> first=dictionary.prefix("p",dictionary.size());
        assertTrue(first.size()>20);assertTrue(first.contains("pond"));for(String word:first)assertTrue(word.startsWith("p"));
        assertEquals(Arrays.asList("poem","poet","point","polar","pole","police","pond","pony","pool","popular","portion","position","possible","post","potato","pottery","poverty","powder","power"),dictionary.prefix("po",2048));
        assertEquals(Collections.singletonList("pond"),dictionary.prefix("pond",2048));assertEquals(dictionary.prefix("po",2048),dictionary.prefix("PO",2048));
        assertTrue(dictionary.prefix("ponds",2048).isEmpty());
    }
    @Test public void limitsMatchesAndNeverSuggestsForBlankOrMultipleWords()throws Exception{
        MnemonicWords dictionary=words();assertEquals(Arrays.asList("poem","poet","point"),dictionary.prefix("po",3));
        for(String prefix:new String[]{null,""," ","p ","po nd","$p","é","p1","\n","pond\n"})assertTrue(dictionary.prefix(prefix,2048).isEmpty());
        assertTrue(dictionary.prefix("p",0).isEmpty());assertTrue(dictionary.prefix("p",-1).isEmpty());
        try{dictionary.prefix("pond",2048).add("invented");fail("Suggestions must be immutable");}catch(UnsupportedOperationException expected){}
    }
    @Test public void rejectsTruncatedUnsortedDuplicateOrMalformedDictionary()throws Exception{
        String source=asset();List<String> lines=new ArrayList<>(Arrays.asList(source.trim().split("\n")));
        List<String> swapped=new ArrayList<>(lines);Collections.swap(swapped,0,1);
        List<String> duplicate=new ArrayList<>(lines);duplicate.set(1,duplicate.get(0));
        for(String malformed:new String[]{source.substring(source.indexOf('\n')+1),String.join("\n",swapped)+"\n",String.join("\n",duplicate)+"\n",source.replaceFirst("abandon","Abandon"),source+"invented\n",source.replaceFirst("abandon","ab andon")}){
            try{MnemonicWords.read(new StringReader(malformed));fail("Invalid dictionary accepted");}catch(IOException expected){}
        }
    }
}
