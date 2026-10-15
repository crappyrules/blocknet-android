package com.blocknet.wallet;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Optional biometric wrapping of a wallet password. Every encryption/decryption needs a CryptoObject prompt. */
@SuppressWarnings("deprecation")
public final class BiometricVault {
    private static final String PREFIX="blocknet.biometric.v1.";
    private static final byte[] MAGIC="BNBIO001".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_PASSWORD_BYTES=4096,MAX_ENVELOPE_BYTES=8192;
    private static final Object LOCK=new Object();
    private final Context context;
    private final IdentityHashMap<Enrollment,Boolean> pending=new IdentityHashMap<>();
    private final IdentityHashMap<Cipher,Envelope> unlocks=new IdentityHashMap<>();
    public BiometricVault(Context context){this.context=context.getApplicationContext();}
    public static final class Enrollment {
        public final Cipher cipher;
        public final String scope,pendingAlias;
        private boolean completed;
        private Enrollment(Cipher cipher,String scope,String alias){this.cipher=cipher;this.scope=scope;this.pendingAlias=alias;}
    }
    static final class Envelope {
        final String scope,alias;final byte[] iv,ciphertext;
        Envelope(String scope,String alias,byte[] iv,byte[] ciphertext){this.scope=scope;this.alias=alias;this.iv=iv;this.ciphertext=ciphertext;}
    }
    private int availability(){
        if(Build.VERSION.SDK_INT<28)return -100;
        try{
            KeyguardManager lock=context.getSystemService(KeyguardManager.class);
            if(lock==null||!lock.isDeviceSecure())return -101;
            if(Build.VERSION.SDK_INT>=30){BiometricManager manager=context.getSystemService(BiometricManager.class);return manager==null?-102:manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);}
            // Before API30 the framework cannot query strength; enrolled fingerprint is conservative.
            FingerprintManager manager=context.getSystemService(FingerprintManager.class);
            return manager!=null&&manager.isHardwareDetected()&&manager.hasEnrolledFingerprints()?0:-102;
        }catch(RuntimeException unavailable){return -102;}
    }
    public boolean available(){return availability()==0;}
    public String availabilityMessage(){
        int state=availability();
        if(state==0)return "Strong biometric unlock is available.";
        if(state==-100)return "Biometric unlock requires Android 9 or newer. Use your wallet password.";
        if(state==-101)return "Set up a secure device screen lock before enabling biometric unlock.";
        if(Build.VERSION.SDK_INT>=30&&state==BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)return "Enroll a strong biometric in Android Settings, then enable biometric unlock.";
        return "Strong biometric unlock is unavailable. Use your wallet password.";
    }
    public boolean isEnabled(String network,String wallet){
        synchronized(LOCK){try{String scope=scopeFor(network,wallet);Envelope e=read(scope);return e!=null&&keys().containsAlias(e.alias);}catch(Exception invalid){return false;}}
    }
    public Enrollment prepareEnrollment(String network,String wallet)throws Exception {
        if(!available())throw new IOException(availabilityMessage());
        String scope=scopeFor(network,wallet),alias=PREFIX+scope+"."+UUID.randomUUID();
        synchronized(LOCK){
            try{
                KeyGenParameterSpec.Builder spec=new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true);
                if(Build.VERSION.SDK_INT>=30)spec.setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG);
                else spec.setUserAuthenticationValidityDurationSeconds(-1);
                KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(spec.build());
                SecretKey key=generator.generateKey();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
                Enrollment enrollment=new Enrollment(cipher,scope,alias);pending.put(enrollment,true);return enrollment;
            }catch(Exception failure){deleteKey(alias);throw new IOException("Biometric enrollment could not start. Use your wallet password.",failure);}
        }
    }
    /** Consumes and wipes password, including failed/cancelled enrollment attempts. */
    public void finishEnrollment(Enrollment enrollment,Cipher authenticated,char[] password)throws Exception {
        byte[] plaintext=null;boolean committed=false;
        try{
            synchronized(LOCK){
                if(enrollment==null||!pending.containsKey(enrollment)||enrollment.completed||authenticated!=enrollment.cipher)throw new IOException("Biometric enrollment is no longer active");
                plaintext=encodePassword(password);
                authenticated.updateAAD(aad(enrollment.scope));
                byte[] ciphertext=authenticated.doFinal(plaintext),iv=authenticated.getIV();
                Envelope replacement=new Envelope(enrollment.scope,enrollment.pendingAlias,iv,ciphertext);
                byte[] bytes=encodeEnvelope(replacement);
                try{writeAtomic(file(enrollment.scope),bytes);committed=true;}
                catch(Exception failure){Envelope current=readSafely(enrollment.scope);committed=current!=null&&current.alias.equals(enrollment.pendingAlias);if(!committed)throw failure;}
                enrollment.completed=true;pending.remove(enrollment);
                try{deleteScopeKeysExcept(enrollment.scope,enrollment.pendingAlias);}catch(Exception cleanupFailure){/* New authenticated enrollment remains usable; old keys have no stored ciphertext. */}
            }
        }catch(Exception failure){if(!committed)cancelEnrollment(enrollment);throw failure;}
        finally{if(plaintext!=null)Arrays.fill(plaintext,(byte)0);if(password!=null)Arrays.fill(password,'\0');}
    }
    public void cancelEnrollment(Enrollment enrollment)throws Exception {
        synchronized(LOCK){if(enrollment==null||enrollment.completed)return;if(pending.remove(enrollment)!=null)deleteKey(enrollment.pendingAlias);}
    }
    public Cipher prepareUnlock(String network,String wallet)throws Exception {
        String scope=scopeFor(network,wallet);
        synchronized(LOCK){
            try{
                Envelope stored=read(scope);if(stored==null)throw new IOException("Biometric unlock is not enabled for this wallet");
                Key key=keys().getKey(stored.alias,null);if(!(key instanceof SecretKey))throw new IOException("Biometric key is unavailable");
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,stored.iv));
                unlocks.put(cipher,stored);return cipher;
            }catch(Exception invalid){invalidate(scope);throw reenable(invalid);}
        }
    }
    public void cancelUnlock(Cipher cipher){synchronized(LOCK){unlocks.remove(cipher);}}
    /** Caller owns the returned chars and must wipe them after the wallet unlock operation. */
    public char[] decrypt(String network,String wallet,Cipher authenticated)throws Exception {
        String scope=scopeFor(network,wallet);byte[] plaintext=null;
        synchronized(LOCK){
            Envelope prepared=unlocks.remove(authenticated);
            if(prepared==null||!prepared.scope.equals(scope))throw new IOException("Biometric unlock is no longer active");
            try{
                Envelope current=read(scope);
                if(current==null||!current.alias.equals(prepared.alias)||!Arrays.equals(current.iv,prepared.iv)||!Arrays.equals(current.ciphertext,prepared.ciphertext))throw new IOException("Biometric enrollment changed");
                authenticated.updateAAD(aad(scope));
                plaintext=authenticated.doFinal(prepared.ciphertext);return decodePassword(plaintext);
            }catch(Exception invalid){
                if(invalid instanceof IOException||hasCause(invalid,AEADBadTagException.class)||hasCause(invalid,android.security.keystore.KeyPermanentlyInvalidatedException.class)){invalidate(scope);throw reenable(invalid);}
                throw new IOException("Biometric authentication was not completed. Use your wallet password or try again.",invalid);
            }
            finally{if(plaintext!=null)Arrays.fill(plaintext,(byte)0);}
        }
    }
    public void disable(String network,String wallet)throws Exception {synchronized(LOCK){invalidate(scopeFor(network,wallet));}}
    private void invalidate(String scope)throws Exception {
        // Removing ciphertext first makes key cleanup failure remain safely disabled.
        Files.deleteIfExists(file(scope).toPath());deleteScopeKeysExcept(scope,null);
        for(Iterator<Map.Entry<Cipher,Envelope>> i=unlocks.entrySet().iterator();i.hasNext();)if(i.next().getValue().scope.equals(scope))i.remove();
        for(Iterator<Enrollment> i=pending.keySet().iterator();i.hasNext();){Enrollment e=i.next();if(e.scope.equals(scope)){e.completed=true;i.remove();}}
    }
    private static boolean hasCause(Throwable error,Class<? extends Throwable> type){for(int depth=0;error!=null&&depth<8;depth++,error=error.getCause())if(type.isInstance(error))return true;return false;}
    private static IOException reenable(Exception cause){return new IOException("Biometric unlock needs to be enabled again using your wallet password.",cause);}
    private static KeyStore keys()throws Exception {KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);return keys;}
    private static void deleteKey(String alias){try{keys().deleteEntry(alias);}catch(Exception ignored){}}
    private static void deleteScopeKeysExcept(String scope,String keep)throws Exception {
        KeyStore store=keys();List<String> remove=new ArrayList<>();Enumeration<String> aliases=store.aliases();String prefix=PREFIX+scope+".";
        while(aliases.hasMoreElements()){String alias=aliases.nextElement();if(alias.startsWith(prefix)&&!alias.equals(keep))remove.add(alias);}
        for(String alias:remove)store.deleteEntry(alias);
    }
    private File file(String scope){return new File(new File(context.getNoBackupFilesDir(),"biometric-vault"),scope+".bin");}
    private Envelope readSafely(String scope){try{return read(scope);}catch(Exception invalid){return null;}}
    private Envelope read(String scope)throws IOException {
        File target=file(scope);if(!target.exists())return null;
        try(InputStream in=new FileInputStream(target)){return decodeEnvelope(scope,readBounded(in));}
    }
    static String scopeFor(String network,String wallet)throws Exception {
        if((!"mainnet".equals(network)&&!"testnet".equals(network))||wallet==null||!wallet.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,47}\\.wallet\\.dat"))throw new IllegalArgumentException("Choose a supported wallet for biometric unlock");
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(("blocknet-biometric-v1\0"+network+"\0"+wallet).getBytes(StandardCharsets.UTF_8));
        StringBuilder scope=new StringBuilder();for(byte b:hash)scope.append(String.format(Locale.ROOT,"%02x",b&255));return scope.toString();
    }
    private static byte[] aad(String scope){return ("Blocknet Android biometric vault v1:"+scope).getBytes(StandardCharsets.UTF_8);}
    static byte[] encodePassword(char[] password)throws IOException {
        if(password==null||password.length==0||password.length>MAX_PASSWORD_BYTES)throw new IOException("Wallet password length is unsupported");
        ByteBuffer encoded=null;
        try{encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(password));if(encoded.remaining()>MAX_PASSWORD_BYTES)throw new IOException("Wallet password is too long");byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return bytes;}
        catch(CharacterCodingException invalid){throw new IOException("Wallet password contains invalid Unicode",invalid);}
        finally{if(encoded!=null&&encoded.hasArray())Arrays.fill(encoded.array(),(byte)0);}
    }
    static char[] decodePassword(byte[] plaintext)throws IOException {
        if(plaintext==null||plaintext.length==0||plaintext.length>MAX_PASSWORD_BYTES)throw new IOException("Invalid biometric password envelope");CharBuffer decoded=null;
        try{decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext));char[] chars=new char[decoded.remaining()];decoded.get(chars);return chars;}
        catch(CharacterCodingException invalid){throw new IOException("Invalid biometric password envelope",invalid);}
        finally{if(decoded!=null&&decoded.hasArray())Arrays.fill(decoded.array(),'\0');}
    }
    private static byte[] readBounded(InputStream in)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[1024];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>MAX_ENVELOPE_BYTES)throw new IOException("Invalid biometric envelope size");out.write(bytes,0,n);}return out.toByteArray();}
    private static void validate(Envelope e)throws IOException {
        if(e==null||!e.scope.matches("[a-f0-9]{64}")||!e.alias.matches(java.util.regex.Pattern.quote(PREFIX+e.scope+".")+"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||e.iv==null||e.iv.length!=12||e.ciphertext==null||e.ciphertext.length<17||e.ciphertext.length>MAX_PASSWORD_BYTES+16)throw new IOException("Invalid biometric envelope");
    }
    static byte[] encodeEnvelope(Envelope e)throws IOException {
        validate(e);ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){byte[] alias=e.alias.getBytes(StandardCharsets.US_ASCII);out.write(MAGIC);out.writeShort(alias.length);out.write(alias);out.writeByte(e.iv.length);out.write(e.iv);out.writeInt(e.ciphertext.length);out.write(e.ciphertext);}return bytes.toByteArray();
    }
    static Envelope decodeEnvelope(String scope,byte[] bytes)throws IOException {
        if(bytes.length>MAX_ENVELOPE_BYTES)throw new IOException("Invalid biometric envelope size");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){byte[] magic=new byte[MAGIC.length];in.readFully(magic);if(!Arrays.equals(magic,MAGIC))throw new IOException("Invalid biometric envelope version");int aliasLength=in.readUnsignedShort();if(aliasLength<1||aliasLength>160)throw new IOException("Invalid biometric alias");byte[] alias=new byte[aliasLength];in.readFully(alias);int ivLength=in.readUnsignedByte();if(ivLength!=12)throw new IOException("Invalid biometric IV");byte[] iv=new byte[ivLength];in.readFully(iv);int length=in.readInt();if(length<17||length>MAX_PASSWORD_BYTES+16)throw new IOException("Invalid biometric ciphertext size");byte[] encrypted=new byte[length];in.readFully(encrypted);if(in.read()!=-1)throw new IOException("Unexpected biometric envelope data");Envelope e=new Envelope(scope,new String(alias,StandardCharsets.US_ASCII),iv,encrypted);validate(e);return e;}
    }
    private static void writeAtomic(File target,byte[] bytes)throws IOException {
        Path directory=target.toPath().getParent();Files.createDirectories(directory);Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        Path temporary=Files.createTempFile(directory,"biometric-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{try(FileOutputStream out=new FileOutputStream(temporary.toFile())){out.write(bytes);out.getFD().sync();}Files.move(temporary,target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
    }
}
