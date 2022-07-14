package ru.coolsoft.p2pcamera;

import static ru.coolsoft.common.Constants.UNUSED;
import static ru.coolsoft.common.Protocol.createSendRoutine;
import static ru.coolsoft.common.Protocol.readData;
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
    private volatile Socket socket;
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

        try {
            if (socket != null) {
                synchronized (this) {
                    if (socket != null) {
                        workerListener.onClientDisconnected(this);
                        socket.close();
                        socket = null;
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean notifyClient(Command command, byte[] data) {
        return sendData(data, CONTROL.id, command.id);
    }

    public boolean sendFrame(byte[] buffer) {
        return sendData(buffer, MEDIA.id);
    }

    private boolean sendData(byte[] data, int... args) {
        if (out == null || data == null) {
            return false;
        }

        Message.obtain(handler, UNUSED, args[0], args.length > 1 ? args[1] : UNUSED, data).sendToTarget();
        return true;
    }

    @Override
    public void run() {
        Log.d(LOG_TAG, "Starting worker thread");
        try {
            in = socket.getInputStream();
            out = socket.getOutputStream();

            try {
                listener.onClientConnected(this);
                loop:
                while (running) {
                    StreamId key = StreamId.byId(in.read());
                    switch (key) {
                        case CONTROL:
                            if (processCommand()) {
                                break;
                            }
                            // fall through to break loop
                        case END_OF_STREAM:
                            break loop;
                    }
                }
            } catch (Exception e) {
                Log.w(LOG_TAG, "Worker loop interrupted", e);
            } finally {
                stopWorker();
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "I/O stream creation failed", e);
            e.printStackTrace();
        }
    }

    private boolean processCommand() {
        try {
            int cmdId = in.read();
            Command cmd = Command.byId(cmdId);
            readData(in);
            switch (cmd) {
                case FLASHLIGHT:
                    //ToDo: process explicit state
                    listener.onToggleFlashlight();
                    break;
                case CAPS:
                    workerListener.reportCaps(this);
                    break;
                case END_OF_STREAM:
                    return false;
                default:
                    listener.onError(this, UNKNOWN_COMMAND, cmdId);
                    break;
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error reading command ID", e);
        }
        return true;
    }

    interface WorkerEventListener {
        void onClientDisconnected(StreamWorker worker);

        void reportCaps(StreamWorker worker);
    }
}
