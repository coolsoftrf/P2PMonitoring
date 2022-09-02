package ru.coolsoft.p2pcamera.net;

import static ru.coolsoft.common.Constants.SSL_PROTOCOL;
import static ru.coolsoft.common.Defaults.SERVER_PORT;
import static ru.coolsoft.p2pcamera.net.PortMappingServer.PortMappingProtocol.TCP;

import android.util.Log;

import androidx.annotation.Nullable;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.PortMappingEntry;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Map;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import ru.coolsoft.common.Constants;
import ru.coolsoft.common.enums.Command;

public class StreamingServer extends Thread {
    private final static String LOG_TAG = StreamingServer.class.getSimpleName();
    private final static String INSECURE_SERVER_FALLBACK_SUFFIX = ". Starting an insecure socket server";

    private boolean running = false;
    private ServerSocket serverSocket;
    private final ArrayList<StreamWorker> streams = new ArrayList<>();
    private final EventListener serverListener;
    private final PortMappingServer mappingServer;

    private final StreamWorker.WorkerEventListener workerListener = new StreamWorker.WorkerEventListener() {
        @Override
        public void onClientDisconnected(StreamWorker worker) {
            streams.remove(worker);
            serverListener.onClientDisconnected(worker);
        }

        @Override
        public void reportCaps(StreamWorker worker) {
            serverListener.notifyTorchMode();
            serverListener.notifyAvailability();

            //ToDo: report all caps:
            //  cams:List<
            //      caps:Map<
            //          capability:[
            //              Resolution, FlashAvailable, AF, MF, ...
            //              (https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#fields_1)
            //          ],
            //          value:Any
            //      >
            //  >
        }
    };

    public StreamingServer(EventListener eventListener) {
        super(StreamingServer.class.getSimpleName());
        serverListener = eventListener;

        mappingServer = new PortMappingServer(new PortMappingServer.PortMappingListener() {
            @Override
            public void onGatewaysDiscovered(Map<InetAddress, GatewayDevice> gateways) {
                serverListener.onGatewaysDiscovered(gateways);
                mappingServer.mapPort(SERVER_PORT, TCP, "Primary P2P Camera");
            }

            @Override
            public void onMappingDone(InetSocketAddress info) {
                serverListener.onMappingDone(info);
            }

            @Override
            public void onAlreadyMapped(PortMappingEntry entry) {
                serverListener.onAlreadyMapped(entry);
                //ToDo:
                // - check if mapped to local IP
                // - start secondary camera mapping flow otherwise
            }

            @Override
            public void onPortMappingServerError(PortMappingServer.Situation situation, @Nullable Throwable e) {
                serverListener.onPortMappingServerError(situation, e);
            }
        });
        mappingServer.discoverGateways();
    }

