package com.blocknet.wallet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

public class AddressBookCryptoTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private static final String ADDRESS="KNNpZhPcJX92oAyvcVWJhQXJjsWRYsEiPHej6ZTM2LuSJBDS3VQebjUjUCzYqBnidt1JXYUiUgA69HeBoTuVuQYWwnhQN";
    private final SecretKeySpec key=new SecretKeySpec(new byte[32],"AES");
    private AddressBookCrypto crypto(){return new AddressBookCrypto(create->key);}
    private File file(){return new File(folder.getRoot(),"address-book.json");}
    private static void cannotRead(AddressBook book)throws Exception {
        try{book.list("mainnet");fail("damaged or keyless book was opened");}catch(IOException expected){}
    }
    private byte[] legacyJson(){
        AddressBook.Entry entry=new AddressBook.Entry(UUID.randomUUID().toString(),"mainnet",ADDRESS,"alice","Alice",
            AddressBook.Status.MATCH,ADDRESS,1000,1000,1000,1);
        JsonObject root=new JsonObject();root.addProperty("version",1);JsonArray rows=new JsonArray();rows.add(entry.json());root.add("entries",rows);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test public void repeatedEncryptionUsesDistinctNoncesAndAuthenticatesHeader() throws Exception {
        byte[] plain="fixture only".getBytes(StandardCharsets.UTF_8);
        byte[] first=crypto().seal(plain),second=crypto().seal(plain);
        assertFalse(Arrays.equals(first,second));
        assertArrayEquals(plain,crypto().open(first));
        byte[] changedHeader=first.clone();changedHeader[4]=2;
        try{crypto().open(changedHeader);fail("changed envelope version accepted");}catch(IOException expected){}
    }

    @Test public void encryptedFileSurvivesRestartWithSameKeyAndHidesPlaintext() throws Exception {
        AddressBook first=new AddressBook(file(),crypto());
        AddressBook.Entry saved=first.save("","mainnet",ADDRESS,"$alice","Private label",true,1000,0);
        byte[] envelope=Files.readAllBytes(file().toPath());
        assertTrue(AddressBookCrypto.hasMagic(envelope));
        assertFalse(new String(envelope,StandardCharsets.ISO_8859_1).contains(ADDRESS));
        assertFalse(new String(envelope,StandardCharsets.ISO_8859_1).contains("Private label"));
        AddressBook afterUpdate=new AddressBook(file(),crypto());
        assertEquals(saved.address,afterUpdate.get(saved.id,"mainnet").address);
        assertEquals("Private label",afterUpdate.get(saved.id,"mainnet").label);
        assertArrayEquals(envelope,Files.readAllBytes(file().toPath()));
    }

    @Test public void legacyMigrationVerifiesAndAtomicallyReplacesPlaintext() throws Exception {
        byte[] original=legacyJson();Files.write(file().toPath(),original);
        AddressBook book=new AddressBook(file(),crypto());
        assertEquals(ADDRESS,book.list("mainnet").get(0).address);
        byte[] migrated=Files.readAllBytes(file().toPath());
        assertTrue(AddressBookCrypto.hasMagic(migrated));
        assertFalse(Arrays.equals(original,migrated));
        assertEquals(ADDRESS,new AddressBook(file(),crypto()).list("mainnet").get(0).address);
    }

    @Test public void migrationFailureKeepsExistingPlaintextForRecovery() throws Exception {
        byte[] original=legacyJson();Files.write(file().toPath(),original);
        AddressBook unavailable=new AddressBook(file(),new AddressBookCrypto(create->{throw new IOException("Keystore unavailable");}));
        cannotRead(unavailable);
        assertArrayEquals(original,Files.readAllBytes(file().toPath()));
        assertEquals(ADDRESS,new AddressBook(file(),crypto()).list("mainnet").get(0).address);
    }

    @Test public void missingKeyAndCiphertextDamageFailClosedWithoutWipe() throws Exception {
        AddressBook book=new AddressBook(file(),crypto());book.save("","mainnet",ADDRESS,"","Label",false,1000,0);
        byte[] original=Files.readAllBytes(file().toPath());
        AddressBook keyless=new AddressBook(file(),new AddressBookCrypto(create->null));
        cannotRead(keyless);
        try{keyless.save("","mainnet",ADDRESS,"","Overwrite",false,2000,0);fail("missing key overwrote contacts");}catch(IOException expected){}
        assertArrayEquals(original,Files.readAllBytes(file().toPath()));
        byte[] corrupted=original.clone();corrupted[corrupted.length-1]^=1;Files.write(file().toPath(),corrupted);
        cannotRead(book);
        try{book.save("","mainnet",ADDRESS,"","Overwrite",false,2000,0);fail("damaged file overwritten");}catch(IOException expected){}
        assertArrayEquals(corrupted,Files.readAllBytes(file().toPath()));
        byte[] unknownVersion=original.clone();unknownVersion[4]=2;Files.write(file().toPath(),unknownVersion);
        cannotRead(book);
        assertArrayEquals(unknownVersion,Files.readAllBytes(file().toPath()));
    }

    @Test public void emptyFirstRunDoesNotCreateAKeyUntilFirstSave() throws Exception {
        AtomicInteger creations=new AtomicInteger();
        AddressBookCrypto crypto=new AddressBookCrypto(create->{if(create)creations.incrementAndGet();return key;});
        AddressBook book=new AddressBook(file(),crypto);
        assertTrue(book.list("mainnet").isEmpty());assertEquals(0,creations.get());
        book.save("","mainnet",ADDRESS,"","",false,1000,0);
        assertEquals(1,creations.get());
    }

    @Test public void failedEncryptedWriteKeepsPreviousCompleteFile() throws Exception {
        AddressBook book=new AddressBook(file(),crypto());
        AddressBook.Entry saved=book.save("","mainnet",ADDRESS,"","Original",false,1000,0);
        byte[] original=Files.readAllBytes(file().toPath());
        File blockedTemporary=new File(file().getPath()+".tmp");
        assertTrue(blockedTemporary.mkdir());
        try{book.save(saved.id,"mainnet",ADDRESS,"","Replacement",false,2000,saved.revision);
            fail("write succeeded through blocked temporary path");}
        catch(IOException expected){}
        assertArrayEquals(original,Files.readAllBytes(file().toPath()));
        assertEquals("Original",new AddressBook(file(),crypto()).get(saved.id,"mainnet").label);
    }
}
