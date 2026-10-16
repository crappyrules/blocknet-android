package com.blocknet.wallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Password-authenticated, encrypted display cache. It never contains spending keys. */
final class OfflineSnapshotStore {
    private static final byte[] MAGIC = {'B','N','C','2'};
    private static final int SALT = 16, NONCE = 12, KEY_BYTES = 32;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static Session session;
    private final File directory;
    private final SharedPreferences state;

    private static final class Session {
        final String scope;
        final byte[] salt, key;
        Session(String scope, byte[] salt, byte[] key) { this.scope=scope; this.salt=salt; this.key=key; }
    }

    OfflineSnapshotStore(Context context) {
        directory = new File(context.getNoBackupFilesDir(), "wallet-snapshots");
        state = context.getSharedPreferences("wallet-snapshot-state",Context.MODE_PRIVATE);
    }

    private File file(String scope) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte b : digest) name.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return new File(directory, name + ".cache");
    }

    boolean has(String scope) {
        synchronized (OfflineSnapshotStore.class) {
        try {
            if (invalid(scope)) return false;
            try (InputStream ignored = new AtomicFile(file(scope)).openRead()) { return true; }
        } catch (Exception missing) { return false; }
        }
    }
    private String invalidKey(String scope) throws Exception { return "invalid."+file(scope).getName(); }
    private boolean invalid(String scope) throws Exception { return state.getBoolean(invalidKey(scope),false); }
    private void setInvalid(String scope,boolean invalid) throws Exception {
        if (!state.edit().putBoolean(invalidKey(scope),invalid).commit())
            throw new IOException("Could not save wallet cache validity");
    }

    static synchronized boolean authorized(String scope) { return session != null && session.scope.equals(scope); }

    static synchronized void lock() {
        if (session != null) Arrays.fill(session.key, (byte)0);
        session = null;
    }

    /** Called only after Core has accepted this wallet password. */
    boolean authorizeOnline(String scope, String password) throws Exception {
        synchronized (OfflineSnapshotStore.class) {
        byte[] salt = random(SALT);
        File existing = file(scope);
        boolean invalidated = false;
        if (has(scope)) {
            try {
                byte[] raw = boundedRead(existing);
                byte[] oldSalt = Arrays.copyOfRange(raw, MAGIC.length, MAGIC.length + SALT);
                byte[] oldKey = derive(password, oldSalt);
                try { decrypt(raw, scope, oldKey); salt = oldSalt; }
                finally { Arrays.fill(oldKey, (byte)0); }
            } catch (Exception changedOrDamaged) {
                // Core authenticated this password; the old cache must not remain usable.
                setInvalid(scope,true);
                new AtomicFile(existing).delete();
                File legacyBackup=new File(existing.getPath()+".bak");
                if (legacyBackup.exists()&&!legacyBackup.delete())
                    throw new IOException("Could not invalidate the old wallet cache");
                invalidated = true;
            }
        }
        byte[] key = derive(password, salt);
        lock(); session = new Session(scope, salt, key);
        return invalidated;
        }
    }

    JSONObject unlockOffline(String scope, String password) throws Exception {
        synchronized (OfflineSnapshotStore.class) {
        File selected = file(scope);
        if (!has(scope)) throw new IOException("No saved view for this wallet. Open it once while sync is available.");
        byte[] raw = boundedRead(selected);
        if (raw.length < MAGIC.length + SALT + NONCE + 16 || !startsWith(raw, MAGIC))
            throw new IOException("The saved wallet view is damaged.");
        byte[] salt = Arrays.copyOfRange(raw, MAGIC.length, MAGIC.length + SALT);
        byte[] key = derive(password, salt);
        try {
            JSONObject value = decodeJson(decrypt(raw, scope, key));
            if (!scope.equals(value.optString("scope")) || value.optLong("savedAt") <= 0)
                throw new IOException("The saved wallet view does not match this wallet.");
            synchronized (OfflineSnapshotStore.class) { lock(); session = new Session(scope, salt, key); } return value;
        } catch (AEADBadTagException wrong) {
            Arrays.fill(key, (byte)0);
            throw new IOException("Incorrect wallet password or damaged saved view.");
        } catch (Exception failure) {
            Arrays.fill(key, (byte)0); throw failure;
        }
        }
    }

    JSONObject read(String scope) throws Exception {
        synchronized (OfflineSnapshotStore.class) {
        Session current = session;
        if (current == null || !current.scope.equals(scope)) return null;
        byte[] raw = boundedRead(file(scope));
        JSONObject value = decodeJson(decrypt(raw, scope, current.key));
        if (!scope.equals(value.optString("scope"))) throw new IOException("The saved wallet view does not match this wallet.");
        return value;
        }
    }

    void save(String scope, JSONObject value) throws Exception {
        synchronized (OfflineSnapshotStore.class) {
        Session current = session;
        if (current == null || !current.scope.equals(scope)) return;
        byte[] plain = value.toString().getBytes(StandardCharsets.UTF_8);
        byte[] raw;
        try { raw = encrypt(plain, scope, current.salt, current.key); }
        finally { Arrays.fill(plain, (byte)0); }
        if (raw.length > MAX_BYTES) throw new IOException("Saved wallet view is too large");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create wallet cache directory");
        Os.chmod(directory.getPath(), 0700);
        File target = file(scope);
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream out = null;
        try { out = atomic.startWrite(); Os.fchmod(out.getFD(),0600); out.write(raw); atomic.finishWrite(out); }
        catch (Exception failure) { if (out != null) atomic.failWrite(out); throw failure; }
        if (invalid(scope)) setInvalid(scope,false);
        }
    }

    static byte[] derive(String password, byte[] salt) throws Exception {
        byte[] secret = password.getBytes(StandardCharsets.UTF_8);
        try {
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt).withIterations(3).withMemoryAsKB(64 * 1024).withParallelism(4).build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator(); generator.init(parameters);
            byte[] key = new byte[KEY_BYTES]; generator.generateBytes(secret,key); return key;
        } finally { Arrays.fill(secret, (byte)0); }
    }

    static byte[] encrypt(byte[] plain, String scope, byte[] salt, byte[] key) throws Exception {
        byte[] nonce = random(NONCE);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] raw = new byte[MAGIC.length + SALT + NONCE + encrypted.length];
        System.arraycopy(MAGIC, 0, raw, 0, MAGIC.length);
        System.arraycopy(salt, 0, raw, MAGIC.length, SALT);
        System.arraycopy(nonce, 0, raw, MAGIC.length + SALT, NONCE);
        System.arraycopy(encrypted, 0, raw, MAGIC.length + SALT + NONCE, encrypted.length);
        return raw;
    }

    static byte[] decrypt(byte[] raw, String scope, byte[] key) throws Exception {
        if (raw.length < MAGIC.length + SALT + NONCE + 16 || !startsWith(raw, MAGIC))
            throw new IOException("The saved wallet view is damaged.");
        byte[] nonce = Arrays.copyOfRange(raw, MAGIC.length + SALT, MAGIC.length + SALT + NONCE);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(raw, MAGIC.length + SALT + NONCE, raw.length - MAGIC.length - SALT - NONCE);
    }

    private static JSONObject decodeJson(byte[] plain) throws Exception {
        try { return new JSONObject(new String(plain, StandardCharsets.UTF_8)); }
        finally { Arrays.fill(plain, (byte)0); }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i=0; i<prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }
    private static byte[] random(int count) { byte[] bytes = new byte[count]; RANDOM.nextBytes(bytes); return bytes; }
    private static byte[] boundedRead(File file) throws IOException {
        try (InputStream in = new AtomicFile(file).openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count=in.read(buffer))!=-1) {
                if (out.size() > MAX_BYTES-count) throw new IOException("The saved wallet view is too large.");
                out.write(buffer,0,count);
            }
            return out.toByteArray();
        }
    }
}