    public void stopServer() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
            mappingServer.removeMapping(SERVER_PORT, TCP);
            mappingServer.stopServer();
        } catch (IOException e) {
            e.printStackTrace();
        }
        for (StreamWorker worker : streams) {
            //FixMe: ConcurrentModificationException
            worker.stopWorker();
        }
    }

    public void notifyClients(Command command, byte[] data/* clientId ... */) {
        for (StreamWorker worker : streams) {
            if (!worker.notifyClient(command, data)) {
                serverListener.onError(worker, Situation.CLIENT_NOTIFICATION_ERROR, null);
            }
        }
    }

    public void streamToClients(byte[] data/* clientId ... */) {
        for (StreamWorker worker : streams) {
            if (!worker.sendFrame(data)) {
                serverListener.onError(worker, Situation.CLIENT_STREAMING_ERROR, null);
            }
        }
    }

    @Override
    public void run() {
        try {
            serverSocket = createServerSocket();
            running = true;

            while (running) {
                Socket clientSocket = serverSocket.accept();
                StreamWorker worker = new StreamWorker(clientSocket, workerListener, serverListener);
                streams.add(worker);
                worker.start();
            }
        } catch (IOException e) {
            Log.i(LOG_TAG, "I/O interrupted while waiting for a connection", e);
        }
    }

    private ServerSocket createServerSocket() throws IOException {
        boolean keyImported;
        KeyStore ksAndroid;
        try {
            ksAndroid = KeyStore.getInstance(Constants.ANDROID_KEY_STORE);
            ksAndroid.load(null);
            keyImported = ksAndroid.isKeyEntry(Constants.ALIAS_MONITORING);
        } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
            Log.w(LOG_TAG, e);
            keyImported = false;
            ksAndroid = null;
        }

        SSLContext ctx;
        if (keyImported) {
            ctx = getSecureContext(ksAndroid);
        } else {
            ctx = null;
            Log.i(LOG_TAG, "Private key not found" + INSECURE_SERVER_FALLBACK_SUFFIX);
        }

        if (!keyImported || ctx == null) {
            return new ServerSocket(SERVER_PORT);
        }

        SSLServerSocketFactory socketFactory = ctx.getServerSocketFactory();
        Log.i(LOG_TAG, "Starting an SSL socket server");
        return socketFactory.createServerSocket(SERVER_PORT);
    }

    private SSLContext getSecureContext(KeyStore ks) {
        X509TrustManager[] tms;
        try {
            tms = getTrustManagers(ks);
        } catch (NoSuchAlgorithmException e) {
            Log.w(LOG_TAG, "Algorithm not supported by TrustManagerFactory" + INSECURE_SERVER_FALLBACK_SUFFIX, e);
            return null;
        } catch (KeyStoreException e) {
            Log.w(LOG_TAG, "TrustManagerFactory initialization failed" + INSECURE_SERVER_FALLBACK_SUFFIX, e);
            return null;
        }

        X509KeyManager[] kms;
        try {
            kms = getKeyManagers(ks);
        } catch (NoSuchAlgorithmException e) {
            Log.w(LOG_TAG, "Algorithm not supported by KeyManagerFactory" + INSECURE_SERVER_FALLBACK_SUFFIX, e);
            return null;
        } catch (KeyStoreException e) {
            Log.w(LOG_TAG, "KeyManagerFactory initialization failed" + INSECURE_SERVER_FALLBACK_SUFFIX, e);
            return null;
        } catch (UnrecoverableKeyException e) {
            Log.w(LOG_TAG, "Key recovery failed" + INSECURE_SERVER_FALLBACK_SUFFIX, e);
            return null;
        }

        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance(SSL_PROTOCOL);
            ctx.init(kms, tms, null);
        } catch (NoSuchAlgorithmException e) {
            Log.w(LOG_TAG, "Protocol initialization failed" + INSECURE_SERVER_FALLBACK_SUFFIX, e);
            return null;
        } catch (KeyManagementException e) {
            Log.w(LOG_TAG, "SSLContext initialization failed" + INSECURE_SERVER_FALLBACK_SUFFIX, e);
            return null;
        }

        return ctx;
    }

    private static X509TrustManager[] getTrustManagers(KeyStore keystore) throws NoSuchAlgorithmException, KeyStoreException {
        TrustManagerFactory trustMgrFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustMgrFactory.init(keystore);
        for (TrustManager trustManager : trustMgrFactory.getTrustManagers()) {
            if (trustManager instanceof X509TrustManager) {
                return new X509TrustManager[]{(X509TrustManager) trustManager};
            }
        }
        return null;
    }

    private static X509KeyManager[] getKeyManagers(KeyStore keystore)
            throws NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException {
        KeyManagerFactory keyMgrFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyMgrFactory.init(keystore, null);
        for (KeyManager keyManager : keyMgrFactory.getKeyManagers()) {
            if (keyManager instanceof X509KeyManager) {
                return new X509KeyManager[]{(X509KeyManager) keyManager};
            }
        }
        return null;
    }

    public interface EventListener extends PortMappingServer.PortMappingListener {
        void onUser(StreamWorker worker, String user);

        void onShadow(StreamWorker worker, byte[] shadow);

        void onClientConnected(StreamWorker worker);

        void onClientDisconnected(StreamWorker worker);

        void onToggleFlashlight();

        void notifyTorchMode();

        void notifyAvailability();

        void onError(StreamWorker worker, Situation situation, Object details);
    }

    public enum Situation {
        CLIENT_STREAMING_ERROR,
        CLIENT_NOTIFICATION_ERROR,
        CONNECTION_CLOSED,
        UNKNOWN_COMMAND,
    }
}
