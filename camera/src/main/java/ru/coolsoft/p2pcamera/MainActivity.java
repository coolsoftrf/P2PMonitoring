package ru.coolsoft.p2pcamera;

import static android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE;
import static android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP;
import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON;
import static android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH;
import static android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE;
import static android.hardware.camera2.CaptureRequest.FLASH_MODE;
import static android.media.MediaFormat.MIMETYPE_VIDEO_AVC;
import static ru.coolsoft.common.Command.AVAILABILITY;
import static ru.coolsoft.common.Command.FORMAT;
import static ru.coolsoft.common.Constants.CAMERA_AVAILABLE;
import static ru.coolsoft.common.Constants.CAMERA_UNAVAILABLE;
import static ru.coolsoft.common.Constants.SIZEOF_INT;
import static ru.coolsoft.common.Constants.SIZEOF_LONG;
import static ru.coolsoft.p2pcamera.StreamingServer.Situation.UNKNOWN_COMMAND;

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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.RouteInfo;
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

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.PortMappingEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.coolsoft.common.Command;
import ru.coolsoft.common.Flashlight;
import ru.coolsoft.p2pcamera.StreamingServer.EventListener;
import ru.coolsoft.p2pcamera.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "P2PCamera";
    public static final int REQUEST_CODE_CAMERA = 1;

    private CameraManager mCameraManager = null;
    private Map<String, CameraService> mCameras = null;
    private final String DEFAULT_CAMERA_ID = "0";

    private boolean torchAvailable = true;
    private boolean torchMode = false;

    private TextureView mTextureView = null;
    private TextView mBindingInfoText = null;
    private TextView mExternalInfoText = null;
    private TextView mInfoText = null;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler = null;

    private StreamingServer streamingServer = null;
    private final List<ClientInfo> clients = new ArrayList<>();

    private void onTorchUnavailable() {
        torchAvailable = false;
        eventListener.notifyTorchMode();
    }

    private void onTorchModeChanged(boolean enabled) {
        torchAvailable = true;
        torchMode = enabled;
        eventListener.notifyTorchMode();
    }

    private final EventListener eventListener = new EventListener() {
        @Override
        public void onGatewaysDiscovered(Map<InetAddress, GatewayDevice> gateways) {
            StringBuilder msg = new StringBuilder(getString(R.string.gateways_discovered, gateways.entrySet().size()));
            for (Map.Entry<InetAddress, GatewayDevice> entry : gateways.entrySet()) {
                msg.append(MessageFormat.format("\r\n@{0}: {1}", entry.getKey(), entry.getValue().getURLBase()));
            }
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
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
            String format = e == null ? "{0}" : "{0}: {1}";
            runOnUiThread(() -> mExternalInfoText.setText(getString(R.string.mapping_error, MessageFormat.format(format,
                    situation.toString(), e == null ? "" : e.getClass().getSimpleName()))));
        }

        private void refreshClientCounter() {
            runOnUiThread(() -> mInfoText.setText(getString(R.string.client_count, clients.size())));
        }

        @Override
        public void onClientConnected(StreamWorker worker) {
            clients.add(new ClientInfo(worker));
            ifCameraInitialized(DEFAULT_CAMERA_ID, CameraService::setUpMediaCodec);
            refreshClientCounter();

            byte[] csdData = getCodecSpecificDataArray(DEFAULT_CAMERA_ID);
            if (csdData != null && csdData.length > 0) {
                worker.notifyClient(FORMAT, csdData);
            }
        }

        @Override
        public void onClientDisconnected(StreamWorker worker) {
            clients.remove(new ClientInfo(worker));
            ifCameraInitialized(DEFAULT_CAMERA_ID, camera -> {
                if (clients.isEmpty()) {
                    camera.stopMediaStreaming();
                }
            });
            refreshClientCounter();
        }

        @Override
        public void onToggleFlashlight() {
            if (torchAvailable) {
                ifCameraInitialized(DEFAULT_CAMERA_ID, camera -> {
                    camera.setFlashMode(!torchMode);
                    onTorchModeChanged(!torchMode);
                });
            }
        }

        @Override
        public void notifyTorchMode() {
            streamingServer.notifyClients(Command.FLASHLIGHT,
                    new byte[]{(byte) (torchAvailable
                            ? torchMode ? Flashlight.ON : Flashlight.OFF
                            : Flashlight.UNAVAILABLE).mode});
        }

        @Override
        public void notifyAvailability() {
            ByteBuffer buf = getCameraIdByteBuffer(DEFAULT_CAMERA_ID);
            buf.put(ifCameraInitialized(DEFAULT_CAMERA_ID, CameraService::isOpen, false)
                    ? CAMERA_AVAILABLE
                    : CAMERA_UNAVAILABLE);
            streamingServer.notifyClients(AVAILABILITY, buf.array());
        }

        @Override
        public void onError(StreamWorker worker, StreamingServer.Situation situation, Object details) {
            if (situation == UNKNOWN_COMMAND) {
                //noinspection RedundantCast
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        getString(R.string.unknown_command, (Integer) details), Toast.LENGTH_SHORT).show()
                );
            } else {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        R.string.unknown_error, Toast.LENGTH_SHORT).show()
                );
            }
        }
    };

    @NonNull
    private static ByteBuffer getCameraIdByteBuffer(String cameraId) {
        byte[] cameraIdBytes = cameraId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(SIZEOF_INT + cameraIdBytes.length + 1);
        buf.putInt(cameraIdBytes.length);
        buf.put(cameraIdBytes);
        return buf;
    }

    @Nullable
    private byte[] getCodecSpecificDataArray(String cameraId) {
        ByteArrayOutputStream csdData = new ByteArrayOutputStream();
        ByteBuffer len = ByteBuffer.allocate(SIZEOF_INT);

        try {
            final List<byte[]> emptyByteArrayList = Collections.emptyList();
            for (byte[] csd : ifCameraInitialized(cameraId, CameraService::getCsdBuffers, emptyByteArrayList)) {
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
        mExternalInfoText = binding.externalInfoText;
        mBindingInfoText = binding.localBinding;

        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                ifCameraInitialized(DEFAULT_CAMERA_ID,
                        camera -> camera.configureTextures(mTextureView.getSurfaceTexture()));
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                Log.d(LOG_TAG, "texture destroyed");
                ifCameraInitialized(DEFAULT_CAMERA_ID, camera -> {
                    if (camera.isOpen()) {
                        camera.pendingSurfaces.remove((Surface) mTextureView.getTag(R.id.TAG_KEY_SURFACE));
                        camera.configureTextures(null);
                    }
                });

                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });

        startBackgroundThread();
        setupServer();
        setupCamera();
    }

    @Override
    protected void onDestroy() {
        streamingServer.stopServer();

        ifCameraInitialized(DEFAULT_CAMERA_ID, camera -> {
            if (camera.isOpen()) {
                camera.closeCamera();
            }
        });
        stopBackgroundThread();

        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    private <T> T ifCameraInitialized(String cameraId, CameraFunction<T> function, T otherwise) {
        CameraService camera;
        return mCameras != null && (camera = mCameras.get(cameraId)) != null
                ? function.call(camera)
                : otherwise;
    }

    private interface CameraFunction<T> {
        T call(CameraService camera);
    }

    private void ifCameraInitialized(String cameraId, CameraConsumer consumer) {
        ifCameraInitialized(cameraId, camera -> {
            consumer.consume(camera);
            return null;
        }, null);
    }

    private interface CameraConsumer {
        void consume(CameraService camera);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_CODE_CAMERA || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        initCamera();
    }

    private void initCamera() {
        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraManager.getCameraCharacteristics(DEFAULT_CAMERA_ID).get(FLASH_INFO_AVAILABLE)) {
                onTorchUnavailable();
            }
        } catch (CameraAccessException e) {
            Log.e(LOG_TAG, "failed to obtain characteristics during camera initialization", e);
        }

        try {
            final String[] cameras = mCameraManager.getCameraIdList();
            mCameras = new HashMap<>(cameras.length);

            for (String cameraID : cameras) {
                Log.i(LOG_TAG, "cameraID: " + cameraID);
                mCameras.put(cameraID, new CameraService(cameraID));
            }
        } catch (CameraAccessException e) {
            Log.e(LOG_TAG, "Camera initialization error", e);
        }
    }

    private void setupServer() {
        streamingServer = new StreamingServer(eventListener);
        streamingServer.start();

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network[] nets = cm.getAllNetworks();

        for (Network net : nets) {
            for (RouteInfo route : cm.getLinkProperties(net).getRoutes()) {
                if (!route.getGateway().isAnyLocalAddress()) {
                    NetworkInterface anInterface;
                    try {
                        anInterface = NetworkInterface.getByName(route.getInterface());
                    } catch (SocketException e) {
                        return;
                    }
                    Enumeration<InetAddress> interfaceAddresses = anInterface.getInetAddresses();
                    while (interfaceAddresses.hasMoreElements()) {
                        InetAddress inetAddress = interfaceAddresses.nextElement();
                        if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                            mBindingInfoText.setText(getString(R.string.binding_info, inetAddress));
                        }
                    }
                    return;
                }
            }
        }
    }

    private void setupCamera() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CODE_CAMERA);
        } else {
            initCamera();
        }
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
            Log.e(LOG_TAG, "background thread interrupted", e);
        }
    }

    public class CameraService {
        private final String mCameraID;
        private volatile CameraDevice mCameraDevice = null;
        private CameraCaptureSession mSession;
        private CaptureRequest.Builder mPreviewBuilder;

        private MediaCodec mCodec = null;
        private Surface mEncoderSurface;
        public final Set<Surface> pendingSurfaces = new HashSet<>();
        private final List<byte[]> csdBuffers = new ArrayList<>();

        public CameraService(String cameraID) {
            mCameraID = cameraID;
        }

        public List<byte[]> getCsdBuffers() {
            return csdBuffers;
        }

        private final CameraDevice.StateCallback mCameraCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                mCameraDevice = camera;
                Log.i(LOG_TAG, "Open camera  with id:" + mCameraDevice.getId());
                streamingServer.notifyClients(AVAILABILITY,
                        getCameraIdByteBuffer(DEFAULT_CAMERA_ID).put(CAMERA_AVAILABLE).array());
                updatePreviewSession();
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                Log.i(LOG_TAG, "disconnect camera  with id:" + mCameraDevice.getId());
                closeCamera();
            }

            @Override
            public void onClosed(@NonNull CameraDevice camera) {
                streamingServer.notifyClients(AVAILABILITY,
                        getCameraIdByteBuffer(DEFAULT_CAMERA_ID).put(CAMERA_UNAVAILABLE).array());
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

        private synchronized void configureTextures(SurfaceTexture texture) {
            if (texture != null) {
                CameraCharacteristics cameraCharacteristics;
                try {
                    cameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraID);
                } catch (CameraAccessException e) {
                    Log.e(LOG_TAG, "failed to obtain characteristics during texture configuration", e);
                    return;
                }
                Log.d(LOG_TAG, "texture initialization started");
                StreamConfigurationMap previewConfig = cameraCharacteristics.get(SCALER_STREAM_CONFIGURATION_MAP);

                int[] formats = previewConfig.getOutputFormats();
                Size previewSize = previewConfig.getOutputSizes(formats[0])[0];

                final int rotation = getWindowManager().getDefaultDisplay().getRotation();
                Log.d(LOG_TAG, "Current Rotation: " + rotation);
                Matrix m = computeTransformationMatrix(mTextureView, cameraCharacteristics, previewSize, rotation);
                runOnUiThread(() -> mTextureView.setTransform(m));

                texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                Surface surface = new Surface(texture);
                mTextureView.setTag(R.id.TAG_KEY_SURFACE, surface);
                pendingSurfaces.add(surface);
            }
            updatePreviewSession();
        }

        private void updatePreviewSession() {
            if (mSession != null) {
                try {
                    mSession.stopRepeating();
                } catch (CameraAccessException e) {
                    Log.e(LOG_TAG, "Error stopping capture session", e);
                }
                mSession = null;
                //restart the session within mSession.StateCallback.onReady
                return;
            }
            if (pendingSurfaces.size() == 0) {
                return;
            }

            try {
                if (!ifCameraInitialized(DEFAULT_CAMERA_ID, camera -> {
                    if (!camera.isOpen()) {
                        camera.openCamera();

                        //restart the session within camera:onOpened
                        return false;
                    }
                    return true;
                }, false)) {
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
                            public void onReady(@NonNull CameraCaptureSession session) {
                                if (mSession == null) {
                                    // requests are being stopped to recreate a session with pending surfaces
                                    updatePreviewSession();
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                            }
                        }, mBackgroundHandler);
            } catch (CameraAccessException e) {
                Log.e(LOG_TAG, "failed to create preview", e);
            }
        }

        private void setRepeatingRequest() {
            try {
                mSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
            } catch (CameraAccessException e) {
                Log.e(LOG_TAG, "failed to start preview", e);
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

        private void setUpMediaCodec() {
            //ToDo: add race condition handling
            if (mCodec != null) {
                return;
            }

            Log.i(LOG_TAG, "starting encoder");
            try {
                mCodec = MediaCodec.createEncoderByType(MIMETYPE_VIDEO_AVC); // H264
            } catch (Exception e) {
                Log.w(LOG_TAG, "codec missing");
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

            pendingSurfaces.add(mEncoderSurface);
            configureTextures(null);
            Log.i(LOG_TAG, "encoder started");
        }

        //ToDo: call it on last client disconnection
        public void stopMediaStreaming() {
            if (mCodec != null) {
                Log.i(LOG_TAG, "stopping encoder");

                ifCameraInitialized(DEFAULT_CAMERA_ID, camera -> {
                    camera.pendingSurfaces.remove(mEncoderSurface);
                    camera.configureTextures(null);
                });
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

                byte[] csdData = getCodecSpecificDataArray(mCameraID);
                if (csdData != null) {
                    streamingServer.notifyClients(FORMAT, csdData);
                }
            }
        };
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