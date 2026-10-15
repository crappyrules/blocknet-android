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
    private volatile String message = "Node stopped";
    private volatile boolean starting;
    private volatile boolean syncRequested;
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
    File binary(String name) { return new File(context.getApplicationInfo().nativeLibraryDir, name); }
    public int port() { return prefs.getInt("port." + network(), 0); }
    public String message() { return message; }
    public boolean starting() { return starting; }
    public boolean syncRequested() { return syncRequested; }
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
        if (alive()) { message = "Node running"; return; }
        starting = true;
        message = "Starting local node…";
        try {
            if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a") || !Arrays.asList("aarch64", "arm64").contains(Os.uname().machine))
                throw new IOException("This wallet requires a 64-bit ARM Android device (arm64-v8a).");
            if (!binary("libblocknet_core.so").canExecute() || !binary("libmantle.so").canExecute())
                throw new IOException("This build requires a 64-bit ARM Android device. Packaged core binaries were not found.");
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
            // The core calls this seed mode: it also lets the local wallet work offline.
            settings.put("seed", true);
            // Core derives the peer-ID endpoint as listen-port + 1; port zero yields privileged port 1.
            if (!settings.has("listen") || settings.optString("listen").equals("/ip4/0.0.0.0/tcp/0"))
                settings.put("listen", network().equals("mainnet") ? "/ip4/0.0.0.0/tcp/28080" : "/ip4/0.0.0.0/tcp/38080");
            if (!settings.has("p2p_max_inbound")) settings.put("p2p_max_inbound", 8);
            if (!settings.has("p2p_max_outbound")) settings.put("p2p_max_outbound", 8);
            writePrivate(settingsFile, settings.toString());
            int port;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
            if (!prefs.edit().putInt("port." + network(), port).commit()) throw new IOException("Could not save node port");
            cookie().delete();
            if (logFile().length() > 2 * 1024 * 1024) Files.move(logFile().toPath(), new File(networkDir(), "core.previous.log").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            List<String> cmd = new ArrayList<>(Arrays.asList("/system/bin/sh", "-c", "echo $$ > \"$BNT_PID_FILE\"; exec \"$@\"", "blocknet-core", binary("libblocknet_core.so").getAbsolutePath(), "--data", dataDir().getAbsolutePath(), "--api", "127.0.0.1:" + port));
            if (network().equals("testnet")) cmd.add("--testnet");
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(networkDir()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile()));
            setEnvironment(pb);
            pb.environment().put("BNT_PID_FILE", pidFile().getAbsolutePath());
            // Settings/file preparation may take time; do not spawn after a network handover.
            connection = SyncNetworkPolicy.read(context);
            if (!connection.allowed) throw new IOException(connection.reason);
            child = pb.start();
            generation++;
            checkpointVerification = null;
            checkpointGeneration = -1;
            child.getOutputStream().close();
            message = "Loading blockchain and connecting to peers…";
            writeMantleConfig();
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
            if (!syncRequested || !alive() || !network().equals("mainnet") || (checkpointRefreshAttempt > 0 && now - checkpointRefreshAttempt < 15 * 60 * 1000)) return;
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
            if (!network().equals("mainnet")) throw new IOException("Published checkpoints are for mainnet");
            directory = dataDir(); expectedGeneration = generation;
        }
        // Download outside the process lock so starting, stopping and status polling stay responsive.
        CheckpointManager.Status status;
        try {
            status = CheckpointManager.refresh(directory, (CheckpointManager.ConnectionFactory) url -> {
                android.net.Network network = SyncNetworkPolicy.allowedNetwork(context);
                if (network == null || downloadEpoch != checkpointDownloadEpoch.get()) throw new IOException("Checkpoint refresh cancelled or no permitted network");
                HttpURLConnection connection = (HttpURLConnection)network.openConnection(url);
                ownedDownload[0] = connection;
                checkpointConnection.set(connection);
                if (!SyncNetworkPolicy.read(context).allowed || downloadEpoch != checkpointDownloadEpoch.get()) {
                    connection.disconnect(); throw new IOException("Sync connection is no longer allowed");
                }
                return connection;
            });
        } finally { checkpointConnection.compareAndSet(ownedDownload[0],null); }
        synchronized (this) {
            if (!network().equals("mainnet") || generation != expectedGeneration) throw new IOException("The node changed while checkpoints were refreshing. Check its current status.");
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
        pb.environment().put("BNT_CONFIG_DIR", new File(root, "mantle").getAbsolutePath());
        pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        pb.environment().put("NO_COLOR", "1");
        pb.environment().put("GOMEMLIMIT", "384MiB");
    }
    void writeMantleConfig() throws Exception {
        File cfg = new File(root, "mantle"); cfg.mkdirs();
        JSONObject core = new JSONObject().put("enabled", true).put("version", "v0.22.0")
            .put("data_dir", dataDir().getAbsolutePath())
            .put("wallet_file", new File(walletsDir(), prefs.getString("active." + network(), "main.wallet.dat")).getAbsolutePath())
            .put("api_addr", "127.0.0.1:" + port());
        writePrivate(new File(cfg, "config.json"), new JSONObject().put("check_interval", "876000h").put("cores", new JSONObject().put(network(), core)).toString());
        writePrivate(new File(cfg, ".last_upgrade_check"), String.valueOf(System.currentTimeMillis()/1000));
        if (pid() > 1) writePrivate(new File(cfg, "core." + network() + ".pid"), String.valueOf(pid()));
    }
    public String mantle(String... args) throws Exception {
        List<String> command = new ArrayList<>(); command.add(binary("libmantle.so").getAbsolutePath()); command.addAll(Arrays.asList(args));
        File out = File.createTempFile("mantle-", ".log", context.getCacheDir());
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(root).redirectErrorStream(true).redirectOutput(out);
            setEnvironment(pb);
            Process p = pb.start(); p.getOutputStream().close();
            if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("Mantle diagnostics timed out"); }
            String result = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8).replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "");
            if (p.exitValue() != 0) throw new IOException(result.trim().isEmpty() ? "Mantle exited " + p.exitValue() : result);
            return result;
        } finally { out.delete(); }
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
