package com.blocknet.wallet;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class TransactionNotifierTest {
    private static final class ToggleStore implements TransactionNotifier.AlertToggleStore {
        boolean enabled=true,failSave,failClear;
        int seen=1;
        @Override public boolean enabled(){return enabled;}
        @Override public boolean saveEnabled(boolean value){if(failSave)return false;enabled=value;return true;}
        @Override public boolean clearSeen(){if(failClear)return false;seen=0;return true;}
    }
    private static String tx(int n){return String.format(Locale.ROOT,"%064x",n);}
    private static TransactionNotifier.Event incoming(int tx,int index){return new TransactionNotifier.Event(tx(tx),index,true,100);}
    private static TransactionNotifier.Event outgoing(int tx){return new TransactionNotifier.Event(tx(tx),-1,false,100);}
    @Test public void firstExistingHistoryIsQuietAndAnEmptyWalletStillStartsWatching(){
        TransactionNotifier.Seen existing=new TransactionNotifier.Seen();
        assertTrue(TransactionNotifier.observe(existing,Arrays.asList(incoming(1,0),outgoing(2))).isEmpty());
        assertTrue(TransactionNotifier.observe(existing,Arrays.asList(incoming(1,0),outgoing(2))).isEmpty());
        TransactionNotifier.Seen empty=new TransactionNotifier.Seen();assertTrue(TransactionNotifier.observe(empty,Collections.emptyList()).isEmpty());
        assertEquals(1,TransactionNotifier.observe(empty,Collections.singletonList(incoming(1,0))).size());
    }
    @Test public void outputIdentityDeduplicatesConfirmationAndAmountChangesButKeepsDistinctOutputs(){
        TransactionNotifier.Seen state=new TransactionNotifier.Seen();TransactionNotifier.observe(state,Collections.emptyList());
        assertEquals(2,TransactionNotifier.observe(state,Arrays.asList(incoming(1,0),incoming(1,1))).size());
        TransactionNotifier.Event changedAmount=new TransactionNotifier.Event(tx(1),0,true,200);
        assertTrue(TransactionNotifier.observe(state,Arrays.asList(changedAmount,incoming(1,1))).isEmpty());
    }
    @Test public void outgoingPaymentAndOwnChangeProduceOnlyOnePaymentAlert(){
        TransactionNotifier.Seen state=new TransactionNotifier.Seen();TransactionNotifier.observe(state,Collections.emptyList());
        List<TransactionNotifier.Event> fresh=TransactionNotifier.observe(state,Arrays.asList(outgoing(1),incoming(1,0),incoming(2,0)));
        assertEquals(2,fresh.size());assertFalse(fresh.get(0).incoming);assertTrue(fresh.get(1).incoming);
        assertTrue(TransactionNotifier.observe(state,Arrays.asList(outgoing(1),incoming(1,0),incoming(2,0))).isEmpty());
        state.touch("out:"+tx(3));assertTrue(TransactionNotifier.observe(state,Collections.singletonList(incoming(3,0))).isEmpty());
    }
    @Test public void durableWindowIsBoundedAndRetainsFrequentlyObservedEvents(){
        TransactionNotifier.Seen state=new TransactionNotifier.Seen();
        for(int i=0;i<5000;i++){state.touch("out:"+tx(i));state.touch("in:"+tx(9000)+":0");}
        assertEquals(4096,state.ids.size());assertTrue(state.ids.contains("in:"+tx(9000)+":0"));assertFalse(state.ids.contains("out:"+tx(0)));
    }
    @Test public void disabledPollDoesNoHistoryWorkAndReenabledHistoryIsQuiet() throws Exception {
        AtomicInteger historyReads=new AtomicInteger();
        TransactionNotifier.Seen seen=new TransactionNotifier.Seen();
        TransactionNotifier.PollAction readHistory=()->{
            historyReads.incrementAndGet();
            assertTrue(TransactionNotifier.observe(seen,Collections.singletonList(incoming(1,0))).isEmpty());
        };
        for(int i=0;i<100;i++)assertFalse(TransactionNotifier.pollWhenEnabled(false,readHistory));
        assertEquals(0,historyReads.get());
        assertTrue(TransactionNotifier.pollWhenEnabled(true,readHistory));
        assertEquals(1,historyReads.get());
        assertEquals(1,TransactionNotifier.observe(seen,Arrays.asList(incoming(1,0),incoming(2,0))).size());
        // setEnabled clears the durable seen scopes on each preference transition.
        TransactionNotifier.Seen afterReenable=new TransactionNotifier.Seen();
        assertTrue(TransactionNotifier.observe(afterReenable,Arrays.asList(incoming(1,0),incoming(2,0))).isEmpty());
        assertEquals(1,TransactionNotifier.observe(afterReenable,Arrays.asList(incoming(1,0),incoming(2,0),incoming(3,0))).size());
    }
    @Test public void toggleCommitFailuresPreserveAnActiveBaselineAndNeverEnableStaleHistory() throws Exception {
        ToggleStore store=new ToggleStore();
        store.failSave=true;
        try{TransactionNotifier.changeEnabled(store,false);fail("disable commit must fail");}
        catch(java.io.IOException expected){}
        assertTrue(store.enabled);assertEquals(1,store.seen);
        store.failSave=false;
        TransactionNotifier.changeEnabled(store,false);
        assertFalse(store.enabled);assertEquals(1,store.seen);
        store.failClear=true;
        try{TransactionNotifier.changeEnabled(store,true);fail("baseline reset must fail closed");}
        catch(java.io.IOException expected){}
        assertFalse(store.enabled);assertEquals(1,store.seen);
        store.failClear=false;store.failSave=true;
        try{TransactionNotifier.changeEnabled(store,true);fail("enable commit must fail");}
        catch(java.io.IOException expected){}
        assertFalse(store.enabled);assertEquals(0,store.seen);
        store.failSave=false;
        TransactionNotifier.changeEnabled(store,true);
        assertTrue(store.enabled);assertEquals(0,store.seen);
        TransactionNotifier.changeEnabled(store,true);
        assertEquals(0,store.seen);
    }
}
