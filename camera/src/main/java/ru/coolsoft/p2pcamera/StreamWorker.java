package ru.coolsoft.p2pcamera;

import static ru.coolsoft.common.Constants.AUTH_OK;
import static ru.coolsoft.common.Constants.UNUSED;
import static ru.coolsoft.common.Protocol.createSendRoutine;
import static ru.coolsoft.common.Protocol.readData;
import static ru.coolsoft.common.StreamId.AUTHENTICATION;
import static ru.coolsoft.common.StreamId.CONTROL;
import static ru.coolsoft.common.StreamId.MEDIA;
import static ru.coolsoft.p2pcamera.StreamingServer.Situation.UNKNOWN_COMMAND;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import ru.coolsoft.common.Command;
import ru.coolsoft.common.Constants;
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

    private enum AuthStage {
        User,
        Denied,
        Shadow,
        Verified
    }

    private AuthStage authStage = AuthStage.User;

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

    public void onAuthorizationFailed(@Constants.AuthFailureCause int cause) {
        authStage = AuthStage.Denied;
        sendData(null, AUTHENTICATION.id, cause);
//        stopWorker(); //Connection should be closed by client once notification is received
    }

    public void onAuthorized() {
        switch (authStage) {
            case User:
                authStage = AuthStage.Shadow;
                break;
            case Shadow:
                authStage = AuthStage.Verified;
                break;
            default:
                //unexpected state
                return;
        }
        sendData(null, AUTHENTICATION.id, AUTH_OK);
    }

    private boolean sendData(byte[] data, int... args) {
        if (out == null
                || args.length == 0
                || args[0] != StreamId.AUTHENTICATION.id && data == null) {
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
                        case AUTHENTICATION:
                            if (!processAuth()) {
                                break loop;
                            }
                            break;
                        case CONTROL:
                            if (authStage != AuthStage.Verified || !processCommand()) {
                                break loop;
                            }
                            break;
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

    private boolean processAuth() {
        //ToDo: wait until listener reports its decision
        switch (authStage) {
            case User:
                listener.onUser(this, getAuthData());
                return true;
            case Shadow:
                listener.onShadow(this, getAuthData());
                return true;
            case Verified:
                //protocol violation
            case Denied:
                //access denied
            default:
                //whatever
                return false;
        }
    }

    @Nullable
    private String getAuthData() {
        byte[] bytes = readData(in);
        if (bytes.length == 0) {
            return null;
        }

        return new String(bytes, StandardCharsets.UTF_8);
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
        void reportCaps(StreamWorker worker);

        void onClientDisconnected(StreamWorker worker);
    }
}
