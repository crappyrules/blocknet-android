package com.blocknet.wallet;

import android.content.Context;
import org.bouncycastle.crypto.digests.SHA3Digest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** App-private recipient list. Names are annotations; the saved address never follows a name. */
final class AddressBook {
    enum Status { UNVERIFIED, MATCH, CHANGED, MISSING, ERROR }
    interface Lookup { String resolve(String handle) throws IOException; }
    static final class NameNotFound extends IOException { NameNotFound(){super("Shortname was not found");} }
    static final class Entry {
        final String id,network,address,handle,label,observedAddress;
        final Status status;
        final long checkedAt,verifiedAt,attemptedAt,revision;
        Entry(String id,String network,String address,String handle,String label,Status status,String observedAddress,long checkedAt,long verifiedAt,long attemptedAt,long revision){
            this.id=id;this.network=network;this.address=address;this.handle=handle;this.label=label;
            this.status=status;this.observedAddress=observedAddress;this.checkedAt=checkedAt;this.verifiedAt=verifiedAt;
            this.attemptedAt=attemptedAt;this.revision=revision;
        }
        JsonObject json(){JsonObject row=new JsonObject();row.addProperty("id",id);row.addProperty("network",network);
            row.addProperty("address",address);row.addProperty("handle",handle);row.addProperty("label",label);
            row.addProperty("status",status.name());row.addProperty("observedAddress",observedAddress);
            row.addProperty("checkedAt",checkedAt);row.addProperty("verifiedAt",verifiedAt);
            row.addProperty("attemptedAt",attemptedAt);row.addProperty("revision",revision);return row;}
    }
    static final class Check {
        final Entry entry;
        final Status status;
        final String observedAddress,message;
        Check(Entry entry,Status status,String observedAddress,String message){this.entry=entry;this.status=status;this.observedAddress=observedAddress;this.message=message;}
        boolean canReviewWithoutChoice(){return status==Status.MATCH;}
    }

    private static final String ALPHABET="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String CHECKSUM_TAG="blocknet_stealth_address_checksum";
    static final long AUTO_INTERVAL=24*60*60*1000L;
    private static final int MAX_ENTRIES=300,MAX_PLAIN=256*1024,MAX_FILE=MAX_PLAIN+AddressBookCrypto.OVERHEAD;
    private static final Object STORAGE_LOCK=new Object();
    private final File file;
    private final AddressBookCrypto crypto;
    AddressBook(Context context){this(new File(context.getNoBackupFilesDir(),"address-book.json"),new AddressBookCrypto(new AndroidAddressBookKey()));}
    AddressBook(File file,AddressBookCrypto crypto){this.file=file;this.crypto=crypto;}

