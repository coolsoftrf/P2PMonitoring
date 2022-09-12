package ru.coolsoft.p2pmonitor;

import static ru.coolsoft.common.Constants.ALIAS_MONITORING;
import static ru.coolsoft.common.Constants.ANDROID_KEY_STORE;
import static ru.coolsoft.common.Constants.AUTH_DENIED_SECURITY_ERROR;
import static ru.coolsoft.common.Constants.AUTH_OK;
import static ru.coolsoft.common.Constants.AUTH_OK_SKIP_SHA;
import static ru.coolsoft.common.Constants.CIPHER_ALGORITHM;
import static ru.coolsoft.common.Constants.CIPHER_IV;
import static ru.coolsoft.common.Constants.CIPHER_IV_CHARSET;
import static ru.coolsoft.common.Constants.CIPHER_TRANSFORMATION;
import static ru.coolsoft.common.Constants.SSL_PROTOCOL;
import static ru.coolsoft.common.Constants.UNUSED;
import static ru.coolsoft.common.Defaults.SERVER_PORT;
import static ru.coolsoft.common.Protocol.END_OF_STREAM;
import static ru.coolsoft.common.Protocol.createSendRoutine;
import static ru.coolsoft.common.enums.StreamId.AUTHENTICATION;
import static ru.coolsoft.common.enums.StreamId.CONTROL;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.AUTH_ERROR;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.CONNECTION_CLOSED;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.ERROR_CLOSING;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.HOST_UNRESOLVED_ERROR;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.ILLEGAL_HOST_DETAILS_ERROR;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.SOCKET_INITIALIZATION_ERROR;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.STREAMING_ERROR;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.OperationCanceledException;
import android.util.Log;

import androidx.core.util.Consumer;

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
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import ru.coolsoft.common.BlockCipherOutputStream;
import ru.coolsoft.common.Protocol;
import ru.coolsoft.common.enums.Command;
import ru.coolsoft.common.enums.StreamId;

public class StreamingClient extends Thread {
    private static final String LOG_TAG = StreamingClient.class.getSimpleName();
    private static final String INSECURE_CONNECTION_FALLBACK_SUFFIX = ". Trying insecure connection";

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

    private final Semaphore userInteractionSemaphore = new Semaphore(0);

    public StreamingClient(String address, EventListener listener) {
        serverAddress = address;
        eventListener = listener;

        handlerThread = new HandlerThread(StreamingClient.class.getSimpleName());
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper(),
                createSendRoutine(streamId -> streamId == AUTHENTICATION ? out : (cout == null ? out : cout)));
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
        InetSocketAddress address;
        try {
            String[] parts = serverAddress.split("/");
            InetAddress host = InetAddress.getByName(parts[0]);
            int port;
            if (parts.length > 1) {
                port = Short.parseShort(parts[1]);
            } else {
                port = SERVER_PORT;
            }
            address = new InetSocketAddress(host, port);
        } catch (UnknownHostException e) {
            eventListener.onError(HOST_UNRESOLVED_ERROR, null, e);
            terminateAndCleanup();
            return;
        } catch (NumberFormatException e) {
            eventListener.onError(ILLEGAL_HOST_DETAILS_ERROR, null, e);
            terminateAndCleanup();
            return;
        }

