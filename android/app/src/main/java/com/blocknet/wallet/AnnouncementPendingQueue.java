package com.blocknet.wallet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Small durable inbox for signed memos whose HTTPS manifests still need checking. */
final class AnnouncementPendingQueue {
    static final int LIMIT=64;
    static final class Entry {
        final long height;
        final String hash,memo;
        Entry(long height,String hash,String memo) throws IOException {
            if(height<0||hash==null||!hash.matches("[0-9a-f]{64}")||
                memo==null||!memo.matches("[0-9a-f]{244}"))
                throw new IOException("Invalid pending announcement.");
            this.height=height;this.hash=hash;this.memo=memo;
        }
    }
    private final List<Entry> entries=new ArrayList<>();
    static AnnouncementPendingQueue decode(String encoded) throws IOException {
        AnnouncementPendingQueue queue=new AnnouncementPendingQueue();
        if(encoded==null||encoded.isEmpty())return queue;
        String[] rows=encoded.split("\\n",-1);
        if(rows.length>LIMIT)throw new IOException("Pending announcement limit exceeded.");
        for(String row:rows){
            String[] fields=row.split(":",-1);
            if(fields.length!=3||!fields[0].matches("0|[1-9][0-9]{0,18}"))
                throw new IOException("Invalid pending announcement encoding.");
            try {queue.offer(new Entry(Long.parseLong(fields[0]),fields[1],fields[2]));}
            catch(NumberFormatException invalid){throw new IOException("Invalid pending height.",invalid);}
        }
        return queue;
    }
    String encode(){
        StringBuilder out=new StringBuilder();
        for(Entry entry:entries){
            if(out.length()>0)out.append('\n');
            out.append(entry.height).append(':').append(entry.hash).append(':').append(entry.memo);
        }
        return out.toString();
    }
    int size(){return entries.size();}
    Entry first(){return entries.isEmpty()?null:entries.get(0);}
    void removeFirst(){if(!entries.isEmpty())entries.remove(0);}
    boolean offer(Entry candidate){
        for(Entry existing:entries)if(existing.height==candidate.height&&
            existing.hash.equals(candidate.hash)&&existing.memo.equals(candidate.memo))return true;
        if(entries.size()==LIMIT)return false;
        entries.add(candidate);
        return true;
    }
    void dropAtOrAfter(long height){entries.removeIf(entry->entry.height>=height);}
    static long rewindHeight(long birth,long height){return Math.max(birth,height>100?height-100:birth);}
    static boolean confirmed(long tip,long height){return tip>=height&&tip-height>=9;}
    static boolean finalEnough(long tip,long height){return tip>=height&&tip-height>=100;}
}
