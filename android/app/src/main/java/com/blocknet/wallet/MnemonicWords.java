package com.blocknet.wallet;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Offline immutable core mnemonic vocabulary. Typed prefixes are never stored or logged. */
public final class MnemonicWords {
    public static final String ASSET="mnemonic-english.txt";
    private static MnemonicWords instance;
    private final List<String> words;
    private MnemonicWords(List<String> words){this.words=Collections.unmodifiableList(new ArrayList<>(words));}
    public static synchronized MnemonicWords get(Context context)throws IOException{
        if(instance==null)try(Reader reader=new InputStreamReader(context.getAssets().open(ASSET),StandardCharsets.UTF_8)){instance=read(reader);}
        return instance;
    }
    /** Reads the exact dictionary shape; does not retain or close the caller's reader. */
    public static MnemonicWords read(Reader source)throws IOException{
        BufferedReader reader=source instanceof BufferedReader?(BufferedReader)source:new BufferedReader(source);
        List<String> words=new ArrayList<>(2048);String line,previous=null;
        while((line=reader.readLine())!=null){
            if(words.size()>=2048||!line.matches("[a-z]+")||(previous!=null&&previous.compareTo(line)>=0))throw new IOException("Mnemonic word list is invalid");
            words.add(line);previous=line;
        }
        if(words.size()!=2048)throw new IOException("Mnemonic word list must contain exactly 2048 words");
        return new MnemonicWords(words);
    }
    public int size(){return words.size();}
    public boolean contains(String value){return value!=null&&Collections.binarySearch(words,value)>=0;}
    /** Returns dictionary-order matches for one ASCII word fragment, including the first letter. */
    public List<String> prefix(String typed,int limit){
        if(typed==null||limit<=0||!typed.matches("[A-Za-z]+"))return Collections.emptyList();
        String prefix=typed.toLowerCase(Locale.ROOT);int index=Collections.binarySearch(words,prefix);
        if(index<0)index=-index-1;
        List<String> matches=new ArrayList<>();
        for(int i=index;i<words.size()&&matches.size()<limit;i++){String word=words.get(i);if(!word.startsWith(prefix))break;matches.add(word);}
        return Collections.unmodifiableList(matches);
    }
}
