package com.blocknet.wallet;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Camera frames are decoded on device; the result is returned for user review. */
@SuppressWarnings("deprecation")
public final class ScannerActivity extends Activity implements TextureView.SurfaceTextureListener {
    public static final String EXTRA_PAYMENT = "payment";
    private static final int CAMERA_PERMISSION = 43;
    private TextureView preview;
    private TextView status;
    private Button retry;
    private HandlerThread cameraThread;
    private volatile Handler cameraHandler;
    private Camera camera;
    private volatile boolean resumed, completed;
    private long lastDecode;

    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(AppLanguage.wrap(base));}

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        cameraThread = new HandlerThread("wallet-qr-camera"); cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        int horizontal=Math.round(20*getResources().getDisplayMetrics().density),vertical=Math.round(16*getResources().getDisplayMetrics().density);
        overlay.setPadding(horizontal,vertical,horizontal,vertical);
        root.setOnApplyWindowInsetsListener((view,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());overlay.setPadding(horizontal+bars.left,vertical,horizontal+bars.right,vertical+bars.bottom);}else overlay.setPadding(horizontal+insets.getSystemWindowInsetLeft(),vertical,horizontal+insets.getSystemWindowInsetRight(),vertical+insets.getSystemWindowInsetBottom());return insets;});
        overlay.setGravity(Gravity.CENTER_HORIZONTAL);
        overlay.setBackgroundColor(0xCC080B10);
        status = new TextView(this);
        status.setText(LocalizedText.get(this,"Scan a Blocknet payment QR code\nAlign the code in the camera view"));
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        overlay.addView(status);
        retry = new Button(this); retry.setText(LocalizedText.get(this,"Enable camera"));
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
            else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            else startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName())));
        });
        overlay.addView(retry);
        Button cancel = new Button(this); cancel.setText(LocalizedText.get(this,"Cancel"));
        cancel.setOnClickListener(v -> finish()); overlay.addView(cancel);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(overlay, overlayParams);
        setContentView(root);root.requestApplyInsets();
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        } else startCamera();
    }
    @Override protected void onPause() {
        resumed = false;
        Handler handler = cameraHandler;
        if (handler != null) handler.post(this::releaseCamera);
        super.onPause();
    }
    @Override protected void onDestroy() {
        Handler handler = cameraHandler;
        HandlerThread thread = cameraThread;
        cameraThread = null;
        if (handler != null) handler.post(() -> { releaseCamera(); cameraHandler = null; thread.quitSafely(); });
        super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else showError("Camera access is needed to scan a payment QR code.");
    }
    private void startCamera() {
        Handler handler = cameraHandler;
        if (!resumed || completed || handler == null || !preview.isAvailable()) return;
        SurfaceTexture surface = preview.getSurfaceTexture();
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        handler.post(() -> {
            if (!resumed || camera != null || completed || surface == null || surface.isReleased()) return;
            try {
                int id = -1; Camera.CameraInfo info = new Camera.CameraInfo();
                for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                    Camera.getCameraInfo(i, info);
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break; }
                }
                if (id < 0) throw new IllegalStateException("No rear camera");
                Camera.CameraInfo selected = new Camera.CameraInfo(); Camera.getCameraInfo(id, selected);
                camera = Camera.open(id);
                Camera.Parameters params = camera.getParameters();
                Camera.Size best = null;
                for (Camera.Size size : params.getSupportedPreviewSizes()) {
                    if (size.width > 1280 || size.height > 960) continue;
                    if (best == null || size.width * size.height > best.width * best.height) best = size;
                }
                if (best != null) params.setPreviewSize(best.width, best.height);
                List<String> modes = params.getSupportedFocusModes();
                if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                params.setPreviewFormat(android.graphics.ImageFormat.NV21);
                camera.setParameters(params);
                int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
                camera.setDisplayOrientation((selected.orientation - degrees + 360) % 360);
                camera.setPreviewTexture(surface);
                Camera.Size frameSize = camera.getParameters().getPreviewSize();
                camera.setPreviewCallback((data, source) -> decodeFrame(data, frameSize));
                camera.startPreview();
                runOnUiThread(() -> retry.setVisibility(View.GONE));
            } catch (Exception e) { releaseCamera(); showError("Camera could not start. Check camera permission and try again."); }
        });
    }
    private void decodeFrame(byte[] data, Camera.Size size) {
        if (!resumed || completed || System.currentTimeMillis() - lastDecode < 220) return;
        lastDecode = System.currentTimeMillis();
        MultiFormatReader reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, true);
        reader.setHints(hints);
        String result = null;
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(data, size.width, size.height, 0, 0, size.width, size.height, false);
            result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source))).getText();
        } catch (Exception ignored) { } finally { reader.reset(); }
        if (result == null) return;
        try { PaymentRequest.parse(result); }
        catch (IllegalArgumentException e) {
            runOnUiThread(() -> status.setText(LocalizedText.get(this,"This QR code is not a supported Blocknet payment request."))); return;
        }
        String payment = result;
        runOnUiThread(() -> {
            if (!resumed || isFinishing() || completed) return;
            completed = true;
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_PAYMENT, payment)); finish();
        });
    }
    private void releaseCamera() {
        if (camera == null) return;
        try { camera.setPreviewCallback(null); camera.stopPreview(); } catch (Exception ignored) { }
        try { camera.release(); } catch (Exception ignored) { } finally { camera = null; }
    }
    private void showError(String message) {
        runOnUiThread(() -> { if (!isFinishing()) { status.setText(LocalizedText.get(this,message)); retry.setVisibility(View.VISIBLE); } });
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { startCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Handler handler = cameraHandler;
        // Keep the SurfaceTexture alive until its camera producer has stopped.
        if (handler != null && handler.post(() -> { releaseCamera(); surface.release(); })) return false;
        surface.release();
        return false;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
}
