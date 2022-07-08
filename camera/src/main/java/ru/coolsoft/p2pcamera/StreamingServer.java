package ru.coolsoft.p2pcamera;

import static ru.coolsoft.common.Defaults.SERVER_PORT;
import static ru.coolsoft.p2pcamera.PortMappingServer.PortMappingProtocol.TCP;

import android.util.Log;

import androidx.annotation.Nullable;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.PortMappingEntry;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Map;

import ru.coolsoft.common.Command;

public class StreamingServer extends Thread {
    private final static String LOG_TAG = StreamingServer.class.getSimpleName();

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
            serverSocket = new ServerSocket(SERVER_PORT);
            running = true;

            while (running) {
                Socket clientSocket = serverSocket.accept();
                StreamWorker worker = new StreamWorker(clientSocket, workerListener, serverListener);
                streams.add(worker);
                worker.start();
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "I/O error occurred while waiting for a connection", e);
        }
    }

    public interface EventListener extends PortMappingServer.PortMappingListener {

        void onClientConnected(StreamWorker worker);

        void onClientDisconnected(StreamWorker worker);

        void onToggleFlashlight();

        void notifyTorchMode();

        void onError(StreamWorker worker, Situation situation, Object details);
    }

    public enum Situation {
        CLIENT_STREAMING_ERROR,
        CLIENT_NOTIFICATION_ERROR,
        MALFORMED_COMMAND,
        UNKNOWN_COMMAND,
    }
}
