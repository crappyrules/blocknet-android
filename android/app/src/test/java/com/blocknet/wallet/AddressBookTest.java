package com.blocknet.wallet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

public class AddressBookTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private static final String A="KNNpZhPcJX92oAyvcVWJhQXJjsWRYsEiPHej6ZTM2LuSJBDS3VQebjUjUCzYqBnidt1JXYUiUgA69HeBoTuVuQYWwnhQN";
    private static final String B="TCpDTBpjwaHcjWEXJwoT5MHrFpoi9cTuftzNLN7bNoBJjCSJEH5ZGXwNcUauhjgoV2ocF9RJWm8Ui2k1QAkAYrwMgG8Wk";
    private static final String LEGACY="3op2Pn13qHb9795mHW9HehiYVLiU7jR2kamRtv32h7s2VTrRnBJRnrH5d8J7Nzxr84yosvn61CcKVkDWcVNZnHQR";
    private final AddressBookCrypto crypto=new AddressBookCrypto(create->new SecretKeySpec(new byte[32],"AES"));
    private AddressBook book(){return new AddressBook(new File(folder.getRoot(),"contacts.json"),crypto);}
    private static void rejects(Runnable action){try{action.run();fail("accepted invalid value");}catch(IllegalArgumentException expected){}}

    @Test public void validatesFullAddressChecksumAndNetwork(){
        assertTrue(AddressBook.isNameInput("alice"));
        assertTrue(AddressBook.isNameInput("$alice"));
        assertFalse(AddressBook.isNameInput(A));
        assertEquals(A,AddressBook.address(A,"mainnet"));
        assertEquals(B,AddressBook.address(B,"mainnet"));
        assertEquals(LEGACY,AddressBook.address(LEGACY,"mainnet"));
        rejects(()->AddressBook.address(A,"testnet"));
        rejects(()->AddressBook.address(A.substring(0,A.length()-1)+"1","mainnet"));
        rejects(()->AddressBook.address("A".repeat(92),"mainnet"));
        rejects(()->AddressBook.address("$alice","mainnet"));
    }

    @Test public void persistsNetworkScopedCanonicalTargetsAndRejectsDuplicates() throws Exception {
        AddressBook book=book();
        AddressBook.Entry alice=book.save("","mainnet",A,"$Alice","Alice",true,1000,0);
        assertEquals("alice",alice.handle);assertEquals(A,alice.address);
        assertEquals(AddressBook.Status.MATCH,alice.status);
        AddressBook reopened=book();
        assertEquals(A,reopened.get(alice.id,"mainnet").address);
        assertTrue(reopened.list("testnet").isEmpty());
        AddressBook.Entry testnet=book.save("","testnet",LEGACY,"","Testnet contact",false,1000,0);
        assertEquals(LEGACY,book.get(testnet.id,"testnet").address);
        rejects(()->{try{book.save("","mainnet",A,"","Other",false,2000,0);}catch(IOException e){throw new AssertionError(e);}});
        rejects(()->{try{book.save("","mainnet",B,"@ALICE","Other",false,2000,0);}catch(IOException e){throw new AssertionError(e);}});
        AddressBook.Entry edited=book.save(alice.id,"mainnet",A,"$alice","New label",false,3000,alice.revision);
        assertEquals("New label",edited.label);assertEquals(1000,edited.verifiedAt);
        AddressBook.Entry changed=book.save(alice.id,"mainnet",B,"","New target",false,4000,edited.revision);
        assertEquals(B,changed.address);assertEquals(AddressBook.Status.UNVERIFIED,changed.status);
        book.delete(alice.id,"mainnet");assertTrue(book.list("mainnet").isEmpty());
        assertEquals(1,book.list("testnet").size());
    }

    @Test public void recheckNeverRetargetsSavedAddress() throws Exception {
        AddressBook book=book();AddressBook.Entry entry=book.save("","mainnet",A,"$alice","Alice",true,1000,0);
        AddressBook.Check matching=book.recheck(entry.id,"mainnet",name->A,2000);
        assertEquals(AddressBook.Status.MATCH,matching.status);
        assertTrue(matching.canReviewWithoutChoice());
        assertEquals(A,matching.entry.address);
        AddressBook.Check reassigned=book.recheck(entry.id,"mainnet",name->B,3000);
        assertEquals(AddressBook.Status.CHANGED,reassigned.status);
        assertFalse(reassigned.canReviewWithoutChoice());
        assertEquals(B,reassigned.observedAddress);
        assertEquals(A,book().get(entry.id,"mainnet").address);
        assertEquals(2000,book.get(entry.id,"mainnet").verifiedAt);
        AddressBook.Check missing=book.recheck(entry.id,"mainnet",name->{throw new AddressBook.NameNotFound();},4000);
        assertEquals(AddressBook.Status.MISSING,missing.status);
        assertFalse(missing.canReviewWithoutChoice());
        assertEquals(A,book.get(entry.id,"mainnet").address);
        AddressBook.Check offline=book.recheck(entry.id,"mainnet",name->{throw new IOException("Wi-Fi unavailable");},5000);
        assertEquals(AddressBook.Status.ERROR,offline.status);
        assertFalse(offline.canReviewWithoutChoice());
        assertEquals(AddressBook.Status.MISSING,book().get(entry.id,"mainnet").status);
        assertEquals(4000,book.get(entry.id,"mainnet").checkedAt);
    }

    @Test public void staleChecksAreBoundedAndUnverifiedNamesAreNotCurrent() throws Exception {
        AddressBook book=book();
        AddressBook.Entry old=book.save("","mainnet",A,"$alice","",true,1000,0);
        AddressBook.Entry newEntry=book.save("","mainnet",B,"$bob","",true,2000,0);
        List<AddressBook.Entry> due=book.due("mainnet",1000+24*60*60*1000L,24*60*60*1000L,1);
        assertEquals(1,due.size());assertEquals(old.id,due.get(0).id);
        assertEquals(0,book.due("mainnet",3000,24*60*60*1000L,2).size());
        assertEquals(AddressBook.Status.ERROR,book.recheck(old.id,"mainnet",name->{throw new IOException("offline");},4000).status);
        assertEquals(1000,book.get(old.id,"mainnet").checkedAt);
        assertEquals(B,book.get(newEntry.id,"mainnet").address);
        assertFalse(AddressBook.autoBatchDue(5000,5001));
        assertTrue(AddressBook.autoBatchDue(5000,5000+AddressBook.AUTO_INTERVAL));
        assertFalse(AddressBook.autoBatchDue(5000,4000));
    }

    @Test public void delayedLookupCannotOverwriteNewerMappingCheck() throws Exception {
        AddressBook book=book();AddressBook.Entry entry=book.save("","mainnet",A,"$alice","Alice",true,1000,0);
        try{
            book.recheck(entry.id,"mainnet",name->{book.recheck(entry.id,"mainnet",ignored->B,3000);return A;},2000);
            fail("older lookup replaced a newer result");
        }catch(IOException expected){}
        assertEquals(AddressBook.Status.CHANGED,book.get(entry.id,"mainnet").status);
        assertEquals(A,book.get(entry.id,"mainnet").address);
    }

    @Test public void cancelledNameCheckRetainsSavedAddressAndLastVerifiedState() throws Exception {
        AddressBook book=book();AddressBook.Entry entry=book.save("","mainnet",A,"$alice","Alice",true,1000,0);
        int queuedEpoch=ShortnameResolver.cancellationEpoch();
        AddressBook.Check check=book.recheck(entry.id,"mainnet",name->{
            ShortnameResolver.cancelActive();
            ShortnameResolver.requireEpoch(queuedEpoch);
            return B;
        },2000);
        assertEquals(AddressBook.Status.ERROR,check.status);
        assertFalse(check.canReviewWithoutChoice());
        AddressBook.Entry persisted=book().get(entry.id,"mainnet");
        assertEquals(A,persisted.address);
        assertEquals(AddressBook.Status.MATCH,persisted.status);
        assertEquals(1000,persisted.checkedAt);
        assertEquals(1000,persisted.verifiedAt);
        assertEquals(2000,persisted.attemptedAt);
    }

    @Test public void selectedRecipientMustBeReselectedAfterAnyEdit() throws Exception {
        AddressBook book=book();AddressBook.Entry selected=book.save("","mainnet",A,"$alice","Alice",true,1000,0);
        assertTrue(AddressBook.selectionCurrent(selected,selected.revision,A,A));
        assertFalse(AddressBook.selectionCurrent(selected,selected.revision,A,B));
        AddressBook.Entry edited=book.save(selected.id,"mainnet",A,"$alice","New label",false,2000,selected.revision);
        assertEquals(A,edited.address);
        assertFalse(AddressBook.selectionCurrent(edited,selected.revision,A,A));
        assertTrue(AddressBook.selectionCurrent(edited,edited.revision,A,A));
    }

    @Test public void editFormCannotOverwriteNewerRecipientRevision() throws Exception {
        AddressBook book=book();AddressBook.Entry opened=book.save("","mainnet",A,"$alice","Original",true,1000,0);
        AddressBook.Entry newer=book.save(opened.id,"mainnet",B,"","Updated elsewhere",false,2000,opened.revision);
        try{
            book.save(opened.id,"mainnet",A,"$alice","Stale form",false,3000,opened.revision);
            fail("stale edit overwrote recipient");
        }catch(IOException expected){assertTrue(expected.getMessage().contains("changed while editing"));}
        assertEquals(B,book.get(opened.id,"mainnet").address);
        assertEquals("Updated elsewhere",book.get(opened.id,"mainnet").label);
        assertEquals(newer.revision,book.get(opened.id,"mainnet").revision);
    }

    @Test public void failedAutomaticCandidateRotatesAndBacksOff() throws Exception {
        AddressBook book=book();AddressBook.Entry first=book.save("","mainnet",A,"$alice","",true,1000,0);
        AddressBook.Entry second=book.save("","mainnet",B,"$bob","",true,2000,0);
        long day=AddressBook.AUTO_INTERVAL;
        assertEquals(first.id,book.due("mainnet",day+2000,day,1).get(0).id);
        assertEquals(AddressBook.Status.ERROR,book.recheck(first.id,"mainnet",name->{throw new IOException("offline");},day+2000).status);
        assertEquals(1000,book.get(first.id,"mainnet").checkedAt);
        assertEquals(day+2000,book.get(first.id,"mainnet").attemptedAt);
        assertEquals(second.id,book.due("mainnet",day+2001,day,1).get(0).id);
        assertFalse(book.due("mainnet",day+2001,day,2).stream().anyMatch(entry->entry.id.equals(first.id)));
        assertTrue(book.due("mainnet",day+2000+6*60*60*1000L,day,2).stream().anyMatch(entry->entry.id.equals(first.id)));
    }

    @Test public void damagedStorageIsNeverOverwritten() throws Exception {
        File file=new File(folder.getRoot(),"contacts.json");
        java.nio.file.Files.write(file.toPath(),"{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AddressBook book=new AddressBook(file,crypto);
        try{book.save("","mainnet",A,"","",false,1,0);fail("corruption ignored");}catch(IOException expected){}
        assertEquals("{broken",new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
    }
}
