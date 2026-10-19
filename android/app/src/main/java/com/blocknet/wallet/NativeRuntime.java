package com.blocknet.wallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Owns only the packaged core process; all paths are inside Android's app sandbox. */
public final class NativeRuntime {
    private static NativeRuntime instance;
    public static synchronized NativeRuntime get(Context context) {
        if (instance == null) instance = new NativeRuntime(context.getApplicationContext());
        return instance;
    }
    final Context context;
    final SharedPreferences prefs;
    final File root;
    private Process child;
    private long generation;
    private String bootstrapProofNonce;
    private volatile String message = "Node stopped";
    private volatile boolean starting;
    private volatile boolean syncRequested;
    static final class WalletSelection {
        final long serial,generation;
        final String network,wallet,walletId;
        WalletSelection(long serial,long generation,String network,String wallet,String walletId) {
            this.serial=serial;this.generation=generation;this.network=network;
            this.wallet=wallet;this.walletId=walletId;
        }
    }
    private volatile WalletSelection walletSelection;
    private long walletSelectionSerial;
    private final Object syncRequestLock = new Object();
    private Object syncRequestOwner;
    private volatile String checkpointWarning = "";
    private JSONObject checkpointVerification;
    private long checkpointGeneration = -1;
    private long checkpointRefreshAttempt;
    private final AtomicReference<HttpURLConnection> checkpointConnection = new AtomicReference<>();
    private final AtomicLong checkpointDownloadEpoch = new AtomicLong();
    private NativeRuntime(Context c) {
        context = c;
        prefs = c.getSharedPreferences("wallet", Context.MODE_PRIVATE);
        root = new File(c.getNoBackupFilesDir(), "blocknet");
        root.mkdirs();
        try { Os.chmod(root.getPath(), 0700); } catch (Exception ignored) { }
    }
    public String network() { return prefs.getString("network", "mainnet"); }
    File networkDir() { return new File(root, network()); }
    File dataDir() { return new File(networkDir(), "data"); }
    File walletsDir() { File f = new File(networkDir(), "wallets"); f.mkdirs(); return f; }
    File cookie() { return new File(dataDir(), "api.cookie"); }
    File pidFile() { return new File(networkDir(), "core.pid"); }
    File logFile() { return new File(networkDir(), "core.log"); }
    File bootstrapProofFile() { return new File(dataDir(), "bootstrap-shutdown-proof.json"); }
    File binary(String name) { return new File(context.getApplicationInfo().nativeLibraryDir, name); }
    public int port() { return prefs.getInt("port." + network(), 0); }
    public String message() { return message; }
    public boolean starting() { return starting; }
    public boolean syncRequested() { return syncRequested; }
    synchronized void noteWalletSelected(String network,String wallet,String walletId) {
        walletSelection=new WalletSelection(++walletSelectionSerial,generation,network,wallet,walletId);
    }
    WalletSelection walletSelection() { return walletSelection; }
    void setSyncRequested(boolean requested) { synchronized (syncRequestLock) { syncRequested = requested; if (!requested) syncRequestOwner = null; } }
    void requestSync(Object owner) { synchronized (syncRequestLock) { syncRequestOwner = owner; syncRequested = true; } }
    void clearSyncRequest(Object owner) { synchronized (syncRequestLock) { if (syncRequestOwner == owner) { syncRequested = false; syncRequestOwner = null; } } }
    synchronized void stopServiceGeneration(Object owner, long expected) throws Exception {
        synchronized (syncRequestLock) { if (syncRequestOwner != null && syncRequestOwner != owner) return; }
        if (generation == expected) stop();
    }
    void cancelCheckpointRefresh() {
        checkpointDownloadEpoch.incrementAndGet();
        HttpURLConnection download = checkpointConnection.get();
        if (download != null) download.disconnect();
    }
    /** Signal before waiting on a wallet/API operation that holds the process lock. */
    void signalNetworkPause() {
        cancelCheckpointRefresh();
        int current = pid();
        if (ownsPid(current)) try { Os.kill(current, OsConstants.SIGTERM); } catch (Exception ignored) { }
    }
    private int pid() {
        try { return Integer.parseInt(new String(Files.readAllBytes(pidFile().toPath()), StandardCharsets.US_ASCII).trim()); }
        catch (Exception e) { return -1; }
    }
    private boolean ownsPid(int pid) {
        if (pid <= 1) return false;
        try {
            byte[] data = Files.readAllBytes(new File("/proc/" + pid + "/cmdline").toPath());
            String cmd = new String(data, StandardCharsets.UTF_8).replace('\0', ' ');
            return cmd.startsWith(binary("libblocknet_core.so").getAbsolutePath() + " ")
                && cmd.contains(dataDir().getAbsolutePath());
        } catch (Exception e) { return false; }
    }
    public synchronized boolean alive() {
        if (child != null && child.isAlive()) return true;
        if (ownsPid(pid())) return true;
        if (child != null) message = "Node exited (code " + child.exitValue() + "). See the node log for details.";
        return false;
    }
    public synchronized void restart() throws Exception { stop(); start(); }
    public synchronized long generation() { return generation; }
    public synchronized void stopGeneration(long expected) throws Exception { if (generation == expected) stop(); }
    public synchronized void start() throws Exception {
        SyncNetworkPolicy.State connection = SyncNetworkPolicy.read(context);
        if (!connection.allowed) throw new IOException(connection.reason);
        if (!MobileCorePolicy.syncPermitted(context)) throw new IOException("Node stopped while wallet is in background");
        if (alive()) { message = "Node running"; return; }
        starting = true;
        message = "Starting local node…";
        try {
            if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a") || !Arrays.asList("aarch64", "arm64").contains(Os.uname().machine))
                throw new IOException("This wallet requires a 64-bit ARM Android device (arm64-v8a).");
            if (!binary("libblocknet_core.so").canExecute())
                throw new IOException("This build requires a 64-bit ARM Android device. The packaged Core executable was not found.");
            dataDir().mkdirs(); walletsDir().mkdirs();
            Os.chmod(networkDir().getPath(), 0700);
            Os.chmod(dataDir().getPath(), 0700);
            Os.chmod(walletsDir().getPath(), 0700);
            if (network().equals("mainnet")) {
                message = "Preparing mainnet checkpoints…";
                CheckpointManager.Status checkpoints;
                try (InputStream bundled = context.getAssets().open("checkpoints-mainnet.dat")) {
                    checkpoints = CheckpointManager.seedBundled(dataDir(), bundled);
                }
                checkpointWarning = checkpointMessage(checkpoints);
            } else checkpointWarning = "";
            String wallet = prefs.getString("active." + network(), "main.wallet.dat");
            File settingsFile = new File(dataDir(), "settings.json");
            JSONObject settings = settingsFile.exists()
                ? new JSONObject(new String(Files.readAllBytes(settingsFile.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
            settings.put("wallet_file", new File(walletsDir(), wallet).getAbsolutePath());
            settings.put("chain_cache_cap", 256);
            if (network().equals("mainnet")) settings.put("full_sync", false);
            // The mobile executable starts offline without acting as a seed or listening for peers.
            // Older executables do not understand these settings and retain their existing behavior.
            settings.put("seed", !BuildConfig.CORE_MOBILE_CLIENT);
            // Keep settings.json compatible with the pinned older Core for rollback.
            settings.remove("mobile_client");
            settings.remove("allow_offline_start");
            // Core derives the peer-ID endpoint as listen-port + 1 on non-mobile seed nodes.
            if (!settings.has("listen") || settings.optString("listen").equals("/ip4/0.0.0.0/tcp/0"))
                settings.put("listen", network().equals("mainnet") ? "/ip4/0.0.0.0/tcp/28080" : "/ip4/0.0.0.0/tcp/38080");
            if (!settings.has("p2p_max_inbound")) settings.put("p2p_max_inbound", 8);
            if (!settings.has("p2p_max_outbound")) settings.put("p2p_max_outbound", 8);
            writePrivate(settingsFile, settings.toString());
            int port;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
            if (!prefs.edit().putInt("port." + network(), port).commit()) throw new IOException("Could not save node port");
            cookie().delete();
            if (bootstrapProofFile().exists() && !bootstrapProofFile().delete())
                throw new IOException("Could not clear prior node shutdown receipt");
            byte[] proofRandom = new byte[16]; new SecureRandom().nextBytes(proofRandom);
            StringBuilder proofToken = new StringBuilder();
            for (byte value : proofRandom) proofToken.append(String.format(Locale.ROOT, "%02x", value & 255));
            bootstrapProofNonce = proofToken.toString();
            if (logFile().length() > 2 * 1024 * 1024) Files.move(logFile().toPath(), new File(networkDir(), "core.previous.log").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            List<String> cmd = new ArrayList<>(Arrays.asList("/system/bin/sh", "-c", "echo $$ > \"$BNT_PID_FILE\"; exec \"$@\"", "blocknet-core", binary("libblocknet_core.so").getAbsolutePath(), "--data", dataDir().getAbsolutePath(), "--api", "127.0.0.1:" + port));
            if (network().equals("testnet")) cmd.add("--testnet");
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(networkDir()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile()));
            setEnvironment(pb);
            if (BuildConfig.CORE_MOBILE_CLIENT) pb.environment().put("BLOCKNET_MOBILE_CLIENT", "1");
            if (BuildConfig.BOOTSTRAP_QA && network().equals("testnet")) {
                if (settings.optJSONArray("extra_peers") == null || settings.optJSONArray("extra_peers").length() != 1)
                    throw new IOException("Controlled testnet requires exactly one private fixture peer");
                pb.environment().put("BLOCKNET_QA_TESTNET_PEER_ONLY", "1");
            }
            pb.environment().put("BLOCKNET_BOOTSTRAP_PROOF_PATH", bootstrapProofFile().getAbsolutePath());
            pb.environment().put("BLOCKNET_BOOTSTRAP_PROOF_NONCE", bootstrapProofNonce);
            pb.environment().put("BNT_PID_FILE", pidFile().getAbsolutePath());
            // Settings/file preparation may take time; do not spawn after a network handover.
            connection = SyncNetworkPolicy.read(context);
            if (!connection.allowed) throw new IOException(connection.reason);
            if (!MobileCorePolicy.syncPermitted(context)) throw new IOException("Node stopped while wallet is in background");
            child = pb.start();
            generation++;
            checkpointVerification = null;
            checkpointGeneration = -1;
            child.getOutputStream().close();
            message = "Loading blockchain and connecting to peers…";
        } catch (Exception e) { message = e.getMessage(); throw e; }
        finally { starting = false; }
    }
    public synchronized void stop() throws Exception {
        message = "Stopping node…";
        boolean forced = false;
        long logOffset = logFile().length();
        int pid = pid();
        if (ownsPid(pid)) {
            Os.kill(pid, OsConstants.SIGTERM);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (ownsPid(pid) && System.nanoTime() < until) Thread.sleep(100);
            if (ownsPid(pid)) { forced = true; Os.kill(pid, OsConstants.SIGKILL); }
        }
        if (child != null) {
            if (child.isAlive()) child.destroy();
            if (!child.waitFor(3, TimeUnit.SECONDS)) { forced = true; child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); }
            child = null;
        }
        pidFile().delete(); cookie().delete();
        message = "Node stopped";
        if (forced) throw new IOException("The node required a forced stop. Restart it, verify the wallet, and try the backup again.");
        if (logFile().exists()) {
            try (RandomAccessFile log = new RandomAccessFile(logFile(), "r")) {
                log.seek(Math.min(logOffset, log.length()));
                byte[] tail = new byte[(int)Math.min(65536, log.length()-log.getFilePointer())];
                log.readFully(tail);
                if (new String(tail, StandardCharsets.UTF_8).contains("failed to save wallet"))
                    throw new IOException("Core reported a wallet save failure. Check storage space and your recovery phrase before trying a backup again.");
            }
        }
    }
    synchronized JSONObject shutdownProof() throws Exception {
        if (alive() || bootstrapProofNonce == null || !bootstrapProofFile().isFile() ||
            bootstrapProofFile().length() > 1024) return null;
        JSONObject proof = new JSONObject(new String(Files.readAllBytes(bootstrapProofFile().toPath()), StandardCharsets.UTF_8));
        return bootstrapProofNonce.equals(proof.optString("nonce")) ? proof : null;
    }
    public String checkpointWarning() { return checkpointWarning; }
    public synchronized JSONObject verifyCheckpoints() throws Exception {
        if (!network().equals("mainnet")) return new JSONObject();
        if (checkpointVerification == null || checkpointGeneration != generation) {
            checkpointVerification = new WalletApi(this).request("POST", "/api/checkpoints/load", "{}", null, 5000);
            checkpointGeneration = generation;
        }
        return new JSONObject(checkpointVerification.toString());
    }
    private static String checkpointMessage(CheckpointManager.Status status) {
        if (status.warning == null || status.warning.isEmpty()) return "";
        if (status.entries > 0) return "Online checkpoint refresh is unavailable. Using " + status.entries + " saved checkpoints through block " + status.maxHeight + ".";
        return "No validated checkpoint file is available. Connect to the network and refresh checkpoints.";
    }
    public void refreshCheckpointsInBackground() {
        synchronized (this) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (!syncRequested || !alive() || !MobileCorePolicy.syncPermitted(context) || !network().equals("mainnet") || (checkpointRefreshAttempt > 0 && now - checkpointRefreshAttempt < 15 * 60 * 1000)) return;
            checkpointRefreshAttempt = now;
        }
        try { refreshCheckpoints(); } catch (Exception ignored) { }
    }
    public JSONObject refreshCheckpoints() throws Exception {
        final File directory;
        final long expectedGeneration;
        final long downloadEpoch = checkpointDownloadEpoch.get();
        final HttpURLConnection[] ownedDownload = new HttpURLConnection[1];
        synchronized (this) {
            SyncNetworkPolicy.State connection = SyncNetworkPolicy.read(context);
            if (!connection.allowed) throw new IOException(connection.reason);
            if (!MobileCorePolicy.syncPermitted(context)) throw new IOException("Node stopped while wallet is in background");
            if (!network().equals("mainnet")) throw new IOException("Published checkpoints are for mainnet");
            directory = dataDir(); expectedGeneration = generation;
        }
        // Download outside the process lock so starting, stopping and status polling stay responsive.
        CheckpointManager.Status status;
        try {
            status = CheckpointManager.refresh(directory, (CheckpointManager.ConnectionFactory) url -> {
                android.net.Network network = SyncNetworkPolicy.allowedNetwork(context);
                if (network == null || downloadEpoch != checkpointDownloadEpoch.get() || !MobileCorePolicy.syncPermitted(context)) throw new IOException("Checkpoint refresh cancelled or no permitted network");
                HttpURLConnection connection = (HttpURLConnection)network.openConnection(url);
                ownedDownload[0] = connection;
                checkpointConnection.set(connection);
                if (!SyncNetworkPolicy.read(context).allowed || !MobileCorePolicy.syncPermitted(context) || downloadEpoch != checkpointDownloadEpoch.get()) {
                    connection.disconnect(); throw new IOException("Sync connection is no longer allowed");
                }
                return connection;
            });
        } finally { checkpointConnection.compareAndSet(ownedDownload[0],null); }
        synchronized (this) {
            if (!network().equals("mainnet") || generation != expectedGeneration || downloadEpoch != checkpointDownloadEpoch.get() || !MobileCorePolicy.syncPermitted(context)) throw new IOException("The node changed while checkpoints were refreshing. Check its current status.");
            checkpointWarning = checkpointMessage(status);
            checkpointVerification = null; checkpointGeneration = -1;
            JSONObject result;
            if (alive()) result = verifyCheckpoints();
            else result = new JSONObject().put("loaded", 0).put("max_height", status.maxHeight).put("message", "Checkpoints saved for the next node start");
            result.put("entries", status.entries).put("refreshed", status.refreshed).put("warning", checkpointWarning);
            return result;
        }
    }
    void setEnvironment(ProcessBuilder pb) {
        pb.environment().put("HOME", root.getAbsolutePath());
        pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        pb.environment().put("NO_COLOR", "1");
        pb.environment().put("GOMEMLIMIT", "384MiB");
    }
    static void writePrivate(File target, String text) throws Exception {
        target.getParentFile().mkdirs();
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        Os.chmod(tmp.getPath(), 0600);
        Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
