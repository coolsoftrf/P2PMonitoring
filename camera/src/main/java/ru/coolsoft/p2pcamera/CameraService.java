package ru.coolsoft.p2pcamera;

import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON;
import static android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH;
import static android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE;
import static android.hardware.camera2.CaptureRequest.FLASH_MODE;
import static android.media.MediaFormat.MIMETYPE_VIDEO_AVC;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CameraService {
    private static final String LOG_TAG = CameraService.class.getSimpleName();

    public final String mCameraID;
    private final Handler mHandler;
    private final CameraServiceListener listener;

    public final Set<Surface> pendingSurfaces = new HashSet<>();
    private final List<byte[]> csdBuffers = new ArrayList<>();

    private volatile CameraDevice mCameraDevice = null;
    private CameraCaptureSession mSession;
    private CaptureRequest.Builder mPreviewBuilder;

    private MediaCodec mCodec = null;
    private Surface mEncoderSurface;

    public CameraService(String cameraID, Handler handler, CameraServiceListener cameraListener) {
        mCameraID = cameraID;
        mHandler = handler;
        listener = cameraListener;
    }

    public List<byte[]> getCsdBuffers() {
        return Collections.unmodifiableList(csdBuffers);
    }

    private final CameraDevice.StateCallback mCameraCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            Log.i(LOG_TAG, "Opened camera with id:" + mCameraDevice.getId());
            listener.onCameraOpened(mCameraID);
            updatePreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.i(LOG_TAG, "disconnected camera  with id:" + mCameraDevice.getId());
            closeCamera();
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            listener.onCameraClosed(mCameraID);
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.w(LOG_TAG, String.format("error! camera id:%s error:%d", camera.getId(), error));
            closeCamera();
        }
    };

    public void setFlashMode(boolean enabled) {
        mPreviewBuilder.set(CONTROL_AE_MODE, CONTROL_AE_MODE_ON);
        mPreviewBuilder.set(FLASH_MODE, enabled ? FLASH_MODE_TORCH : FLASH_MODE_OFF);
        setRepeatingRequest();
    }

    public void addSurface(Surface surface) {
        pendingSurfaces.add(surface);
        updatePreviewSession();
    }

    public void removeSurface(Surface surface) {
        pendingSurfaces.remove(surface);
        updatePreviewSession();
    }

    private void updatePreviewSession() {
        if (mSession != null) {
            try {
                mSession.stopRepeating();
            } catch (CameraAccessException | IllegalStateException e) {
                Log.w(LOG_TAG, "Error stopping capture session", e);
            } finally {
                mSession = null;
            }
        }
        if (pendingSurfaces.size() == 0) {
            return;
        }

        try {
            if (!isOpen()) {
                openCamera();

                //restart the session within camera:onOpened
                return;
            }

            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            final List<Surface> surfaces = new ArrayList<>(pendingSurfaces.size());
            for (Surface surface : pendingSurfaces) {
                mPreviewBuilder.addTarget(surface);
                surfaces.add(surface);
            }

            mCameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (isOpen()) {
                                synchronized (CameraService.this) {
                                    if (isOpen()) {
                                        mSession = session;
                                        setRepeatingRequest();
                                    }
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(LOG_TAG, "onConfigureFailed");
                        }
                    }, mHandler);
        } catch (CameraAccessException e) {
            Log.e(LOG_TAG, "failed to create preview", e);
        }
    }

    private void setRepeatingRequest() {
        try {
            mSession.setRepeatingRequest(mPreviewBuilder.build(), null, mHandler);
        } catch (CameraAccessException e) {
            Log.e(LOG_TAG, "failed to start preview", e);
        }
    }

    public boolean isOpen() {
        return mCameraDevice != null;
    }

    public void openCamera() {
        try {
            listener.openCamera(mCameraID, mCameraCallback);
        } catch (CameraAccessException e) {
            Log.i(LOG_TAG, "Error opening camera", e);
        }
    }

    public synchronized void closeCamera() {
        Log.i(LOG_TAG, "Closing camera");
        if (isOpen()) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    public void setUpMediaCodec() {
        //ToDo: add race condition handling
        if (mCodec != null) {
            return;
        }

        Log.i(LOG_TAG, "starting encoder");
        try {
            mCodec = MediaCodec.createEncoderByType(MIMETYPE_VIDEO_AVC); // H264
        } catch (Exception e) {
            Log.w(LOG_TAG, "codec missing", e);
            return;
        }

        int width = 320;
        int height = 240;
        int colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
        int videoBitrate = 500000;
        int videoFramePerSecond = 20;
        int iframeInterval = 3;

        MediaFormat format = MediaFormat.createVideoFormat(MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFramePerSecond);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeInterval);

        mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoderSurface = mCodec.createInputSurface();
        mCodec.setCallback(mEncoderCallback);
        mCodec.start();

        addSurface(mEncoderSurface);
        Log.i(LOG_TAG, "encoder started");
    }

    public synchronized void stopMediaStreaming() {
        if (mCodec != null) {
            Log.i(LOG_TAG, "stopping encoder");

            removeSurface(mEncoderSurface);

            mCodec.stop();
            mCodec.release();
            mCodec = null;
            mEncoderSurface.release();
            Log.i(LOG_TAG, "encoder stopped");
        }
    }

    private final MediaCodec.Callback mEncoderCallback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {

        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            ByteBuffer outByteBuffer = codec.getOutputBuffer(index);
            listener.onEncodedBufferAvailable(info, outByteBuffer);
            codec.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.i(LOG_TAG, "Error: " + e);
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            Log.i(LOG_TAG, "encoder output format changed: " + format);
            csdBuffers.clear();
            for (String csdKey : new String[]{"csd-0", "csd-1", "csd-2"}) {
                ByteBuffer buffer = format.getByteBuffer(csdKey);
                if (buffer == null || !buffer.hasRemaining()) {
                    break;
                }
                csdBuffers.add(buffer.array());
            }

            listener.onOutputFormatChanged(csdBuffers);
        }
    };

    public interface CameraServiceListener {
        void onCameraOpened(String cameraId);

        void onCameraClosed(String cameraId);

        void openCamera(String cameraId, CameraDevice.StateCallback cameraCallback) throws CameraAccessException;

        void onEncodedBufferAvailable(MediaCodec.BufferInfo info, ByteBuffer outByteBuffer);

        void onOutputFormatChanged(List<byte[]> csdBuffers);
    }
}
