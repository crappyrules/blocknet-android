package com.blocknet.wallet;

import org.junit.Test;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AddressBookCheckBudgetTest {
    @Test public void concurrentActivitiesReserveOnlyOneDailyBatch() throws Exception {
        Map<String,Long> saved=new ConcurrentHashMap<>();
        AddressBookCheckBudget.Store store=new AddressBookCheckBudget.Store(){
            public long last(String network){return saved.getOrDefault(network,0L);}
            public boolean save(String network,long when){saved.put(network,when);return true;}
        };
        ExecutorService workers=Executors.newFixedThreadPool(8);
        CountDownLatch start=new CountDownLatch(1);AtomicInteger admitted=new AtomicInteger();
        try{
            Future<?>[] jobs=new Future<?>[16];
            for(int i=0;i<jobs.length;i++)jobs[i]=workers.submit(()->{try{start.await();if(AddressBookCheckBudget.reserve(store,"mainnet",1000))admitted.incrementAndGet();}catch(Exception error){throw new RuntimeException(error);}});
            start.countDown();for(Future<?> job:jobs)job.get(5,TimeUnit.SECONDS);
            assertEquals(1,admitted.get());
            assertTrue(AddressBookCheckBudget.reserve(store,"testnet",1000));
            assertFalse(AddressBookCheckBudget.reserve(store,"mainnet",1001));
            assertTrue(AddressBookCheckBudget.reserve(store,"mainnet",1000+AddressBook.AUTO_INTERVAL));
        }finally{workers.shutdownNow();}
    }
    @Test public void failedPersistentWriteNeverGrantsBudget() throws Exception {
        AddressBookCheckBudget.Store failing=new AddressBookCheckBudget.Store(){
            public long last(String network){return 0;}
            public boolean save(String network,long when){return false;}
        };
        try{AddressBookCheckBudget.reserve(failing,"mainnet",1000);fail("non-durable reservation accepted");}
        catch(IOException expected){}
    }
}
