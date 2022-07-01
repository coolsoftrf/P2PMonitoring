package ru.coolsoft.p2pcamera;

import static ru.coolsoft.common.Protocol.createSendRoutine;
import static ru.coolsoft.common.StreamId.CONTROL;
import static ru.coolsoft.common.StreamId.MEDIA;
import static ru.coolsoft.p2pcamera.StreamingServer.Situation.UNKNOWN_COMMAND;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import ru.coolsoft.common.Command;
import ru.coolsoft.common.StreamId;
import ru.coolsoft.p2pcamera.StreamingServer.EventListener;

public class StreamWorker extends Thread {
    private static final String LOG_TAG = StreamWorker.class.getSimpleName();

    private final WorkerEventListener workerListener;
    private final EventListener listener;
    private final Handler handler;
    private Socket socket;
    private HandlerThread handlerThread;
    private InputStream in;
    private OutputStream out;
    private boolean running;

    public StreamWorker(Socket socket, WorkerEventListener workerEventListener, EventListener eventListener) {
        this.socket = socket;
        listener = eventListener;
        workerListener = workerEventListener;

        handlerThread = new HandlerThread(StreamWorker.class.getSimpleName());
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper(), createSendRoutine(() -> out));
        running = true;
    }

    public Socket getSocket() {
        return socket;
    }

    public void stopWorker() {
        Log.d(LOG_TAG, "Stopping worker thread");
        running = false;

        try {
            if (in != null) {
                in.close();
                in = null;
            }
            if (out != null) {
                out.flush();
                out.close();
                out = null;
            }
            if (handlerThread != null) {
                handlerThread.quitSafely();
                handlerThread = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        //disconnected socket reports null address on API 22, so notify listener prior to closing the socket
        workerListener.onClientDisconnected(this);
        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean notifyClient(Command command, byte[] data) {
        return sendData(data, CONTROL.getId(), command.getId());
    }

    public boolean sendFrame(byte[] buffer) {
        return sendData(buffer, MEDIA.getId());
    }

    private boolean sendData(byte[] data, int... args) {
        if (out == null || data == null) {
            return false;
        }
        Message msg = new Message();
        msg.arg1 = args[0];
        if (args.length > 1) {
            msg.arg2 = args[1];
        }
        msg.obj = data;
        handler.sendMessage(msg);
        return true;
    }

    @Override
    public void run() {
        Log.d(LOG_TAG, "Starting worker thread");
        try {
            in = socket.getInputStream();
            out = socket.getOutputStream();

            try {
                listener.onClientConnected(socket);
                while (running) {
                    StreamId key = StreamId.byId(in.read());
                    switch (key) {
                        case CONTROL:
                            processCommand();
                            break;
                        case END_OF_STREAM:
                            stopWorker();
                            break;
                    }
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error occurred while reading input", e);
                stopWorker();
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "I/O stream creation failed", e);
            e.printStackTrace();
        }
    }

    private void processCommand() {
        try {
            Command cmd = Command.byId(in.read());
            switch (cmd) {
                case FLASHLIGHT:
                    //ToDo: process explicit state
                    listener.onToggleFlashlight();
                    break;
                case CAPS:
                    workerListener.reportCaps(this);
                    break;
                default:
                    listener.onError(this, UNKNOWN_COMMAND, cmd.aux);
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    interface WorkerEventListener {
        void onClientDisconnected(StreamWorker worker);

        void reportCaps(StreamWorker worker);
    }
}
