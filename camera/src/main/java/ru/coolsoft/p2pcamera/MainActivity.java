package ru.coolsoft.p2pcamera;

import static android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE;
import static android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP;
import static android.util.Base64.DEFAULT;
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
import android.annotation.SuppressLint;
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
import android.util.Base64;
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
import java.util.Arrays;
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
            int displayRotationDegrees
    ) {
        Integer sensorOrientationDegrees =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

        // Reverse device orientation for back-facing cameras.
        int sign = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT ? 1 : -1;

        // Calculate desired orientation relative to camera orientation to make
        // the image upright relative to the device orientation.
        return (sensorOrientationDegrees - displayRotationDegrees * sign + 360) % 360;
    }

    private static Matrix computeTransformationMatrix(
            TextureView textureView,
            CameraCharacteristics characteristics,
            Size previewSize,
            int displayRotation,
            Matrix textureTransformMatrix) {
        int displayRotationDegrees;
        switch (displayRotation) {
            case Surface.ROTATION_90:
                displayRotationDegrees = 90;
                break;
            case Surface.ROTATION_180:
                displayRotationDegrees = 180;
                break;
            case Surface.ROTATION_270:
                displayRotationDegrees = 270;
                break;
            case Surface.ROTATION_0:
            default:
                displayRotationDegrees = 0;
        }

        /* Rotation required to transform from the camera sensor orientation to the
         * device's current orientation in degrees. */
        int relativeRotation = computeRelativeRotation(characteristics, displayRotationDegrees);

        // Scale factor required to scale the preview to its original size on the x-axis
        float scaleX = (relativeRotation % 180 == 0)
                ? (float) textureView.getWidth() / previewSize.getWidth()
                : (float) textureView.getWidth() / previewSize.getHeight();
        // Scale factor required to scale the preview to its original size on the y-axis
        float scaleY = (relativeRotation % 180 == 0)
                ? (float) textureView.getHeight() / previewSize.getHeight()
                : (float) textureView.getHeight() / previewSize.getWidth();
        // Scale factor required to fit the preview to the TextureView size
        float finalScale = Math.min(scaleX, scaleY);

        Matrix matrix = new Matrix();
        //normalize texture
        matrix.postScale(1f / textureView.getWidth(), 1f / textureView.getHeight());
        //compensate matrix changes
        matrix.postConcat(textureTransformMatrix);
        //compensate surface rotation
        matrix.postRotate((float) -displayRotationDegrees, 0.5f, 0.5f);
        //adjust scales
        if (relativeRotation % 180 == 0) {
            matrix.postScale(previewSize.getWidth() * finalScale, previewSize.getHeight() * finalScale);
        } else {
            matrix.postScale(previewSize.getHeight() * finalScale, previewSize.getWidth() * finalScale);
        }

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
        final Matrix startupMtx = new Matrix();
        final Matrix prevMtx = new Matrix();
        final Matrix transformMtx = new Matrix();

        CameraCharacteristics cameraCharacteristics;
        Size previewSize;
        int rotation;

        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
            Log.d(LOG_TAG, "texture available");
            ifCameraInitialized(DEFAULT_CAMERA_ID, camera -> {
                try {
                    cameraCharacteristics = mCameraManager.getCameraCharacteristics(camera.mCameraID);
                } catch (CameraAccessException e) {
                    Log.e(LOG_TAG, "failed to obtain characteristics during texture configuration", e);
                    return;
                }
                Log.d(LOG_TAG, "texture initialization started");
                StreamConfigurationMap previewConfig = cameraCharacteristics.get(SCALER_STREAM_CONFIGURATION_MAP);

                int[] formats = previewConfig.getOutputFormats();
                previewSize = previewConfig.getOutputSizes(formats[0])[0];

                rotation = mTextureView.getDisplay().getRotation();
                Log.d(LOG_TAG, "Current Rotation: " + rotation);
                updateTransform();

                texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                Surface surface = new Surface(texture);
                mTextureView.setTag(R.id.TAG_KEY_SURFACE, surface);

                camera.addSurface(surface);
            });
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            Log.d(LOG_TAG, "texture size changed");
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
            float[] newMtx = new float[16];
            surface.getTransformMatrix(newMtx);

            Matrix matrix = new Matrix();
            float[] matrix3x3 = new float[]{
                    newMtx[0], newMtx[4], newMtx[4 * 3],
                    newMtx[1], newMtx[4 + 1], newMtx[4 * 3 + 1],
                    newMtx[3], newMtx[4 + 3], newMtx[4 * 3 + 3]
            };
            matrix.setValues(matrix3x3);

            if (prevMtx.equals(matrix)) {
                return;
            }
            prevMtx.set(matrix);

            if (startupMtx.isIdentity()) {
                startupMtx.set(matrix);
                return;
            }

            if (!startupMtx.invert(transformMtx)) {
                Log.w(LOG_TAG, "irreversible matrix!");
                return;
            }
            transformMtx.postConcat(matrix);
            updateTransform();
        }

        private void updateTransform() {
            Matrix m = computeTransformationMatrix(mTextureView, cameraCharacteristics, previewSize, rotation, transformMtx);
            runOnUiThread(() -> mTextureView.setTransform(m));
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
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
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
        public void onShadow(StreamWorker worker, byte[] shadow) {
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
            String shaStr = Base64.encodeToString(shadow, DEFAULT);
            if (shadowPref == null) {
                Log.d(LOG_TAG, String.format("Shadow initialized for user '%s':%s", user, shaStr));
                sm.setUserShadow(user, shaStr);
            } else if (!Arrays.equals(shadow, Base64.decode(shadowPref, DEFAULT))) {
                Log.d(LOG_TAG, String.format("Access denied for user '%s' with shadow %s (against %s)",
                        user, shaStr, shadowPref));
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

        @SuppressLint("StringFormatMatches")
        @Override
        public void onError(StreamWorker worker, StreamingServer.Situation situation, Object details) {
            if (situation == UNKNOWN_COMMAND) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        getString(R.string.unknown_command, details), Toast.LENGTH_SHORT).show()
                );
            } else {
                String errorName;
                if (details instanceof Exception) {
                    errorName = String.format("%s: %s", situation.toString(), ((Exception) details).getLocalizedMessage());
                } else {
                    errorName = situation.toString();
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, errorName, Toast.LENGTH_SHORT).show());
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