        try {
            createSocket(address);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            eventListener.onConnected();
        } catch (IOException | OperationCanceledException e) {
            eventListener.onError(SOCKET_INITIALIZATION_ERROR, null, e);
            terminateAndCleanup();
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
                                    break;
                                }
                                // else fall through
                            case AUTH_OK_SKIP_SHA:
                                setupCiphers();
                                eventListener.onAuthorized();
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
        } catch (GeneralSecurityException e) {
            eventListener.onError(AUTH_ERROR, (byte) AUTH_DENIED_SECURITY_ERROR, e);
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

    private void setupCiphers() throws GeneralSecurityException {
        SecretKeySpec secretKeySpec = new SecretKeySpec(mSha, CIPHER_ALGORITHM);
        IvParameterSpec paramSpec = new IvParameterSpec(CIPHER_IV.getBytes(Charset.forName(CIPHER_IV_CHARSET)));
        Arrays.fill(mSha, (byte) 0);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, paramSpec);
        cin = new CipherInputStream(in, cipher);

        cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, paramSpec);
        cout = new BlockCipherOutputStream(out, cipher);
    }

    private final boolean[] insecureConnectionDecisionContainer = new boolean[1];

    private void createSocket(InetSocketAddress address) throws IOException {
        SSLSocket secureSocket = createSecureSocket(address);
        if (secureSocket != null) {
            try {
                secureSocket.startHandshake();
                socket = secureSocket;
                return;
            } catch (IOException e) {
                //ToDo: handle certificate mismatch case not to fall back to insecure socket, but report a security violation
                //Possible reasons:
                // javax.net.ssl.SSLHandshakeException: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
                //  Caused by: java.security.cert.CertificateException: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
                //      Caused by: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found
                // javax.net.ssl.SSLHandshakeException: SSL handshake aborted: ssl=0x9c6a8280: I/O error during system call, Connection reset by peer
                Log.w(LOG_TAG, "SSL handshake failed" + INSECURE_CONNECTION_FALLBACK_SUFFIX, e);
            }
        }

        eventListener.requestInsecureConnectionConfirmation();
        userInteractionSemaphore.acquireUninterruptibly();
        if (!insecureConnectionDecisionContainer[0]) {
            throw new OperationCanceledException("Non secure connection cancelled by user");
        }

        socket = new Socket();
        socket.connect(address);
    }

    private final SSLContext[] secureContextContainer = new SSLContext[1];
    private final Consumer<TrustManager[]> trustManagerConsumer = trustManagers -> {
        if (trustManagers != null && trustManagers.length > 0) {
            try {
                secureContextContainer[0].init(null, trustManagers, null);
            } catch (KeyManagementException e) {
                Log.w(LOG_TAG, "SSLContext initialization failed" + INSECURE_CONNECTION_FALLBACK_SUFFIX, e);
            }
        } else {
            secureContextContainer[0] = null;
        }
        userInteractionSemaphore.release();
    };

    private SSLSocket createSecureSocket(InetSocketAddress address) throws IOException {
        try {
            secureContextContainer[0] = SSLContext.getInstance(SSL_PROTOCOL);
            provideTrustManager(trustManagerConsumer);
        } catch (NoSuchAlgorithmException e) {
            Log.w(LOG_TAG, "Protocol initialization failed" + INSECURE_CONNECTION_FALLBACK_SUFFIX, e);
            return null;
        } catch (CertificateException | KeyStoreException e) {
            Log.w(LOG_TAG, "TrustManager initialization failed" + INSECURE_CONNECTION_FALLBACK_SUFFIX, e);
            return null;
        }

        userInteractionSemaphore.acquireUninterruptibly();
        if (secureContextContainer[0] == null) {
            throw new OperationCanceledException("Untrusted connection cancelled by user");
        }

        SSLSocketFactory SocketFactory = secureContextContainer[0].getSocketFactory();
        return (SSLSocket) SocketFactory.createSocket(address.getAddress(), address.getPort());
    }

    private void provideTrustManager(Consumer<TrustManager[]> resultConsumer) throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException {
        KeyStore ksAndroid = KeyStore.getInstance(ANDROID_KEY_STORE);
        ksAndroid.load(null);
        if (ksAndroid.isCertificateEntry(ALIAS_MONITORING)) {
            TrustManagerFactory trustMgrFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustMgrFactory.init(ksAndroid);
            for (TrustManager trustManager : trustMgrFactory.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager) {
                    resultConsumer.accept(new X509TrustManager[]{(X509TrustManager) trustManager});
                    return;
                }
            }
        }

        eventListener.requestUntrustedConnectionConfirmation(EventListener.UntrustedConnectionCase.NO_CERTIFICATE);
    }

    public void onInsecureConnectionDecision(boolean decision) {
        insecureConnectionDecisionContainer[0] = decision;
        userInteractionSemaphore.release();
    }

    @SuppressLint("CustomX509TrustManager")
    public void onUntrustedConnectionDecision(boolean decision) {
        if (decision) {
            trustManagerConsumer.accept(new TrustManager[]{
                    new X509TrustManager() {
                        @SuppressLint("TrustAllX509TrustManager")
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @SuppressLint("TrustAllX509TrustManager")
                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }
                    }
            });
        } else {
            trustManagerConsumer.accept(null);
        }
    }

    public boolean isAuthorized() {
        return cout != null;
    }

    public void terminate() {
        if (socket != null) {
            handler.post(() -> {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        eventListener.onError(ERROR_CLOSING, null, e);
                    }
                    socket = null;
                }
            });
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
            ILLEGAL_HOST_DETAILS_ERROR,
            SOCKET_INITIALIZATION_ERROR,
            AUTH_ERROR,
            STREAMING_ERROR,
            CONNECTION_CLOSED,
            ERROR_CLOSING
        }

        enum UntrustedConnectionCase {
            NO_CERTIFICATE,
            CERTIFICATE_DOENT_MATCH
        }

        void requestUntrustedConnectionConfirmation(UntrustedConnectionCase certificateCase);

        void requestInsecureConnectionConfirmation();

        void onConnected();

        void onDisconnected();

        void onAuthorized();

        void onFormat(List<byte[]> csdBuffers);

        void onMedia(byte[] data);

        void onCommand(Command command, byte[] data);

        void onError(Error situation, Byte aux, Throwable e);
    }
}
