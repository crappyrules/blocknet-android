package com.blocknet.wallet;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Refreshes the official mainnet checkpoint list before the core starts. */
public final class CheckpointManager {
    public static final String SOURCE = "https://bntpool.com/checkpoints.dat";
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private CheckpointManager() { }
    public static final class Status {
        public final int entries;
        public final long maxHeight;
        public final boolean refreshed;
        public final String warning;
        private Status(Map<Long,String> records, boolean refreshed, String warning) {
            entries = records.size(); maxHeight = records.isEmpty() ? 0 : Collections.max(records.keySet());
            this.refreshed = refreshed; this.warning = warning;
        }
    }
    /** A nonempty file is available; the core API must still confirm that it loaded it. */
    public static Status inspect(File dataDir) {
        try { return new Status(readExisting(new File(dataDir,"checkpoints.dat")),false,""); }
        catch (IOException e) { return new Status(Collections.emptyMap(),false,"Existing checkpoints are invalid: " + e.getMessage()); }
    }
    /** Seeds a missing file from the packaged mainnet snapshot, closing the supplied stream.
     * Existing data is never overwritten; availability still needs core API verification.
     */
    public static synchronized Status seedBundled(File dataDir, InputStream bundled) {
        File target=new File(dataDir,"checkpoints.dat");
        SortedMap<Long,String> existing=new TreeMap<>();
        try(InputStream in=bundled) {
            existing=readExisting(target);
            if(!existing.isEmpty())return new Status(existing,false,"");
            SortedMap<Long,String> records=parse(readBounded(in,MAX_BYTES));
            // Startup calls this before spawning the core; recheck to retain external additions.
            if(target.exists())return inspect(dataDir);
            writeAtomic(target,encode(records));
            return new Status(records,true,"");
        } catch(Exception failure) {
            return new Status(existing,false,"Could not seed bundled mainnet checkpoints: "+failure.getMessage());
        }
    }
    /** Best effort: failures retain the last validated file and do not prevent startup. */
    public static synchronized Status refresh(File dataDir) {
        return refresh(dataDir,()->download(URL::openConnection));
    }
    interface ConnectionFactory { URLConnection open(URL url) throws IOException; }
    static synchronized Status refresh(File dataDir, ConnectionFactory factory) {
        return refresh(dataDir,()->download(factory));
    }
    interface Downloader { byte[] fetch() throws IOException; }
    static Status refresh(File dataDir, Downloader downloader) {
        File target = new File(dataDir,"checkpoints.dat");
        SortedMap<Long,String> existing;
        try { existing = readExisting(target); }
        catch (IOException e) {
            return new Status(Collections.emptyMap(),false,"Checkpoint refresh skipped: existing file is invalid ("+ e.getMessage()+")");
        }
        try {
            SortedMap<Long,String> remote = parse(downloader.fetch());
            SortedMap<Long,String> merged = validateCandidate(existing,remote);
            if (merged.equals(existing)) {
                String warning = !existing.isEmpty() && remote.lastKey() < existing.lastKey()
                    ? "The checkpoint server has an older list; keeping the newer local checkpoints." : "";
                return new Status(existing,false,warning);
            }
            writeAtomic(target,encode(merged));
            return new Status(merged,true,"");
        } catch (Exception e) {
            String warning = "Could not refresh checkpoints: " + e.getMessage();
            if (existing.isEmpty()) warning += ". No validated checkpoint file is available; the core will try its own download.";
            return new Status(existing,false,warning);
        }
    }
    private static SortedMap<Long,String> readExisting(File target) throws IOException {
        if (!target.exists()) return new TreeMap<>();
        try (InputStream in = new FileInputStream(target)) { return parse(readBounded(in,MAX_BYTES)); }
    }
    private static byte[] download(ConnectionFactory factory) throws IOException {
        long started=System.nanoTime();String stage="https-connect";int httpStatus=-1;
        HttpsURLConnection c = (HttpsURLConnection)factory.open(new URL(SOURCE));
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000); c.setReadTimeout(15000);
            c.setRequestProperty("Accept","text/plain");
            c.setRequestProperty("User-Agent","Blocknet-Android-Wallet/1.0");
            c.connect();
            stage="http-status";httpStatus=c.getResponseCode();
            if (httpStatus != 200) throw new IOException("HTTP " + httpStatus);
            if (c.getContentLengthLong() > MAX_BYTES) throw new IOException("Checkpoint download exceeds 32 MiB");
            stage="https-body";
            try (InputStream in = c.getInputStream()) { return readBounded(in,MAX_BYTES); }
        } catch (IOException failure) {
            // Diagnose the fixed public endpoint without dumping socket addresses or credentials.
            StringBuilder causes=new StringBuilder();Throwable cause=failure;
            for(int depth=0;cause!=null&&depth<6;depth++,cause=cause.getCause()) {
                if(depth>0)causes.append(" -> ");causes.append(cause.getClass().getSimpleName());
            }
            throw new IOException("Checkpoint transport stage="+stage+", elapsed_ms="+((System.nanoTime()-started)/1000000)
                +", http_status="+httpStatus+", proxy="+c.usingProxy()+", causes="+causes,failure);
        } finally { c.disconnect(); }
    }
    static byte[] readBounded(InputStream in, int limit) throws IOException {
        if (limit < 0) throw new IllegalArgumentException("Negative byte limit");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (count > limit - out.size()) throw new IOException("Checkpoint download exceeds byte limit");
            out.write(buffer,0,count);
        }
        return out.toByteArray();
    }
    static SortedMap<Long,String> parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Checkpoint file exceeds 32 MiB");
        final String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IOException("Checkpoint file is not UTF-8",e); }
        SortedMap<Long,String> records = new TreeMap<>(); int lineNumber = 0;
        for (String raw : text.split("\\n",-1)) {
            lineNumber++; String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0 || colon != line.lastIndexOf(':')) throw malformed(lineNumber);
            String height = line.substring(0,colon).trim();
            String hash = line.substring(colon+1).trim();
            if (hash.startsWith("0x")) hash = hash.substring(2);
            if (!height.matches("[0-9]{1,19}") || !hash.matches("[a-fA-F0-9]{64}")) throw malformed(lineNumber);
            long h;
            try { h = Long.parseLong(height); }
            catch (NumberFormatException e) { throw malformed(lineNumber); }
            if (h <= 0) throw malformed(lineNumber);
            hash = hash.toLowerCase(Locale.ROOT);
            String previous = records.putIfAbsent(h,hash);
            if (previous != null && !previous.equals(hash)) throw new IOException("Conflicting checkpoint height " + h);
        }
        if (records.isEmpty()) throw new IOException("Checkpoint file has no entries");
        return Collections.unmodifiableSortedMap(records);
    }
    static SortedMap<Long,String> validateCandidate(SortedMap<Long,String> existing, SortedMap<Long,String> remote) throws IOException {
        if (remote.isEmpty()) throw new IOException("Checkpoint file has no entries");
        for (Map.Entry<Long,String> entry : remote.entrySet()) {
            String old = existing.get(entry.getKey());
            if (old != null && !old.equals(entry.getValue())) throw new IOException("Conflicting checkpoint height " + entry.getKey());
        }
        // A stale server may omit locally generated records; never delete them.
        TreeMap<Long,String> merged = new TreeMap<>(existing); merged.putAll(remote);
        return Collections.unmodifiableSortedMap(merged);
    }
    private static byte[] encode(Map<Long,String> records) {
        StringBuilder body = new StringBuilder("# Mainnet checkpoints from " + SOURCE + "\n");
        for (Map.Entry<Long,String> entry : records.entrySet()) body.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static IOException malformed(int line) { return new IOException("Invalid checkpoint row " + line); }
    private static void writeAtomic(File target, byte[] bytes) throws IOException {
        Path directory = target.toPath().getParent(); Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory,"checkpoints-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileOutputStream out = new FileOutputStream(temp.toFile())) { out.write(bytes); out.getFD().sync(); }
            Files.move(temp,target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
