package com.blocknet.wallet;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Fixed loopback transport. No redirects, remote nodes, retries, or credential logging. */
public final class WalletApi {
    public static final class ApiException extends IOException {
        public final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }
    private final NativeRuntime runtime;
    public WalletApi(NativeRuntime runtime) { this.runtime = runtime; }
    public JSONObject get(String path) throws Exception { return request("GET", path, null, null); }
    public JSONObject post(String path, JSONObject body) throws Exception { return request("POST", path, body == null ? "{}" : body.toString(), null); }
    public JSONObject patch(String path, JSONObject body) throws Exception { return request("PATCH", path, body.toString(), null); }
    public JSONObject request(String method, String path, String body, String key) throws Exception {
        return request(method,path,body,key,120000);
    }
    JSONObject request(String method, String path, String body, String key, int readTimeout) throws Exception {
        if (!path.startsWith("/api/") || path.contains("#") || path.contains("\r") || path.contains("\n")) throw new IllegalArgumentException("Invalid local API path");
        if (runtime.port() <= 0 || !runtime.cookie().isFile()) throw new IOException("Local node is still starting. Please wait for it to become ready.");
        String token = new String(Files.readAllBytes(runtime.cookie().toPath()), StandardCharsets.UTF_8).trim();
        if (token.isEmpty()) throw new IOException("Local node authentication is not ready");
        URL url = new URL("http", "127.0.0.1", runtime.port(), path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(4000); c.setReadTimeout(readTimeout);
            c.setRequestMethod(method); c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            if (key != null) c.setRequestProperty("Idempotency-Key", key);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int code = c.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String raw = stream == null ? "" : readBounded(stream, 8 * 1024 * 1024);
            JSONObject response;
            try { response = raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw); }
            catch (Exception e) {
                if (code < 200 || code >= 300) throw new ApiException(code, code == 401 ? "Local node authentication failed. Restart the node." : "Local node request failed (HTTP " + code + ")");
                throw new IOException("Local node returned an invalid response (HTTP " + code + ")");
            }
            if (code < 200 || code >= 300) throw new ApiException(code, response.optString("error", "Local node request failed (HTTP " + code + ")"));
            return response;
        } finally { c.disconnect(); }
    }
    static String readBounded(InputStream stream, int limit) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) { if (out.size() + n > limit) throw new IOException("Node response is too large"); out.write(buffer,0,n); }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
