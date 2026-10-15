package com.blocknet.wallet;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class WalletBackupTest {
    private void validate(byte[] data)throws Exception {
        File file=Files.createTempFile("wallet-backup-header-",".dat").toFile();
        try{Files.write(file.toPath(),data);WalletBackup.validate(file);}finally{Files.deleteIfExists(file.toPath());}
    }
    private byte[] versionOne(int length,int version) {
        byte[] data=new byte[length];System.arraycopy("BLKNTWLT".getBytes(StandardCharsets.US_ASCII),0,data,0,8);data[8]=(byte)version;return data;
    }
    @Test public void retainsLegacyFormatSupport()throws Exception {validate(new byte[44]);validate(new byte[256]);}
    @Test public void acceptsVersionOneEnvelope()throws Exception {validate(versionOne(66,1));validate(versionOne(512,1));}
    @Test public void rejectsEmptyAndTruncatedLegacyFiles()throws Exception {
        for(int size:new int[]{0,1,8,27,28,43}) {
            try{validate(new byte[size]);fail("Accepted truncated backup "+size);}catch(IOException expected){ }
        }
    }
    @Test public void rejectsTruncatedVersionOneDespiteLegacyMinimumSize()throws Exception {
        for(int size:new int[]{44,50,65}) {
            try{validate(versionOne(size,1));fail("Accepted truncated version1 "+size);}catch(IOException expected){ }
        }
    }
    @Test public void rejectsUnsupportedHeaderVersion()throws Exception {
        for(int version:new int[]{0,2,255}) {
            try{validate(versionOne(66,version));fail("Accepted unsupported version");}catch(IOException expected){ }
        }
    }
}
