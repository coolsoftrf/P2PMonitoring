package ru.coolsoft.p2pcamera;

import static android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE;
import static android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP;
import static ru.coolsoft.common.Command.AVAILABILITY;
import static ru.coolsoft.common.Command.FORMAT;
import static ru.coolsoft.common.Constants.AUTH_DENIED_NOT_ALLOWED;
import static ru.coolsoft.common.Constants.AUTH_DENIED_SERVER_ERROR;
import static ru.coolsoft.common.Constants.AUTH_DENIED_WRONG_CREDENTIALS;
import static ru.coolsoft.common.Constants.CAMERA_AVAILABLE;
import static ru.coolsoft.common.Constants.CAMERA_UNAVAILABLE;
import static ru.coolsoft.common.Constants.SIZEOF_INT;
import static ru.coolsoft.common.Constants.SIZEOF_LONG;
import static ru.coolsoft.p2pcamera.AuthorizationDialogFragment.ADDRESS_KEY;
import static ru.coolsoft.p2pcamera.AuthorizationDialogFragment.RESULT_KEY;
import static ru.coolsoft.p2pcamera.AuthorizationDialogFragment.USER_KEY;
import static ru.coolsoft.p2pcamera.StreamingServer.Situation.UNKNOWN_COMMAND;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
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
import androidx.fragment.app.FragmentResultListener;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.PortMappingEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ru.coolsoft.common.Command;
import ru.coolsoft.common.Flashlight;
import ru.coolsoft.p2pcamera.StreamingServer.EventListener;
import ru.coolsoft.p2pcamera.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "P2PCamera";
    private static final int REQUEST_CODE_CAMERA = 1;

    private final String DEFAULT_CAMERA_ID = "0";
    //ToDo: move clients
    // - to service layer
    private final List<ClientInfo> clients = new ArrayList<>();

    private CameraManager mCameraManager = null;
    private Map<String, CameraService> mCameras = null;
    private boolean torchAvailable = true;
    private boolean torchMode = false;

    private TextureView mTextureView = null;
    private TextView mBindingInfoText = null;
    private TextView mExternalInfoText = null;
    private TextView mInfoText = null;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler = null;
    private StreamingServer streamingServer = null;

    @NonNull
    private static ByteBuffer getCameraIdByteBuffer(String cameraId) {
        byte[] cameraIdBytes = cameraId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(SIZEOF_INT + cameraIdBytes.length + 1);
        buf.putInt(cameraIdBytes.length);
        buf.put(cameraIdBytes);
        return buf;
    }

    private static int computeRelativeRotation(
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

    private static Matrix computeTransformationMatrix(
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

    @Nullable
    private static byte[] getCodecSpecificDataArray(List<byte[]> csdBuffers) {
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

    private final FragmentResultListener fragmentResultListener = (requestKey, result) -> {
        if (!requestKey.equals(AuthorizationDialogFragment.RESULT_TAG)) {
            return;
        }

        String user = result.getString(USER_KEY);
        AuthorizationDialogFragment.Decision decision = (AuthorizationDialogFragment.Decision) result.getSerializable(RESULT_KEY);
        Log.d(LOG_TAG, String.format("Authorization decision for user '%s' is '%s'", user, decision.toString()));

        InetSocketAddress socketInfo = (InetSocketAddress) result.getSerializable(ADDRESS_KEY);
        int clientIndex = clients.indexOf(new ClientInfo(socketInfo));
        if (clientIndex == -1) {
            Log.e(LOG_TAG, String.format("Failed to find stream worker for client %s", socketInfo));
            return;
        }
        StreamWorker worker = clients.get(clientIndex).streamWorker;

        SecurityManager sm = SecurityManager.getInstance(MainActivity.this);
        switch (decision) {
            case ALLOW_ALWAYS:
                sm.setUserAccess(user, SecurityManager.UserAccess.GRANTED);
            case ALLOW:
                worker.onAuthorized();
                break;
            case DENY_ALWAYS:
                sm.setUserAccess(user, SecurityManager.UserAccess.DENIED);
            case DENY:
                worker.onAuthorizationFailed(AUTH_DENIED_NOT_ALLOWED);
                break;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mTextureView = binding.textureView;
        mInfoText = binding.infoText;
        mExternalInfoText = binding.externalInfoText;
        mBindingInfoText = binding.localBinding;

        mTextureView.setSurfaceTextureListener(surfaceTextureListener);

        getSupportFragmentManager().setFragmentResultListener(
                AuthorizationDialogFragment.RESULT_TAG, this, fragmentResultListener);

        startBackgroundThread();
        setupServer();
        setupCamera();
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
            ifCameraInitialized(DEFAULT_CAMERA_ID, camera -> {
                CameraCharacteristics cameraCharacteristics;
                try {
                    cameraCharacteristics = mCameraManager.getCameraCharacteristics(camera.mCameraID);
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

                camera.addSurface(surface);
            });
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            Log.d(LOG_TAG, "texture destroyed");
            ifCameraInitialized(DEFAULT_CAMERA_ID, camera -> {
                if (camera.isOpen()) {
                    camera.removeSurface((Surface) mTextureView.getTag(R.id.TAG_KEY_SURFACE));
                }
            });

            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        ifCameraInitialized(DEFAULT_CAMERA_ID, camera -> {
            if (!camera.isOpen() && mTextureView.isAvailable()) {
                //camera got disabled while running in background - restart session
                camera.openCamera();
            }
        });
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_CODE_CAMERA || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        initCamera();
    }

    private CameraService isCameraInitialized(String cameraId) {
        return mCameras != null ? mCameras.get(cameraId) : null;
    }

    private <T> T ifCameraInitialized(String cameraId, CameraFunction<T> function, T otherwise) {
        CameraService camera;
        return (camera = isCameraInitialized(cameraId)) != null
                ? function.call(camera)
                : otherwise;
    }

    private void ifCameraInitialized(String cameraId, CameraConsumer consumer) {
        CameraService camera;
        if ((camera = isCameraInitialized(cameraId)) != null) {
            consumer.consume(camera);
        }
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
                mCameras.put(cameraID,
                        new CameraService(cameraID, mBackgroundHandler, cameraServiceListener));
            }
        } catch (CameraAccessException e) {
            Log.e(LOG_TAG, "Camera initialization error", e);
        }
    }

    private final CameraService.CameraServiceListener cameraServiceListener = new CameraService.CameraServiceListener() {
        @Override
        public void onCameraOpened(String cameraId) {
            streamingServer.notifyClients(AVAILABILITY,
                    getCameraIdByteBuffer(cameraId).put(CAMERA_AVAILABLE).array());
        }

        @Override
        public void onCameraClosed(String cameraId) {
            streamingServer.notifyClients(AVAILABILITY,
                    getCameraIdByteBuffer(cameraId).put(CAMERA_UNAVAILABLE).array());
        }

        @Override
        public void openCamera(String cameraId, CameraDevice.StateCallback cameraCallback) throws CameraAccessException {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                mCameraManager.openCamera(cameraId, cameraCallback, mBackgroundHandler);
            }
        }

        @Override
        public void onEncodedBufferAvailable(MediaCodec.BufferInfo info, ByteBuffer outByteBuffer) {
            long now = System.currentTimeMillis();
            byte[] outData = new byte[SIZEOF_LONG + info.size];
            ByteBuffer outDataBuffer = ByteBuffer.wrap(outData);

            outDataBuffer.putLong(now);
            outDataBuffer.put(outByteBuffer);

            streamingServer.streamToClients(outData);
        }

        @Override
        public void onOutputFormatChanged(List<byte[]> csdBuffers) {
            byte[] csdData = getCodecSpecificDataArray(csdBuffers);
            if (csdData != null) {
                streamingServer.notifyClients(FORMAT, csdData);
            }
        }
    };

    private void onTorchUnavailable() {
        torchAvailable = false;
        eventListener.notifyTorchMode();
    }

    private void onTorchModeChanged(boolean enabled) {
        torchAvailable = true;
        torchMode = enabled;
        eventListener.notifyTorchMode();
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
        public void onUser(StreamWorker worker, String user) {
            ClientInfo clientInfo = new ClientInfo(worker);
            int clientIndex = clients.indexOf(clientInfo);
            if (clientIndex == -1) {
                Log.w(LOG_TAG, String.format("Failed to find client for worker %s. Adding new one.",
                        worker.getSocket().getRemoteSocketAddress()));
                clients.add(clientInfo);
            }
            clients.get(clientIndex).setUserName(user);

            SecurityManager sm = SecurityManager.getInstance(MainActivity.this);
            switch (sm.getUserAccess(user)) {
                case GRANTED:
                    worker.onAuthorized();
                    break;
                case DENIED:
                    worker.onAuthorizationFailed(AUTH_DENIED_NOT_ALLOWED);
                    break;
                default:
                    Socket socket = worker.getSocket();
                    new AuthorizationDialogFragment(user, new InetSocketAddress(socket.getInetAddress(), socket.getPort()))
                            .show(getSupportFragmentManager(), AuthorizationDialogFragment.TAG_PREFIX + user);
            }
        }

        @Override
        public void onShadow(StreamWorker worker, String shadow) {
            ClientInfo clientInfo = new ClientInfo(worker);
            int clientIndex = clients.indexOf(clientInfo);
            if (clientIndex == -1) {
                Log.e(LOG_TAG, String.format("Failed to find client for worker %s.",
                        worker.getSocket().getRemoteSocketAddress()));
                worker.onAuthorizationFailed(AUTH_DENIED_SERVER_ERROR);
                return;
            }

            SecurityManager sm = SecurityManager.getInstance(MainActivity.this);
            String user = clients.get(clientIndex).getUserName();
            String shadowPref = sm.getUserShadow(user);
            if (shadowPref == null) {
                Log.d(LOG_TAG, String.format("Shadow initialized for user '%s':%s", user, shadow));
                sm.setUserShadow(user, shadow);
            } else if (!shadowPref.equals(shadow)) {
                Log.d(LOG_TAG, String.format("Access denied for user '%s':%s (against %s)", user, shadow, shadowPref));
                worker.onAuthorizationFailed(AUTH_DENIED_WRONG_CREDENTIALS);
                return;
            }
            worker.onAuthorized();

            ifCameraInitialized(DEFAULT_CAMERA_ID, CameraService::setUpMediaCodec);

            byte[] csdData = ifCameraInitialized(DEFAULT_CAMERA_ID,
                    camera -> getCodecSpecificDataArray(camera.getCsdBuffers()), null);
            if (csdData != null && csdData.length > 0) {
                worker.notifyClient(FORMAT, csdData);
            }
        }

        @Override
        public void onClientConnected(StreamWorker worker) {
            clients.add(new ClientInfo(worker));
            refreshClientCounter();
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

    private interface CameraFunction<T> {
        T call(CameraService camera);
    }

    private interface CameraConsumer {
        void consume(CameraService camera);
    }

    private static class ClientInfo {
        private final InetSocketAddress address;
        @Nullable
        public final Long connectedTimestamp;
        public final StreamWorker streamWorker;

        private String userName;

        private ClientInfo(StreamWorker worker) {
            address = new InetSocketAddress(worker.getSocket().getInetAddress(), worker.getSocket().getPort());
            connectedTimestamp = System.currentTimeMillis();
            streamWorker = worker;
        }

        private ClientInfo(InetSocketAddress socketInfo) {
            address = socketInfo;
            connectedTimestamp = null;
            streamWorker = null;
        }

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

        @Nullable
        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }
    }
}