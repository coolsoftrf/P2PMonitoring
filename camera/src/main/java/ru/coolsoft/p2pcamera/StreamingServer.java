package ru.coolsoft.p2pcamera;

import static ru.coolsoft.common.Defaults.SERVER_PORT;
import static ru.coolsoft.p2pcamera.upnp.PmpServer.Protocol.TCP;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.MessageFormat;
import java.util.ArrayList;

import ru.coolsoft.common.Command;
import ru.coolsoft.p2pcamera.upnp.PmpServer;

public class StreamingServer extends Thread {
    private final static String LOG_TAG = StreamingServer.class.getSimpleName();

    private boolean running = false;
    private ServerSocket serverSocket;
    private final ArrayList<StreamWorker> streams = new ArrayList<>();
    private final EventListener serverListener;

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

    private PmpServer pmpServer;
    private InetAddress externalAddress;

    public StreamingServer(EventListener eventListener) {
        super(StreamingServer.class.getSimpleName());
        serverListener = eventListener;
    }

    public void stopServer() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if(pmpServer != null){
            pmpServer.stopServer();
            pmpServer = null;
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
            pmpServer = new PmpServer(/*InetAddress.getLocalHost(),*/ address -> {
                externalAddress = address;
                Log.i(LOG_TAG, MessageFormat.format("External address: {0}", externalAddress));
                //ToDo: request actual forwarding
            });
            pmpServer.start();
            pmpServer.requestPortForward(TCP, SERVER_PORT);

            serverSocket = new ServerSocket(SERVER_PORT);
            running = true;

            while (running) {
                Socket clientSocket = serverSocket.accept();
                StreamWorker worker = new StreamWorker(clientSocket, workerListener, serverListener);
                streams.add(worker);
                worker.start();
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "I/O error occurred while waiting for a connection", e);
        }
    }

    public interface EventListener {

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
