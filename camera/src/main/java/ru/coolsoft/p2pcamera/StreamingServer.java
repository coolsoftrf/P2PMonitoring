package ru.coolsoft.p2pcamera;

import android.util.Log;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

import ru.coolsoft.common.Command;

import static ru.coolsoft.common.Defaults.SERVER_PORT;

public class StreamingServer extends Thread {
    private final static String LOG_TAG = StreamingServer.class.getSimpleName();

    private boolean running = false; // флаг для проверки, запущен ли сервер
    private ServerSocket serverSocket; // экземпляр класса ServerSocket
    private final ArrayList<StreamWorker> streams = new ArrayList<>();
    private final EventListener listener;

    private final StreamWorker.WorkerEventListener workerListener = new StreamWorker.WorkerEventListener() {
        @Override
        public void onClientDisconnected(StreamWorker worker) {
            streams.remove(worker);
            listener.onClientDisconnected(worker.getSocket());
        }

        @Override
        public void reportCaps(StreamWorker worker) {
            listener.notifyTorchMode();

            //ToDo: report other caps:
            //  - cameras->resolutions mapping
        }
    };

    public StreamingServer(EventListener eventListener) {
        super(StreamingServer.class.getSimpleName());
        listener = eventListener;
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
        for (StreamWorker worker : streams) {
            //FixMe: ConcurrentModificationException
            worker.stopWorker();
        }
    }

    public void notifyClients(Command command, byte[] data/* clientId ... */) {
        for (StreamWorker worker : streams) {
            if (!worker.notifyClient(command, data)) {
                listener.onError(worker, Situation.CLIENT_NOTIFICATION_ERROR, null);
            }
        }
    }

    public void streamToClients(/* clientId ... */) {

    }

    @Override
    public void run() {
        try {
            // создаём серверный сокет, он будет прослушивать порт на наличие запросов
            serverSocket = new ServerSocket(SERVER_PORT);
            running = true;

            while (running) {
                // запускаем бесконечный цикл, внутри которого сокет будет слушать соединения и обрабатывать их
                // создаем клиентский сокет, метод accept() создаёт экземпляр Socket при новом подключении
                Socket clientSocket = serverSocket.accept();
                StreamWorker worker = new StreamWorker(clientSocket, workerListener, listener);
                streams.add(worker);
                worker.start();
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "I/O error occurred while waiting for a connection", e);
        }
    }

    public interface EventListener {
        void onClientConnected(Socket socket);

        void onClientDisconnected(Socket socket);

        void onToggleFlashlight();

        void notifyTorchMode();

        void onError(StreamWorker worker, Situation situation, Object details);
    }

    public enum Situation {
        CLIENT_NOTIFICATION_ERROR,
        MALFORMED_COMMAND,
        UNKNOWN_COMMAND,
    }
}
