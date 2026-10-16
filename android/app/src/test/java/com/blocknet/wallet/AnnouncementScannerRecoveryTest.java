package com.blocknet.wallet;

import android.content.SharedPreferences;
import org.junit.Test;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public class AnnouncementScannerRecoveryTest {
    private static String repeat(char value,int count){char[] bytes=new char[count];java.util.Arrays.fill(bytes,value);return new String(bytes);}
    private static SharedPreferences memoryPreferences(Map<String,Object> saved) {
        return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                if(method.getName().equals("edit")){
                    Map<String,Object> writes=new HashMap<>();Set<String> removes=new HashSet<>();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                        new Class<?>[]{SharedPreferences.Editor.class},(editor,change,values)->{
                            String operation=change.getName();
                            if(operation.startsWith("put")){
                                writes.put((String)values[0],values[1]);removes.remove(values[0]);return editor;
                            }
                            if(operation.equals("remove")){
                                removes.add((String)values[0]);writes.remove(values[0]);return editor;
                            }
                            if(operation.equals("commit")||operation.equals("apply")){
                                for(String key:removes)saved.remove(key);
                                saved.putAll(writes);
                                return operation.equals("commit")?true:null;
                            }
                            throw new UnsupportedOperationException(operation);
                        });
                }
                if(method.getName().startsWith("get"))return saved.getOrDefault(args[0],args[1]);
                throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test public void corruptQueueReplaysFromBirthAndPreservesFinalizedFloorAcrossRestart() throws Exception {
        Map<String,Object> disk=new HashMap<>();
        disk.put("cursorHeight",100000L);disk.put("cursorTx",7);disk.put("cursorOutput",9);
        disk.put("partialHash","old");disk.put("anchorHeight",99999L);
        disk.put("anchorHash","old");disk.put("pending","not:a:valid:queue");
        disk.put("floorSequence",55L);disk.put("floorDigest","trusted");
        SharedPreferences prefs=memoryPreferences(disk);
        try {AnnouncementScanner.readPending(prefs,17);fail("Corrupt queue advanced the cursor.");}
        catch(IOException expected) {assertTrue(expected.getMessage().contains("replaying"));}
        assertEquals(17L,disk.get("cursorHeight"));
        assertEquals(0,disk.get("cursorTx"));
        assertEquals(0,disk.get("cursorOutput"));
        assertFalse(disk.containsKey("pending"));
        assertFalse(disk.containsKey("partialHash"));
        assertFalse(disk.containsKey("anchorHeight"));
        assertEquals(55L,disk.get("floorSequence"));
        assertEquals("trusted",disk.get("floorDigest"));
        assertEquals(0,AnnouncementScanner.readPending(memoryPreferences(disk),17).size());
    }

    @Test public void fullInboxPersistsCandidatesButDoesNotAdvanceCursor() throws Exception {
        Map<String,Object> disk=new HashMap<>();
        disk.put("cursorHeight",300L);disk.put("cursorTx",2);disk.put("cursorOutput",17);
        SharedPreferences prefs=memoryPreferences(disk);
        AnnouncementPendingQueue pending=new AnnouncementPendingQueue();
        for(int i=0;i<AnnouncementPendingQueue.LIMIT;i++)
            assertTrue(pending.offer(new AnnouncementPendingQueue.Entry(100+i,
                repeat('a',64),repeat((char)('0'+i%10),244))));
        assertFalse(pending.offer(new AnnouncementPendingQueue.Entry(200,repeat('a',64),repeat('e',244))));
        AnnouncementScanner.persistBackpressure(prefs,pending);
        assertEquals(300L,disk.get("cursorHeight"));
        assertEquals(2,disk.get("cursorTx"));
        assertEquals(17,disk.get("cursorOutput"));
        AnnouncementPendingQueue restarted=AnnouncementScanner.readPending(memoryPreferences(disk),17);
        assertEquals(64,restarted.size());
        restarted.removeFirst(); // one manifest resolved on a later pass
        assertTrue(restarted.offer(new AnnouncementPendingQueue.Entry(200,repeat('a',64),repeat('e',244))));
        AnnouncementScanner.persistBackpressure(prefs,restarted);
        assertEquals(64,AnnouncementScanner.readPending(memoryPreferences(disk),17).size());
        assertEquals(300L,disk.get("cursorHeight")); // the page can be replayed without losing the candidate
    }
}
