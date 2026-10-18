package com.blocknet.wallet;

import android.content.SharedPreferences;
import java.io.IOException;

/** A process-shared, persistent reservation for the bounded automatic name-check batch. */
final class AddressBookCheckBudget {
    interface Store { long last(String network); boolean save(String network,long when); }
    private static final Object LOCK=new Object();
    private AddressBookCheckBudget(){}
    static Store preferences(SharedPreferences prefs){
        return new Store(){
            public long last(String network){return prefs.getLong("batch."+network,0);}
            public boolean save(String network,long when){return prefs.edit().putLong("batch."+network,when).commit();}
        };
    }
    static boolean reserve(Store store,String network,long now) throws IOException {
        synchronized(LOCK){
            long last=store.last(network);
            if(!AddressBook.autoBatchDue(last,now))return false;
            if(!store.save(network,now))throw new IOException("Could not reserve the address-book check budget");
            return true;
        }
    }
}
