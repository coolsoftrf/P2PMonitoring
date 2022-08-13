package ru.coolsoft.p2pmonitor;

import static ru.coolsoft.common.Constants.AUTH_DENIED_SECURITY_ERROR;
import static ru.coolsoft.common.Constants.AUTH_OK;
import static ru.coolsoft.common.Constants.CIPHER_ALGORITHM;
import static ru.coolsoft.common.Constants.CIPHER_IV;
import static ru.coolsoft.common.Constants.CIPHER_IV_CHARSET;
import static ru.coolsoft.common.Constants.CIPHER_TRANSFORMATION;
import static ru.coolsoft.common.Constants.UNUSED;
import static ru.coolsoft.common.Protocol.END_OF_STREAM;
import static ru.coolsoft.common.Protocol.createSendRoutine;
import static ru.coolsoft.common.StreamId.AUTHENTICATION;
import static ru.coolsoft.common.StreamId.CONTROL;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.AUTH_ERROR;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.CONNECTION_CLOSED;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.ERROR_CLOSING;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.HOST_UNRESOLVED_ERROR;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.SOCKET_INITIALIZATION_ERROR;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.STREAMING_ERROR;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StreamCorruptedException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import ru.coolsoft.common.BlockCipherOutputStream;
import ru.coolsoft.common.Command;
import ru.coolsoft.common.Defaults;
import ru.coolsoft.common.Protocol;
import ru.coolsoft.common.StreamId;

public class StreamingClient extends Thread {
    private static final String LOG_TAG = StreamingClient.class.getSimpleName();

    private final EventListener eventListener;
    private final Handler handler;
    private final String serverAddress;
    private HandlerThread handlerThread;

    private Socket socket;
    private InputStream in;
    private CipherInputStream cin;
    private OutputStream out;
    private BlockCipherOutputStream cout;

    private byte[] mSha;

    public StreamingClient(String address, EventListener listener) {
        serverAddress = address;
        eventListener = listener;

        handlerThread = new HandlerThread(StreamingClient.class.getSimpleName());
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper(),
                createSendRoutine(streamId -> streamId == AUTHENTICATION ? out : cout));
    }

    public void logIn(String login, String password) {
        try {
            mSha = MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            //impossible case for SHA-256 supported since API v1
            terminate();
            return;
        }

        sendAuth(login.getBytes(StandardCharsets.UTF_8));
    }

    private void sendAuth(byte[] authPart) {
        Message.obtain(handler, UNUSED, AUTHENTICATION.id, UNUSED, authPart).sendToTarget();
    }

    public void sendCommand(Command command, byte[] data) {
        Message.obtain(handler, UNUSED, CONTROL.id, command.id, data).sendToTarget();
    }

    @Override
    public void run() {
        InetAddress address;
        try {
            address = InetAddress.getByName(serverAddress);
        } catch (UnknownHostException e) {
            eventListener.onError(HOST_UNRESOLVED_ERROR, null, e);
            return;
        }

        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(address, Defaults.SERVER_PORT));
            in = socket.getInputStream();
            out = socket.getOutputStream();
            eventListener.onConnected();
        } catch (IOException e) {
            eventListener.onError(SOCKET_INITIALIZATION_ERROR, null, e);
            return;
        }

        boolean shaSent = false;
        try {
            loop:
            while (true) {
                int streamId = (cin != null ? cin : in).read();
                switch (StreamId.byId(streamId)) {
                    case AUTHENTICATION:
                        int result = in.read();
                        switch (result) {
                            case AUTH_OK:
                                if (!shaSent) {
                                    sendAuth(mSha);
                                    shaSent = true;
                                } else {
                                    SecretKeySpec secretKeySpec = new SecretKeySpec(mSha, CIPHER_ALGORITHM);
                                    try {
                                        IvParameterSpec paramSpec = new IvParameterSpec(CIPHER_IV.getBytes(Charset.forName(CIPHER_IV_CHARSET)));
                                        Arrays.fill(mSha, (byte) 0);

                                        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
                                        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, paramSpec);
                                        cin = new CipherInputStream(in, cipher);

                                        cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
                                        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, paramSpec);
                                        cout = new BlockCipherOutputStream(out, cipher);
                                    } catch (GeneralSecurityException e) {
                                        eventListener.onError(AUTH_ERROR, (byte) AUTH_DENIED_SECURITY_ERROR, e);
                                        return;
                                    }
                                    eventListener.onAuthorized();
                                }
                                break;
                            default:
                                eventListener.onError(AUTH_ERROR, (byte) result, null);
                                //fall through to break loop
                            case END_OF_STREAM:
                                break loop;
                        }
                        break;

                    case MEDIA: {
                        byte[] media = Protocol.readData(cin);
                        eventListener.onMedia(media);
                        break;
                    }

                    case CONTROL: {
                        int cmdId = cin.read();
                        Command cmd = Command.byId(cmdId);
                        byte[] data;
                        switch (cmd) {
                            case UNDEFINED:
                                data = new byte[]{(byte) cmdId};
                                break;
                            case END_OF_STREAM:
                                break loop;
                            default:
                                data = Protocol.readData(cin);
                                break;
                        }
                        eventListener.onCommand(cmd, data);
                        break;
                    }
                    default:
                        Log.e(LOG_TAG, String.format("Unexpected stream ID: %d", streamId));
                    case END_OF_STREAM:
                        break loop;
                    case PADDING:
                        //skip
                }
            }
        } catch (StreamCorruptedException e) {
            eventListener.onError(STREAMING_ERROR, null, e);
        } catch (EOFException e) {
            eventListener.onError(CONNECTION_CLOSED, null, e);
        } catch (IOException e) {
            Log.w(LOG_TAG, "Client loop interrupted", e);
        } finally {
            terminateAndCleanup();
        }
    }

    public void terminate() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                eventListener.onError(ERROR_CLOSING, null, e);
            }
            socket = null;
        }
    }

    public void terminateAndCleanup() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                eventListener.onError(ERROR_CLOSING, null, e);
            }
            socket = null;
        }

        if (cout != null) {
            handler.post(() -> {
                if (cout != null) {
                    try {
                        cout.close();
                    } catch (IOException e) {
                        eventListener.onError(ERROR_CLOSING, null, e);
                    }
                    cout = null;
                }
            });
        }
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                eventListener.onError(ERROR_CLOSING, null, e);
            }
            out = null;
        }

        if (in != null) {
            try {
                in.close();
            } catch (IOException e) {
                eventListener.onError(ERROR_CLOSING, null, e);
            }
            in = null;
        }
        if (cin != null) {
            try {
                cin.close();
            } catch (IOException e) {
                eventListener.onError(ERROR_CLOSING, null, e);
            }
            cin = null;
        }

        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }

        eventListener.onDisconnected();
    }

    public interface EventListener {
        enum Error {
            HOST_UNRESOLVED_ERROR,
            SOCKET_INITIALIZATION_ERROR,
            AUTH_ERROR,
            STREAMING_ERROR,
            CONNECTION_CLOSED,
            ERROR_CLOSING
        }

        void onConnected();

        void onDisconnected();

        void onAuthorized();

        void onFormat(List<byte[]> csdBuffers);

        void onMedia(byte[] data);

        void onCommand(Command command, byte[] data);

        void onError(Error situation, Byte aux, Throwable e);
    }
}
