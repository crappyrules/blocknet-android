package com.blocknet.wallet;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class AnnouncementPendingQueueTest {
    private static String repeat(char value,int count){char[] chars=new char[count];java.util.Arrays.fill(chars,value);return new String(chars);}
    private static AnnouncementPendingQueue.Entry entry(long height,char memo) throws Exception {
        return new AnnouncementPendingQueue.Entry(height,repeat('a',64),repeat(memo,244));
    }

    @Test public void unavailableManifestSurvivesRestartWithoutBlockingLaterNotice() throws Exception {
        AnnouncementPendingQueue queue=new AnnouncementPendingQueue();
        queue.offer(entry(200,'b')); // manifest unavailable: retain for another connection
        queue.offer(entry(201,'c'));
        String persisted=queue.encode();
        AnnouncementPendingQueue restarted=AnnouncementPendingQueue.decode(persisted);
        assertEquals(2,restarted.size());
        AnnouncementPendingQueue.Entry unavailable=restarted.first();
        restarted.removeFirst();restarted.offer(unavailable); // bounded failed attempt
        assertEquals(201,restarted.first().height); // later signed notice can resolve
        restarted.removeFirst(); // accepted after valid manifest
        assertEquals(200,AnnouncementPendingQueue.decode(restarted.encode()).first().height);
    }

    @Test public void reorgAndConfirmationBoundariesAreDurable() throws Exception {
        AnnouncementPendingQueue queue=new AnnouncementPendingQueue();
        queue.offer(entry(109,'b'));queue.offer(entry(110,'c'));
        queue=AnnouncementPendingQueue.decode(queue.encode()); // process death
        assertFalse(AnnouncementPendingQueue.confirmed(118,110));
        assertTrue(AnnouncementPendingQueue.confirmed(119,110)); // ten confirmations
        assertFalse(AnnouncementPendingQueue.finalEnough(209,110));
        assertTrue(AnnouncementPendingQueue.finalEnough(210,110));
        assertTrue(AnnouncementPendingQueue.finalEnough(211,110));
        assertEquals(10,AnnouncementPendingQueue.rewindHeight(1,110));
        assertEquals(100,AnnouncementPendingQueue.rewindHeight(1,200));
        assertEquals(101,AnnouncementPendingQueue.rewindHeight(1,201));
        queue.dropAtOrAfter(110);
        assertEquals(1,AnnouncementPendingQueue.decode(queue.encode()).size());
        assertEquals(109,queue.first().height);
    }

    @Test public void boundedEncodingRejectsCorruptionAndDeduplicates() throws Exception {
        AnnouncementPendingQueue queue=new AnnouncementPendingQueue();
        queue.offer(entry(5,'b'));queue.offer(entry(5,'b'));
        assertEquals(1,queue.size());
        for(int i=0;i<63;i++)assertTrue(queue.offer(entry(100+i,(char)('0'+i%10))));
        assertEquals(AnnouncementPendingQueue.LIMIT,queue.size());
        assertFalse(queue.offer(entry(163,'d'))); // keep input cursor for this unqueued candidate
        assertEquals(5,queue.first().height);
        assertTrue(queue.offer(entry(5,'b'))); // replaying the page cannot deadlock on duplicates
        queue.removeFirst(); // a valid manifest accepted and freed one slot
        assertTrue(queue.offer(entry(163,'d')));
        assertEquals(100,queue.first().height);
        try {AnnouncementPendingQueue.decode("5:"+repeat('z',64)+":"+repeat('a',244));fail();}
        catch(IOException expected) { }
    }
}
