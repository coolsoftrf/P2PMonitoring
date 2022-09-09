package ru.coolsoft.p2pcamera.net;

import static android.util.Base64.DEFAULT;
import static ru.coolsoft.common.Constants.AUTH_DENIED_SERVER_ERROR;
import static ru.coolsoft.common.Constants.AUTH_OK;
import static ru.coolsoft.common.Constants.AUTH_OK_SKIP_SHA;
import static ru.coolsoft.common.Constants.CIPHER_ALGORITHM;
import static ru.coolsoft.common.Constants.CIPHER_IV;
import static ru.coolsoft.common.Constants.CIPHER_IV_CHARSET;
import static ru.coolsoft.common.Constants.CIPHER_TRANSFORMATION;
import static ru.coolsoft.common.Constants.UNUSED;
import static ru.coolsoft.common.Protocol.createSendRoutine;
import static ru.coolsoft.common.Protocol.readData;
import static ru.coolsoft.common.enums.StreamId.AUTHENTICATION;
import static ru.coolsoft.common.enums.StreamId.CONTROL;
import static ru.coolsoft.common.enums.StreamId.MEDIA;
import static ru.coolsoft.p2pcamera.net.StreamingServer.Situation.CLIENT_STREAMING_ERROR;
import static ru.coolsoft.p2pcamera.net.StreamingServer.Situation.CONNECTION_CLOSED;
import static ru.coolsoft.p2pcamera.net.StreamingServer.Situation.UNKNOWN_COMMAND;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StreamCorruptedException;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import ru.coolsoft.common.BlockCipherOutputStream;
import ru.coolsoft.common.Constants;
import ru.coolsoft.common.enums.Command;
import ru.coolsoft.common.enums.StreamId;
import ru.coolsoft.p2pcamera.net.StreamingServer.EventListener;

public class StreamWorker extends Thread {
    private static final String LOG_TAG = StreamWorker.class.getSimpleName();

    private final WorkerEventListener workerListener;
    private final EventListener listener;
    private final Handler handler;
    private volatile Socket socket;
    private HandlerThread handlerThread;
    private InputStream in;
    private OutputStream out;
    private CipherInputStream cin;
    private BlockCipherOutputStream cout;
    private boolean running;

    private enum AuthStage {
        User,
        Denied,
        Shadow,
        Allowed
    }

    private AuthStage authStage = AuthStage.User;
    private byte[] sha;

    public StreamWorker(Socket socket, WorkerEventListener workerEventListener, EventListener eventListener) {
        this.socket = socket;
        listener = eventListener;
        workerListener = workerEventListener;

        handlerThread = new HandlerThread(StreamWorker.class.getSimpleName());
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper(),
                createSendRoutine(streamId -> streamId == AUTHENTICATION ? out : (cout == null ? out : cout)));
        running = true;
    }

    public Socket getSocket() {
        return socket;
    }

    public void stopWorker() {
        Log.d(LOG_TAG, "Stopping worker thread");
        running = false;

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

            if (cout != null) {
                handler.post(() -> {
                    if (cout != null) {
                        try {
                            cout.close();
                            cout = null;
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
            if (out != null) {
                out.close();
                out = null;
            }

            if (in != null) {
                in.close();
                in = null;
            }
            if (cin != null) {
                cin.close();
                cin = null;
            }

            if (handlerThread != null) {
                handlerThread.quitSafely();
                handlerThread = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean notifyClient(Command command, byte[] data) {
        return sendData(data, CONTROL.id, command.id);
    }

    public boolean sendFrame(byte[] buffer) {
        return isNotReady() || sendData(buffer, MEDIA.id);
    }

    public void onAuthorizationFailed(@Constants.AuthFailureCause int cause) {
        authStage = AuthStage.Denied;
        sendData(null, AUTHENTICATION.id, cause);
        //Connection should be closed by client once notification is received
    }

    public void onUserKnown(String shadow) {
        sha = Base64.decode(shadow, DEFAULT);
        if (setupCiphers() == null) {
            sendData(null, AUTHENTICATION.id, AUTH_OK_SKIP_SHA);
        }
    }

    public void onAuthorized() {
        switch (authStage) {
            case User:
                authStage = AuthStage.Shadow;
                break;
            case Shadow:
                if (setupCiphers() != null) {
                    return;
                }
                break;
            default:
                //unexpected state
                return;
        }
        sendData(null, AUTHENTICATION.id, AUTH_OK);
    }

    private Exception setupCiphers() {
        SecretKeySpec secretKeySpec = new SecretKeySpec(sha, CIPHER_ALGORITHM);
        try {
            IvParameterSpec paramSpec = new IvParameterSpec(CIPHER_IV.getBytes(Charset.forName(CIPHER_IV_CHARSET)));
            Arrays.fill(sha, (byte) 0);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, paramSpec);
            cout = new BlockCipherOutputStream(out, cipher);

            cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, paramSpec);
            cin = new CipherInputStream(in, cipher);
        } catch (GeneralSecurityException e) {
            onAuthorizationFailed(AUTH_DENIED_SERVER_ERROR);
            Log.e(LOG_TAG, "Cipher initialization failed", e);
            return e;
        }
        authStage = AuthStage.Allowed;
        return null;
    }

    private boolean sendData(byte[] data, int... args) {
        if (args.length == 0) {
            return false;
        }
        if (args[0] == AUTHENTICATION.id) {
            if (out == null) {
                return false;
            }
        } else if (cout == null || data == null) {
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
                    StreamId key = StreamId.byId((cin != null ? cin : in).read());
                    switch (key) {
                        case AUTHENTICATION:
                            if (!processAuth()) {
                                break loop;
                            }
                            break;
                        case CONTROL:
                            if (isNotReady()) {
                                break loop;
                            }
                            processCommand();
                            break;
                        case END_OF_STREAM:
                            break loop;
                    }
                }
            } catch (StreamCorruptedException e) {
                listener.onError(this, CLIENT_STREAMING_ERROR, e);
            } catch (EOFException e) {
                listener.onError(this, CONNECTION_CLOSED, e);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Worker loop interrupted", e);
            } finally {
                stopWorker();
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "I/O stream creation failed", e);
        }
    }

    private boolean isNotReady() {
        return authStage != AuthStage.Allowed;
    }

    private boolean processAuth() throws IOException {
        //ToDo: wait until listener reports its decision
        switch (authStage) {
            case User:
                listener.onUser(this, new String(getAuthData(), StandardCharsets.UTF_8));
                return true;
            case Shadow:
                sha = getAuthData();
                listener.onShadow(this, sha);
                return true;
            default:
                //Allowed - protocol violation
                //Denied - no operations permitted
                return false;
        }
    }

    @NonNull
    private byte[] getAuthData() throws StreamCorruptedException, EOFException {
        byte[] bytes = readData(in);
        if (bytes.length == 0) {
            throw new StreamCorruptedException();
        }

        return bytes;
    }

    private void processCommand() throws IOException {
        int cmdId = cin.read();
        Command cmd = Command.byId(cmdId);
        readData(cin);
        switch (cmd) {
            case FLASHLIGHT:
                //ToDo: process explicit state
                listener.onToggleFlashlight();
                break;
            case CAPS:
                workerListener.reportCaps(this);
                break;
            case END_OF_STREAM:
                throw new EOFException();
            default:
                listener.onError(this, UNKNOWN_COMMAND, cmdId);
                break;
        }
    }

    interface WorkerEventListener {
        void reportCaps(StreamWorker worker);

        void onClientDisconnected(StreamWorker worker);
    }
}
