package com.example.hackyeah;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

class CameraController {

    interface Listener {
        void onSample(double avgY, long ts);
        void onError(String message, Throwable t);
    }

    private final Context ctx;
    private final SurfaceHolder surfaceHolder;
    private final Listener listener;

    private final CameraManager cameraManager;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;

    private MediaRecorder mediaRecorder;
    private ImageReader imageReader;

    private boolean pendingStartRecording = false;

    CameraController(Context ctx, SurfaceHolder holder, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.surfaceHolder = holder;
        this.listener = l;
        this.cameraManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        pickBackCamera();
    }

    /** Wywołuj przy starcie pomiaru. Otworzy kamerę i wystartuje, gdy Surface i kamera będą gotowe. */
    void requestStartRecording() {
        pendingStartRecording = true;
        if (cameraDevice == null) {
            openCameraIfNeeded();
            return;
        }
        if (isSurfaceReady()) {
            try { doStartRecording(); }
            catch (Exception e) { listener.onError("Start recording", e); }
        }
    }

    /** Zatrzymuje nagrywanie i zamyka kamerę. */
    void stopRecordingAndClose() {
        try {
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            }
        } catch (Exception ignored) {}
        try { if (mediaRecorder != null) { mediaRecorder.stop(); mediaRecorder.release(); } } catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        try { if (captureSession != null) captureSession.close(); } catch (Exception ignored) {}
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}

        captureSession = null;
        cameraDevice = null;
        mediaRecorder = null;
        imageReader = null;
        pendingStartRecording = false;
    }

    // ---------- internals ----------
    private void openCameraIfNeeded() {
        if (cameraId == null) { listener.onError("No back camera", null); return; }
        if (ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            listener.onError("Brak uprawnienia CAMERA", null);
            return;
        }
        try { cameraManager.openCamera(cameraId, stateCallback, null); }
        catch (CameraAccessException e) { listener.onError("openCamera", e); }
    }

    private boolean isSurfaceReady() {
        return surfaceHolder != null &&
                surfaceHolder.getSurface() != null &&
                surfaceHolder.getSurface().isValid();
    }

    private void pickBackCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { cameraId = id; return; }
            }
        } catch (CameraAccessException e) {
            listener.onError("camera list", e);
        }
    }

    void stopRecordingAndReturnToPreview() {
        // zatrzymaj record
        try {
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            }
        } catch (Exception ignored) {}
        try { if (mediaRecorder != null) { mediaRecorder.stop(); mediaRecorder.release(); } } catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        imageReader = null;
        mediaRecorder = null;

        // wróć do PREVIEW bez zamykania kamery
        try { startPreviewSession(); }
        catch (CameraAccessException e) { listener.onError("Preview after stop", e); }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            if (pendingStartRecording && isSurfaceReady()) {
                try { doStartRecording(); }
                catch (Exception e) { listener.onError("Start after onOpened", e); }
            } else if (isSurfaceReady()) {
                try { startPreviewSession(); }  // NOWE
                catch (CameraAccessException e) { listener.onError("start preview", e); }
            }
        }





        @Override public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close(); cameraDevice = null;
        }

        @Override public void onError(@NonNull CameraDevice camera, int error) {
            camera.close(); cameraDevice = null;
            listener.onError("Camera device error: " + error, null);
        }
    };

    private void doStartRecording() throws IOException, CameraAccessException {
        if (!isSurfaceReady()) throw new IllegalStateException("Preview surface not ready");
        if (cameraDevice == null) throw new IllegalStateException("Camera not opened");

        // MediaRecorder
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        String fn = "video_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".mp4";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, fn);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ppg_better");

        Uri uri = ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Nie można utworzyć pliku video");

        FileDescriptor fd = Objects.requireNonNull(ctx.getContentResolver().openFileDescriptor(uri, "w")).getFileDescriptor();
        mediaRecorder.setOutputFile(fd);
        mediaRecorder.setVideoEncodingBitRate(10_000_000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoSize(1280, 720);
        mediaRecorder.setPreviewDisplay(surfaceHolder.getSurface());
        mediaRecorder.prepare();

        // ImageReader do analizy Y
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);
        imageReader.setOnImageAvailableListener(this::analyzeImage, null);

        // Sesja
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.addTarget(mediaRecorder.getSurface());
        builder.addTarget(surfaceHolder.getSurface());
        builder.addTarget(imageReader.getSurface());
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);

        cameraDevice.createCaptureSession(
                Arrays.asList(mediaRecorder.getSurface(), surfaceHolder.getSurface(), imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            session.setRepeatingRequest(builder.build(), null, null);
                            mediaRecorder.start();
                        } catch (CameraAccessException e) {
                            listener.onError("setRepeating record", e);
                        }
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        listener.onError("Błąd konfiguracji sesji", null);
                    }
                }, null);

        pendingStartRecording = false;
    }

    void openPreviewIfReady() {
        if (!isSurfaceReady()) return;
        if (cameraDevice == null) {
            openCameraIfNeeded();
            return;
        }
        try {
            startPreviewSession();  // patrz metoda poniżej
        } catch (CameraAccessException e) {
            listener.onError("start preview", e);
        }
    }

    private void startPreviewSession() throws CameraAccessException {
        if (!isSurfaceReady() || cameraDevice == null) return;

        // zamknij ewentualną starą sesję
        try { if (captureSession != null) captureSession.close(); } catch (Exception ignored) {}
        captureSession = null;

        CaptureRequest.Builder builder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(surfaceHolder.getSurface());
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        cameraDevice.createCaptureSession(
                java.util.Collections.singletonList(surfaceHolder.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                        captureSession = session;
                        try { session.setRepeatingRequest(builder.build(), null, null); }
                        catch (CameraAccessException e) { listener.onError("Preview setRepeating", e); }
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        listener.onError("Błąd konfiguracji podglądu", null);
                    }
                }, null
        );
    }



    private void analyzeImage(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            Image.Plane yPlane = image.getPlanes()[0];
            ByteBuffer yBuffer = yPlane.getBuffer();
            int pixelStride = yPlane.getPixelStride();
            int rowStride = yPlane.getRowStride();

            int sum = 0, count = 0;
            yBuffer.rewind();
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    int yIndex = row * rowStride + col * pixelStride;
                    if (yIndex >= yBuffer.limit()) continue;
                    int Y = yBuffer.get(yIndex) & 0xFF;
                    sum += Y; count++;
                }
            }
            double avg = sum / (double) count;
            listener.onSample(avg, System.currentTimeMillis());
        } catch (Exception e) {
            listener.onError("analyzeImage", e);
        } finally {
            image.close();
        }
    }
}
