package ru.coolsoft.p2pcamera;

import static android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE;
import static android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP;
import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON;
import static android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH;
import static android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE;
import static android.hardware.camera2.CaptureRequest.FLASH_MODE;
import static android.media.MediaFormat.MIMETYPE_VIDEO_AVC;
import static ru.coolsoft.common.Command.FORMAT;
import static ru.coolsoft.common.Constants.SIZEOF_INT;
import static ru.coolsoft.common.Constants.SIZEOF_LONG;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.PortMappingEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import ru.coolsoft.common.Command;
import ru.coolsoft.common.Flashlight;
import ru.coolsoft.p2pcamera.StreamingServer.EventListener;
import ru.coolsoft.p2pcamera.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "P2PCamera";

    private CameraManager mCameraManager = null;
    CameraService[] mCameras = null;
    private final int DEFAULT_CAMERA_IDX = 0;

    private boolean torchAvailable = true;
    private boolean torchMode = false;

    private TextureView mTextureView = null;
    private TextView mInfoText = null;
    private TextView mExternalInfoText = null;

    private MediaCodec mCodec = null;
    Surface mEncoderSurface;
    public static Surface surface = null;
    private final List<byte[]> csdBuffers = new ArrayList<>();

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler = null;

    private StreamingServer streamingServer = null;
    private final List<ClientInfo> clients = new ArrayList<>();

    private void onTorchUnavailable() {
        torchAvailable = false;
        streamingServer.notifyClients(Command.FLASHLIGHT, new byte[]{(byte) Flashlight.UNAVAILABLE.mode});
    }

    private void onTorchModeChanged(boolean enabled) {
        torchAvailable = true;
        torchMode = enabled;
        eventListener.notifyTorchMode();
    }

    private final EventListener eventListener = new EventListener() {
        @Override
        public void onGatewaysDiscovered(Map<InetAddress, GatewayDevice> gateways) {
            StringBuffer msg = new StringBuffer(MessageFormat.format("Discovered {0} gateways:", gateways.entrySet().size()));
            for (Map.Entry<InetAddress, GatewayDevice> entry : gateways.entrySet()) {
                msg.append(MessageFormat.format("\r\n@{0}: {1}", entry.getKey(), entry.getValue().getURLBase()));
            }

            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onMappingDone(InetSocketAddress info) {
            runOnUiThread(() -> mExternalInfoText.setText(getString(R.string.mapping_info,
                    info.getHostString(), info.getPort()
            )));
        }

        @Override
        public void onAlreadyMapped(PortMappingEntry entry) {
            runOnUiThread(() -> mExternalInfoText.setText(getString(R.string.already_mapped_info,
                    entry.getRemoteHost(), entry.getInternalPort(), entry.getInternalClient()
            )));
        }

        @Override
        public void onPortMappingServerError(PortMappingServer.Situation situation, @Nullable Throwable e) {
            runOnUiThread(() -> mExternalInfoText.setText(getString(R.string.mapping_error,
                    e == null ? "" : e.getClass().getSimpleName())));
        }

        private void refreshClientCounter() {
            runOnUiThread(() -> mInfoText.setText(getString(R.string.client_count, clients.size())));
        }

        @Override
        public void onClientConnected(StreamWorker worker) {
            clients.add(new ClientInfo(worker));
            refreshClientCounter();

            byte[] csdData = getCodecSpecificDataArray();
            if (csdData != null) {
                worker.notifyClient(FORMAT, csdData);
            }
        }

        @Override
        public void onClientDisconnected(StreamWorker worker) {
            clients.remove(new ClientInfo(worker));
            refreshClientCounter();
        }

        @Override
        public void onToggleFlashlight() {
            if (torchAvailable) {
                mCameras[DEFAULT_CAMERA_IDX].setFlashMode(!torchMode);
                onTorchModeChanged(!torchMode);
            }
        }

        @Override
        public void notifyTorchMode() {
            streamingServer.notifyClients(Command.FLASHLIGHT,
                    new byte[]{(byte) (torchMode ? Flashlight.ON : Flashlight.OFF).mode});
        }

        @Override
        public void onError(StreamWorker worker, StreamingServer.Situation situation, Object details) {
            switch (situation) {
                case UNKNOWN_COMMAND:
                    Toast.makeText(MainActivity.this, getString(R.string.unknown_command, (Integer) details), Toast.LENGTH_SHORT).show();
                    break;
                case MALFORMED_COMMAND:
                    Toast.makeText(MainActivity.this, getString(R.string.malformed_command, (Command) details), Toast.LENGTH_SHORT).show();
                    break;
                default:
                    Toast.makeText(MainActivity.this, R.string.unknown_error, Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Nullable
    private byte[] getCodecSpecificDataArray() {
        ByteArrayOutputStream csdData = new ByteArrayOutputStream();
        ByteBuffer len = ByteBuffer.allocate(SIZEOF_INT);
        try {
            for (byte[] csd : csdBuffers) {
                len.clear();
                len.putInt(csd.length);
                csdData.write(len.array());
                csdData.write(csd);
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "failed to prepare codec specific data (CSD)");
            return null;
        }
        return csdData.toByteArray();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mTextureView = binding.textureView;
        mInfoText = binding.infoText;
        mExternalInfoText = binding.extenalInfoText;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
                requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }

        streamingServer = new StreamingServer(eventListener);
        streamingServer.start();

        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraManager.getCameraCharacteristics(Integer.toString(DEFAULT_CAMERA_IDX)).get(FLASH_INFO_AVAILABLE)) {
                onTorchUnavailable();
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        try {
            mCameras = new CameraService[mCameraManager.getCameraIdList().length];

            for (String cameraID : mCameraManager.getCameraIdList()) {
                Log.i(LOG_TAG, "cameraID: " + cameraID);
                int id = Integer.parseInt(cameraID);
                mCameras[id] = new CameraService(cameraID);

            }
        } catch (CameraAccessException e) {
            Log.e(LOG_TAG, e.getMessage());
            e.printStackTrace();
        }

        setUpMediaCodec();
    }

    @Override
    protected void onDestroy() {
        streamingServer.stopServer();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    protected void onPause() {
        if (mCameras[DEFAULT_CAMERA_IDX].isOpen()) {
            mCameras[DEFAULT_CAMERA_IDX].closeCamera();
        }

        stopBackgroundThread();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();

        if (mCameras[DEFAULT_CAMERA_IDX] != null && !mCameras[DEFAULT_CAMERA_IDX].isOpen())
            mCameras[DEFAULT_CAMERA_IDX].openCamera();
    }

    private void setUpMediaCodec() {
        try {
            mCodec = MediaCodec.createEncoderByType(MIMETYPE_VIDEO_AVC); // H264
        } catch (Exception e) {
            Log.i(LOG_TAG, "codec missing");
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

        mCodec.setCallback(new EncoderCallback());
        mCodec.start();
        Log.i(LOG_TAG, "encoder started");
    }


    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public class CameraService {
        private final String mCameraID;
        private CameraDevice mCameraDevice = null;
        private CameraCaptureSession mSession;
        private CaptureRequest.Builder mPreviewBuilder;

        public CameraService(String cameraID) {
            mCameraID = cameraID;
        }

        private final CameraDevice.StateCallback mCameraCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                mCameraDevice = camera;
                Log.i(LOG_TAG, "Open camera  with id:" + mCameraDevice.getId());

                startCameraPreviewSession();
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                mCameraDevice.close();

                Log.i(LOG_TAG, "disconnect camera  with id:" + mCameraDevice.getId());
                mCameraDevice = null;
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                Log.i(LOG_TAG, "error! camera id:" + camera.getId() + " error:" + error);
            }
        };

        private int computeRelativeRotation(
                CameraCharacteristics characteristics,
                int surfaceRotationDegrees
        ) {
            Integer sensorOrientationDegrees =
                    characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            // Reverse device orientation for back-facing cameras.
            int sign = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT ? 1 : -1;

            // Calculate desired orientation relative to camera orientation to make
            // the image upright relative to the device orientation.
            return (sensorOrientationDegrees - surfaceRotationDegrees * sign + 360) % 360;
        }

        private Matrix computeTransformationMatrix(
                TextureView textureView,
                CameraCharacteristics characteristics,
                Size previewSize,
                int surfaceRotation
        ) {
            Matrix matrix = new Matrix();
            int surfaceRotationDegrees;
            switch (surfaceRotation) {
                case Surface.ROTATION_90:
                    surfaceRotationDegrees = 90;
                    break;
                case Surface.ROTATION_180:
                    surfaceRotationDegrees = 180;
                    break;
                case Surface.ROTATION_270:
                    surfaceRotationDegrees = 270;
                    break;
                case Surface.ROTATION_0:
                default:
                    surfaceRotationDegrees = 0;
            }

            /* Rotation required to transform from the camera sensor orientation to the
             * device's current orientation in degrees. */
            int relativeRotation = computeRelativeRotation(characteristics, surfaceRotationDegrees);

            /* Scale factor required to scale the preview to its original size on the x-axis. */
            float scaleX = (relativeRotation % 180 == 0)
                    ? (float) textureView.getWidth() / previewSize.getWidth()
                    : (float) textureView.getWidth() / previewSize.getHeight();

            /* Scale factor required to scale the preview to its original size on the y-axis. */
            float scaleY = (relativeRotation % 180 == 0)
                    ? (float) textureView.getHeight() / previewSize.getHeight()
                    : (float) textureView.getHeight() / previewSize.getWidth();

            /* Scale factor required to fit the preview to the TextureView size. */
            float finalScale = Math.min(scaleX, scaleY);

            /* The scale will be different if the buffer has been rotated. */
            if (relativeRotation % 180 == 0) {
                matrix.setScale(
                        textureView.getHeight() / (float) textureView.getWidth() / scaleY * finalScale,
                        textureView.getWidth() / (float) textureView.getHeight() / scaleX * finalScale,
                        textureView.getWidth() / 2f,
                        textureView.getHeight() / 2f
                );
            } else {
                matrix.setScale(
                        1 / scaleX * finalScale,
                        1 / scaleY * finalScale,
                        textureView.getWidth() / 2f,
                        textureView.getHeight() / 2f
                );
            }

            // Rotate the TextureView to compensate for the Surface's rotation.
            matrix.postRotate(
                    (float) -surfaceRotationDegrees,
                    textureView.getWidth() / 2f,
                    textureView.getHeight() / 2f
            );

            return matrix;
        }

        private void setFlashMode(boolean enabled) {
            mPreviewBuilder.set(CONTROL_AE_MODE, CONTROL_AE_MODE_ON);
            mPreviewBuilder.set(FLASH_MODE, enabled ? FLASH_MODE_TORCH : FLASH_MODE_OFF);
            setRepeatingRequest();
        }

        private void configureTexture() {
            CameraCharacteristics cameraCharacteristics;
            if (mCameraDevice == null) {
                //camera was closed before we managed to get here
                return;
            }
            try {
                cameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraID);
            } catch (CameraAccessException e) {
                e.printStackTrace();
                return;
            }
            StreamConfigurationMap previewConfig = cameraCharacteristics.get(SCALER_STREAM_CONFIGURATION_MAP);

            int[] formats = previewConfig.getOutputFormats();
            Size previewSize = previewConfig.getOutputSizes(formats[0])[0];

            final int rotation = getWindowManager().getDefaultDisplay().getRotation();
            Log.w(LOG_TAG, "Current Rotation: " + rotation);
            Matrix m = computeTransformationMatrix(mTextureView, cameraCharacteristics, previewSize, rotation);
            runOnUiThread(() -> mTextureView.setTransform(m));

            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            surface = new Surface(texture);

            try {
                mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mPreviewBuilder.addTarget(surface);
                mPreviewBuilder.addTarget(mEncoderSurface);

                mCameraDevice.createCaptureSession(Arrays.asList(surface, mEncoderSurface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                mSession = session;
                                setRepeatingRequest();
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                            }
                        }, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        private void setRepeatingRequest() {
            try {
                mSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        private void startCameraPreviewSession() {
            if (mTextureView.isAvailable()) {
                configureTexture();
            } else {
                mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                        configureTexture();
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {

                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                        return false;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

                    }
                });
            }
        }

        public boolean isOpen() {
            return mCameraDevice != null;
        }

        public void openCamera() {
            try {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    mCameraManager.openCamera(mCameraID, mCameraCallback, mBackgroundHandler);
                }
            } catch (CameraAccessException e) {
                Log.i(LOG_TAG, e.getMessage());
            }
        }

        public void closeCamera() {
            Log.i(LOG_TAG, "Closing camera");
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }

        //ToDo: call it on last client disconnection
        public void stopStreamingVideo() {
            if (mCameraDevice != null & mCodec != null) {
                try {
                    mSession.stopRepeating();
                    mSession.abortCaptures();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }

                mCodec.stop();
                mCodec.release();
                mEncoderSurface.release();
                closeCamera();
            }
        }
    }

    private class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {

        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            ByteBuffer outByteBuffer = codec.getOutputBuffer(index);
            long now = System.currentTimeMillis();
            byte[] outData = new byte[SIZEOF_LONG + info.size];
            ByteBuffer outDataBuffer = ByteBuffer.wrap(outData);

            outDataBuffer.putLong(now);
            outDataBuffer.put(outByteBuffer);

            streamingServer.streamToClients(outData);

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

            byte[] csdData = getCodecSpecificDataArray();
            if (csdData != null) {
                streamingServer.notifyClients(FORMAT, csdData);
            }
        }
    }

    private static class ClientInfo {
        private final InetAddress address;
        private final long connectedTimestamp;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ClientInfo that = (ClientInfo) o;
            return address.equals(that.address);
        }

        @Override
        public int hashCode() {
            return address.hashCode();
        }

        private ClientInfo(StreamWorker worker) {
            address = worker.getSocket().getInetAddress();
            connectedTimestamp = System.currentTimeMillis();
        }
    }
}