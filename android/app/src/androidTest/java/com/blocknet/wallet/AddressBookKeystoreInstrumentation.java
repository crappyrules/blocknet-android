package com.blocknet.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;

/** Isolated sidecar-only, synthetic storage and key. It never opens a wallet or starts Core. */
public final class AddressBookKeystoreInstrumentation extends Instrumentation {
    private static final String TEST_PACKAGE="com.blocknet.wallet.offlinetest";
    private static final String ADDRESS="KNNpZhPcJX92oAyvcVWJhQXJjsWRYsEiPHej6ZTM2LuSJBDS3VQebjUjUCzYqBnidt1JXYUiUgA69HeBoTuVuQYWwnhQN";
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);this.arguments=arguments;start();}
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static KeyStore keys()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);return store;}
    private static byte[] legacyFixture(){
        AddressBook.Entry entry=new AddressBook.Entry(UUID.randomUUID().toString(),"mainnet",ADDRESS,"alice","Fixture",
            AddressBook.Status.MATCH,ADDRESS,1000,1000,1000,1);
        JsonObject root=new JsonObject();root.addProperty("version",1);JsonArray entries=new JsonArray();entries.add(entry.json());root.add("entries",entries);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void failsClosed(AddressBook book)throws Exception {
        try{book.list("mainnet");throw new IllegalStateException("Damaged or keyless fixture opened");}
        catch(IOException expected){}
    }
    private static void remove(File file){if(file!=null){file.delete();new File(file.getPath()+".tmp").delete();}}
    private void prepare(File fixture,String alias)throws Exception {
        check(!fixture.exists()&&!keys().containsAlias(alias),"Test fixture already exists");
        AddressBookCrypto crypto=new AddressBookCrypto(new AndroidAddressBookKey(alias));
        AddressBook book=new AddressBook(fixture,crypto);
        AddressBook.Entry saved=book.save("","mainnet",ADDRESS,"","Fixture",false,1000,0);
        byte[] envelope=Files.readAllBytes(fixture.toPath());
        check(AddressBookCrypto.hasMagic(envelope),"Address-book fixture was not encrypted");
        check(!new String(envelope,StandardCharsets.ISO_8859_1).contains(ADDRESS),"Address appeared in encrypted file");
        check(new AddressBook(fixture,new AddressBookCrypto(new AndroidAddressBookKey(alias)))
            .get(saved.id,"mainnet").address.equals(ADDRESS),"Keystore-backed restart failed");
        File legacy=new File(fixture.getPath()+".legacy"),damaged=new File(fixture.getPath()+".damaged");
        try{
            byte[] plain=legacyFixture();Files.write(legacy.toPath(),plain);
            AddressBook migrating=new AddressBook(legacy,crypto);
            check(migrating.list("mainnet").get(0).address.equals(ADDRESS),"Plaintext migration lost fixture");
            byte[] migrated=Files.readAllBytes(legacy.toPath());
            check(AddressBookCrypto.hasMagic(migrated)&&!Arrays.equals(plain,migrated),"Plaintext migration did not encrypt");
            File blocked=new File(legacy.getPath()+".tmp");check(blocked.mkdir(),"Could not block temporary write");
            try{migrating.save(migrating.list("mainnet").get(0).id,"mainnet",ADDRESS,"","Changed",false,2000,1);
                throw new IllegalStateException("Interrupted write replaced fixture");}
            catch(IOException expected){}
            check(Arrays.equals(migrated,Files.readAllBytes(legacy.toPath())),"Interrupted write changed stored fixture");
            Files.write(damaged.toPath(),envelope);
            failsClosed(new AddressBook(damaged,new AddressBookCrypto(create->null)));
            check(Arrays.equals(envelope,Files.readAllBytes(damaged.toPath())),"Key-loss check altered fixture");
            byte[] tampered=envelope.clone();tampered[tampered.length-1]^=1;Files.write(damaged.toPath(),tampered);
            failsClosed(new AddressBook(damaged,crypto));
            check(Arrays.equals(tampered,Files.readAllBytes(damaged.toPath())),"Corruption check altered fixture");
        }finally{remove(legacy);remove(damaged);}
    }
    private void verifyAfterUpdate(File fixture,String alias)throws Exception {
        check(fixture.isFile()&&keys().containsAlias(alias),"Prepared fixture or test key is missing");
        AddressBook reopened=new AddressBook(fixture,new AddressBookCrypto(new AndroidAddressBookKey(alias)));
        check(reopened.list("mainnet").size()==1&&reopened.list("mainnet").get(0).address.equals(ADDRESS),
            "Same-signer update lost encrypted fixture");
        check(fixture.delete(),"Could not remove synthetic fixture");
        keys().deleteEntry(alias);
    }
    @Override public void onStart(){
        super.onStart();Bundle result=new Bundle();
        try{
            Context context=getTargetContext();
            check(TEST_PACKAGE.equals(context.getPackageName()),"Isolated test package is required");
            String runId=UUID.fromString(arguments.getString("runId","")).toString();
            String phase=arguments.getString("phase","");
            String alias="blocknet.address-book.test."+runId;
            File fixture=new File(context.getNoBackupFilesDir(),"address-book-test-"+runId+".json");
            if("prepare".equals(phase))prepare(fixture,alias);
            else if("verify".equals(phase))verifyAfterUpdate(fixture,alias);
            else throw new IllegalArgumentException("Use prepare or verify phase");
            result.putString("stream","OK isolated address-book Keystore "+phase+"; synthetic fixture only.\n");
            finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("stream","FAIL isolated address-book Keystore test ("+failure.getClass().getSimpleName()+").\n");finish(Activity.RESULT_CANCELED,result);}
    }
}