    static String address(String raw,String network){
        network(network);
        String value=raw==null?"":raw.trim();
        if(!value.matches("[1-9A-HJ-NP-Za-km-z]{64,100}"))throw new IllegalArgumentException("Enter a full Blocknet address");
        byte[] decoded=decode(value);
        if(decoded.length!=64&&decoded.length!=68)throw new IllegalArgumentException("Invalid Blocknet address length");
        if(decoded.length==68){
            SHA3Digest digest=new SHA3Digest(256);
            byte[] prefix=(CHECKSUM_TAG+"blocknet_"+network).getBytes(StandardCharsets.UTF_8);
            digest.update(prefix,0,prefix.length);digest.update(decoded,0,64);
            byte[] sum=new byte[32];digest.doFinal(sum,0);
            for(int i=0;i<4;i++)if(decoded[64+i]!=sum[i])throw new IllegalArgumentException("Invalid Blocknet address checksum or network");
        }
        // Core validates the public keys before any payment; legacy 64-byte addresses have no checksum.
        return value;
    }
    private static byte[] decode(String value){
        BigInteger number=BigInteger.ZERO;
        for(int i=0;i<value.length();i++){
            int digit=ALPHABET.indexOf(value.charAt(i));
            if(digit<0)throw new IllegalArgumentException("Invalid Blocknet address character");
            number=number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }
        byte[] bytes=number.signum()==0?new byte[0]:number.toByteArray();
        if(bytes.length>0&&bytes[0]==0){byte[] withoutSign=new byte[bytes.length-1];System.arraycopy(bytes,1,withoutSign,0,withoutSign.length);bytes=withoutSign;}
        int zeroes=0;while(zeroes<value.length()&&value.charAt(zeroes)=='1')zeroes++;
        byte[] decoded=new byte[zeroes+bytes.length];System.arraycopy(bytes,0,decoded,zeroes,bytes.length);return decoded;
    }
    private static String network(String value){
        if(!"mainnet".equals(value)&&!"testnet".equals(value))throw new IllegalArgumentException("Unsupported wallet network");
        return value;
    }
    private static String handle(String value){return value==null||value.trim().isEmpty()?"":ShortnameResolver.normalize(value);}
    static boolean isNameInput(String raw){
        String value=raw==null?"":raw.trim();
        return value.startsWith("$")||value.startsWith("@")||value.matches("[A-Za-z0-9_.-]{1,64}");
    }
    private static String label(String value){
        String result=value==null?"":value.trim();
        if(result.length()>64||result.chars().anyMatch(c->Character.isISOControl(c)))throw new IllegalArgumentException("Label must be at most 64 visible characters");
        return result;
    }
    private static Entry parse(JsonObject row) throws Exception {
        String id=row.get("id").getAsString(),net=network(row.get("network").getAsString());
        UUID.fromString(id);
        String addr=address(row.get("address").getAsString(),net),name=handle(row.get("handle").getAsString()),title=label(row.get("label").getAsString());
        Status status=Status.valueOf(row.get("status").getAsString());
        String observed=row.get("observedAddress").getAsString();
        if(!observed.isEmpty())address(observed,net);
        long checked=row.get("checkedAt").getAsLong(),verified=row.get("verifiedAt").getAsLong();
        long attempted=row.has("attemptedAt")?row.get("attemptedAt").getAsLong():checked;
        long revision=row.has("revision")?row.get("revision").getAsLong():0;
        if(checked<0||verified<0||verified>checked||attempted<0||revision<0)throw new IOException("Invalid address book timestamps");
        return new Entry(id,net,addr,name,title,status,observed,checked,verified,attempted,revision);
    }
    private List<Entry> read() throws IOException {
        if(!file.exists())return new ArrayList<>();
        try{
            if(file.length()>MAX_FILE)throw new IOException("Address book is too large");
            byte[] raw=Files.readAllBytes(file.toPath());
            if(raw.length>MAX_FILE)throw new IOException("Address book is too large");
            boolean legacy=!AddressBookCrypto.hasMagic(raw);
            if(legacy&&(raw.length>MAX_PLAIN||!looksLikeLegacyJson(raw)))
                throw new IOException("Address book is damaged; the saved file was kept");
            byte[] plain=legacy?raw:crypto.open(raw);
            try{
                List<Entry> entries=parsePlain(plain);
                if(legacy){
                    byte[] sealed=crypto.seal(plain);
                    byte[] verified=crypto.open(sealed);
                    try{if(!Arrays.equals(plain,verified))throw new IOException("Address-book migration verification failed");}
                    finally{Arrays.fill(verified,(byte)0);}
                    writeRaw(sealed);
                }
                return entries;
            }finally{Arrays.fill(plain,(byte)0);}
        }catch(IOException error){throw error;}
        catch(Exception invalid){throw new IOException("Address book is damaged; no changes were made",invalid);}
    }
    private static boolean looksLikeLegacyJson(byte[] raw){
        for(byte value:raw){if(value==' '||value=='\n'||value=='\r'||value=='\t')continue;return value=='{';}
        return false;
    }
    private static List<Entry> parsePlain(byte[] plain)throws Exception {
        String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString();
        JsonObject root=JsonParser.parseString(json).getAsJsonObject();
        if(root.get("version").getAsInt()!=1)throw new IOException("Unsupported address book version");
        JsonArray rows=root.getAsJsonArray("entries");if(rows.size()>MAX_ENTRIES)throw new IOException("Address book has too many entries");
        List<Entry> entries=new ArrayList<>();
        for(int i=0;i<rows.size();i++)entries.add(parse(rows.get(i).getAsJsonObject()));
        return entries;
    }
    private void write(List<Entry> entries) throws IOException {
        try{
            JsonArray rows=new JsonArray();for(Entry entry:entries)rows.add(entry.json());
            JsonObject root=new JsonObject();root.addProperty("version",1);root.add("entries",rows);
            byte[] plain=root.toString().getBytes(StandardCharsets.UTF_8);
            try{if(plain.length>MAX_PLAIN)throw new IOException("Address book is too large");writeRaw(crypto.seal(plain));}
            finally{Arrays.fill(plain,(byte)0);}
        }catch(IOException error){throw error;}
        catch(Exception error){throw new IOException("Could not save address book",error);}
    }
    private void writeRaw(byte[] bytes)throws IOException {
        if(bytes.length>MAX_FILE)throw new IOException("Address book is too large");
        File temporary=new File(file.getPath()+".tmp");
        try{
            File parent=file.getParentFile();if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Could not create address book storage");
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(IOException error){throw error;}
        catch(Exception error){throw new IOException("Could not save address book",error);}
        finally{temporary.delete();}
    }
    List<Entry> list(String net) throws IOException {synchronized(STORAGE_LOCK){
        network(net);List<Entry> result=new ArrayList<>();for(Entry entry:read())if(entry.network.equals(net))result.add(entry);return result;
    }}
    Entry get(String id,String net) throws IOException {synchronized(STORAGE_LOCK){
        network(net);for(Entry entry:read())if(entry.id.equals(id)&&entry.network.equals(net))return entry;
        throw new IOException("Saved recipient is no longer available");
    }}
    List<Entry> due(String net,long now,long interval,int limit) throws IOException {synchronized(STORAGE_LOCK){
        List<Entry> entries=list(net);
        entries.removeIf(entry->entry.handle.isEmpty()
            ||entry.checkedAt>0&&entry.checkedAt<=now&&now-entry.checkedAt<interval
            ||entry.attemptedAt>0&&(entry.attemptedAt>now||now-entry.attemptedAt<Math.min(interval,6*60*60*1000L)));
        entries.sort(Comparator.comparingLong((Entry entry)->entry.attemptedAt).thenComparingLong(entry->entry.checkedAt));
        return new ArrayList<>(entries.subList(0,Math.min(limit,entries.size())));
    }}
    static boolean autoBatchDue(long last,long now){return last<=0||now>=last&&now-last>=AUTO_INTERVAL;}
    static boolean selectionCurrent(Entry entry,long revision,String savedAddress,String formAddress){
        return entry.revision==revision&&entry.address.equals(savedAddress)&&entry.address.equals(formAddress);
    }
    Entry save(String id,String net,String rawAddress,String rawHandle,String rawLabel,boolean verified,long now,long expectedRevision) throws IOException {synchronized(STORAGE_LOCK){
        String addr=address(rawAddress,net),name=handle(rawHandle),title=label(rawLabel);
        if(verified&&name.isEmpty())throw new IllegalArgumentException("A verified entry needs a shortname");
        List<Entry> entries=read();Entry old=null;int position=-1;
        for(int i=0;i<entries.size();i++)if(entries.get(i).id.equals(id)){old=entries.get(i);position=i;break;}
        if(!id.isEmpty()&&old==null)throw new IOException("Saved recipient is no longer available");
        if(old!=null&&!old.network.equals(net))throw new IOException("Recipient belongs to another network");
        if(old==null&&expectedRevision!=0||old!=null&&old.revision!=expectedRevision)
            throw new IOException("Saved recipient changed while editing. Open it again before saving.");
        for(Entry entry:entries)if(!entry.id.equals(id)&&entry.network.equals(net)){
            if(entry.address.equals(addr))throw new IllegalArgumentException("This address is already in the address book");
            if(!name.isEmpty()&&entry.handle.equals(name))throw new IllegalArgumentException("This shortname is already in the address book");
        }
        if(old==null&&entries.size()>=MAX_ENTRIES)throw new IOException("Address book is full");
        boolean same=old!=null&&old.address.equals(addr)&&old.handle.equals(name);
        Status status=verified?Status.MATCH:same?old.status:Status.UNVERIFIED;
        long checked=verified?now:same?old.checkedAt:0,previousVerified=verified?now:same?old.verifiedAt:0;
        String observed=verified?addr:same?old.observedAddress:"";
        long attempted=verified?now:same?old.attemptedAt:0;
        long revision=old==null?1:old.revision+1;
        if(revision<=0)throw new IOException("Recipient revision overflow");
        Entry saved=new Entry(old==null?UUID.randomUUID().toString():old.id,net,addr,name,title,status,observed,checked,previousVerified,attempted,revision);
        if(position<0)entries.add(saved);else entries.set(position,saved);
        write(entries);return saved;
    }}
    void delete(String id,String net) throws IOException {synchronized(STORAGE_LOCK){
        List<Entry> entries=read();boolean removed=entries.removeIf(entry->entry.id.equals(id)&&entry.network.equals(net));
        if(!removed)throw new IOException("Saved recipient is no longer available");write(entries);
    }}
    Check recheck(String id,String net,Lookup lookup,long now) throws IOException {
        Entry before;
        long reservation;
        synchronized(STORAGE_LOCK){
            List<Entry> entries=read();int index=-1;
            for(int i=0;i<entries.size();i++)if(entries.get(i).id.equals(id)&&entries.get(i).network.equals(net)){index=i;break;}
            if(index<0)throw new IOException("Saved recipient is no longer available");
            before=entries.get(index);
            reservation=Math.max(now,before.attemptedAt+1);
            if(reservation<=0)throw new IOException("Invalid shortname check time");
            entries.set(index,new Entry(before.id,net,before.address,before.handle,before.label,before.status,
                before.observedAddress,before.checkedAt,before.verifiedAt,reservation,before.revision));
            write(entries);
        }
        if(before.handle.isEmpty())return new Check(before,Status.UNVERIFIED,"","No shortname is saved for this address");
        String observed;Status status;
        try{observed=address(lookup.resolve(before.handle),net);status=observed.equals(before.address)?Status.MATCH:Status.CHANGED;}
        catch(NameNotFound absent){observed="";status=Status.MISSING;}
        catch(IllegalArgumentException invalid){return new Check(before,Status.ERROR,"","Shortname returned an invalid address");}
        catch(IOException unavailable){return new Check(before,Status.ERROR,"",unavailable.getMessage()==null?"Shortname could not be checked":unavailable.getMessage());}
        synchronized(STORAGE_LOCK){
            List<Entry> entries=read();int index=-1;
            for(int i=0;i<entries.size();i++)if(entries.get(i).id.equals(id)&&entries.get(i).network.equals(net)){index=i;break;}
            if(index<0||entries.get(index).revision!=before.revision||entries.get(index).attemptedAt!=reservation)
                throw new IOException("Saved recipient changed during verification. Choose it again.");
            Entry current=entries.get(index);
            long completedAt=Math.max(now,reservation);
            Entry checked=new Entry(before.id,net,before.address,before.handle,current.label,status,observed,completedAt,
                status==Status.MATCH?completedAt:current.verifiedAt,reservation,current.revision);
            entries.set(index,checked);write(entries);return new Check(checked,status,observed,"");
        }
    }
}
