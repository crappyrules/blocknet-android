package com.blocknet.wallet;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class CheckpointManagerTest {
    private static final String A = "a".repeat(64), B = "b".repeat(64);
    private static SortedMap<Long,String> parse(String text) throws IOException {
        return CheckpointManager.parse(text.getBytes(StandardCharsets.UTF_8));
    }
    @Test public void acceptsCommentsBlankLinesCRLFAndNormalizedHashes() throws Exception {
        SortedMap<Long,String> records = parse("# mainnet\r\n\n 100 : 0x"+A.toUpperCase(Locale.ROOT)+"\r\n200:"+B+"\n");
        assertEquals(2,records.size()); assertEquals(A,records.get(100L)); assertEquals(Long.valueOf(200),records.lastKey());
        try { records.put(300L,A); fail("Mutable parsed result"); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void rejectsEveryMalformedRowRatherThanAcceptingValidSubset() throws Exception {
        for (String bad : new String[]{"", "# comments only\n", "junk", "0:"+A,"-100:"+A,"1e2:"+A,
                "9223372036854775808:"+A,"100:"+A.substring(1),"100:"+"z".repeat(64),
                "100:"+A+":extra","100:"+A+" trailing","100:"+A+"\n200:invalid"}) {
            try { parse(bad); fail("Accepted malformed data"); } catch (IOException expected) { }
        }
        try { CheckpointManager.parse(new byte[]{(byte)0xff}); fail("Accepted invalid UTF8"); }
        catch (IOException expected) { }
    }
    @Test public void rejectsConflictingDuplicateButAllowsIdenticalRows() throws Exception {
        assertEquals(1,parse("100:"+A+"\n100:"+A).size());
        try { parse("100:"+A+"\n100:"+B); fail("Accepted conflict"); } catch (IOException expected) { }
    }
    @Test public void preservesNewerLocalAndMissingRemoteRecords() throws Exception {
        SortedMap<Long,String> existing = parse("100:"+A+"\n200:"+B+"\n300:"+A);
        assertEquals(existing,CheckpointManager.validateCandidate(existing,parse("100:"+A)));
        SortedMap<Long,String> merged = CheckpointManager.validateCandidate(existing,parse("100:"+A+"\n400:"+B));
        assertEquals(4,merged.size()); assertEquals(B,merged.get(200L)); assertEquals(Long.valueOf(400),merged.lastKey());
        assertEquals(3,existing.size());
    }
    @Test public void rejectsRemoteRewritingExistingCheckpoint() throws Exception {
        SortedMap<Long,String> old = parse("100:"+A);
        try { CheckpointManager.validateCandidate(old,parse("100:"+B+"\n200:"+B)); fail("Accepted conflict"); }
        catch (IOException expected) { }
        assertEquals(A,old.get(100L));
        try { CheckpointManager.validateCandidate(old,new TreeMap<>()); fail("Accepted empty candidate"); }
        catch (IOException expected) { }
    }
    @Test public void enforcesDownloadLimitIncludingOneBytePastExactLimit() throws Exception {
        assertArrayEquals(new byte[]{1,2,3},CheckpointManager.readBounded(new ByteArrayInputStream(new byte[]{1,2,3}),3));
        try { CheckpointManager.readBounded(new ByteArrayInputStream(new byte[]{1,2,3,4}),3); fail("Accepted oversized body"); }
        catch (IOException expected) { }
        assertEquals(0,CheckpointManager.readBounded(new ByteArrayInputStream(new byte[0]),0).length);
    }
    @Test public void inspectionNeverClaimsCheckpointAvailabilityForMissingOrInvalidFiles() throws Exception {
        File dir = Files.createTempDirectory("checkpoint-unit-").toFile();
        File file = new File(dir,"checkpoints.dat");
        try {
            CheckpointManager.Status missing=CheckpointManager.inspect(dir);
            assertEquals(0,missing.entries); assertEquals(0,missing.maxHeight); assertFalse(missing.refreshed);
            Files.write(file.toPath(),("100:"+A+"\n200:"+B).getBytes(StandardCharsets.UTF_8));
            CheckpointManager.Status good=CheckpointManager.inspect(dir);
            assertEquals(2,good.entries);assertEquals(200,good.maxHeight);assertEquals("",good.warning);
            Files.write(file.toPath(),"invalid".getBytes(StandardCharsets.UTF_8));
            CheckpointManager.Status bad=CheckpointManager.inspect(dir);
            assertEquals(0,bad.entries);assertFalse(bad.warning.isEmpty());
        } finally { Files.deleteIfExists(file.toPath());Files.deleteIfExists(dir.toPath()); }
    }
    @Test public void offlineFailurePreservesExistingAndMissingDoesNotClaimAvailability() throws Exception {
        File dir=Files.createTempDirectory("checkpoint-offline-").toFile();
        File file=new File(dir,"checkpoints.dat");
        try {
            CheckpointManager.Downloader offline=() -> { throw new IOException("offline"); };
            CheckpointManager.Status missing=CheckpointManager.refresh(dir,offline);
            assertEquals(0,missing.entries);assertFalse(file.exists());assertFalse(missing.warning.isEmpty());
            byte[] original=("100:"+A).getBytes(StandardCharsets.UTF_8);Files.write(file.toPath(),original);
            CheckpointManager.Status old=CheckpointManager.refresh(dir,offline);
            assertEquals(1,old.entries);assertFalse(old.refreshed);assertArrayEquals(original,Files.readAllBytes(file.toPath()));
        } finally {Files.deleteIfExists(file.toPath());Files.deleteIfExists(dir.toPath());}
    }
    @Test public void atomicRefreshAddsRecordsAndRejectsConflictingResponseWithoutChangingFile() throws Exception {
        File dir=Files.createTempDirectory("checkpoint-atomic-").toFile();
        File file=new File(dir,"checkpoints.dat");
        try {
            CheckpointManager.Status first=CheckpointManager.refresh(dir,() -> ("100:"+A).getBytes(StandardCharsets.UTF_8));
            assertTrue(first.refreshed);assertEquals(1,first.entries);
            assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())));
            CheckpointManager.Status appended=CheckpointManager.refresh(dir,() -> ("100:"+A+"\n200:"+B).getBytes(StandardCharsets.UTF_8));
            assertTrue(appended.refreshed);assertEquals(200,appended.maxHeight);
            byte[] original=Files.readAllBytes(file.toPath());
            CheckpointManager.Status conflict=CheckpointManager.refresh(dir,() -> ("100:"+B+"\n300:"+A).getBytes(StandardCharsets.UTF_8));
            assertFalse(conflict.refreshed);assertEquals(200,conflict.maxHeight);assertFalse(conflict.warning.isEmpty());
            assertArrayEquals(original,Files.readAllBytes(file.toPath()));
            assertEquals(1,Objects.requireNonNull(dir.list()).length);
        } finally {Files.deleteIfExists(file.toPath());Files.deleteIfExists(dir.toPath());}
    }
    @Test public void bundledSeedCreatesMissingFileWithPrivateModeAndClosesStream() throws Exception {
        File dir=Files.createTempDirectory("checkpoint-bundle-").toFile();File file=new File(dir,"checkpoints.dat");
        final boolean[] closed={false};
        InputStream asset=new ByteArrayInputStream(("100:"+A+"\n200:"+B).getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException {closed[0]=true;super.close();}
        };
        try {
            CheckpointManager.Status seeded=CheckpointManager.seedBundled(dir,asset);
            assertTrue(seeded.refreshed);assertEquals(2,seeded.entries);assertEquals(200,seeded.maxHeight);assertTrue(seeded.warning.isEmpty());assertTrue(closed[0]);
            assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())));
        } finally {Files.deleteIfExists(file.toPath());Files.deleteIfExists(dir.toPath());}
    }
    @Test public void bundledSeedKeepsNewerLocalFileWithoutReadingStaleOrInvalidAsset() throws Exception {
        File dir=Files.createTempDirectory("checkpoint-bundle-newer-").toFile();File file=new File(dir,"checkpoints.dat");
        try {
            byte[] old=("100:"+A+"\n300:"+B).getBytes(StandardCharsets.UTF_8);Files.write(file.toPath(),old);
            CheckpointManager.Status retained=CheckpointManager.seedBundled(dir,new ByteArrayInputStream("invalid asset".getBytes(StandardCharsets.UTF_8)));
            assertFalse(retained.refreshed);assertEquals(300,retained.maxHeight);assertEquals(2,retained.entries);assertTrue(retained.warning.isEmpty());
            assertArrayEquals(old,Files.readAllBytes(file.toPath()));
        } finally {Files.deleteIfExists(file.toPath());Files.deleteIfExists(dir.toPath());}
    }
    @Test public void invalidBundledAssetNeverCreatesCheckpointFile() throws Exception {
        File dir=Files.createTempDirectory("checkpoint-bundle-invalid-").toFile();File file=new File(dir,"checkpoints.dat");
        try {
            CheckpointManager.Status invalid=CheckpointManager.seedBundled(dir,new ByteArrayInputStream("invalid asset".getBytes(StandardCharsets.UTF_8)));
            assertEquals(0,invalid.entries);assertFalse(invalid.refreshed);assertFalse(invalid.warning.isEmpty());assertFalse(file.exists());
        } finally {Files.deleteIfExists(file.toPath());Files.deleteIfExists(dir.toPath());}
    }
    @Test public void invalidExistingFileIsPreservedAndNeverReportedAsSeeded() throws Exception {
        File dir=Files.createTempDirectory("checkpoint-bundle-local-invalid-").toFile();File file=new File(dir,"checkpoints.dat");
        try {
            byte[] original="invalid local checkpoint file".getBytes(StandardCharsets.UTF_8);Files.write(file.toPath(),original);
            CheckpointManager.Status invalid=CheckpointManager.seedBundled(dir,new ByteArrayInputStream(("100:"+A).getBytes(StandardCharsets.UTF_8)));
            assertEquals(0,invalid.entries);assertFalse(invalid.refreshed);assertFalse(invalid.warning.isEmpty());assertArrayEquals(original,Files.readAllBytes(file.toPath()));
        } finally {Files.deleteIfExists(file.toPath());Files.deleteIfExists(dir.toPath());}
    }
}
