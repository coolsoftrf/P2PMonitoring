package ru.coolsoft.p2pmonitor;

import static ru.coolsoft.common.Protocol.createSendRoutine;
import static ru.coolsoft.common.StreamId.CONTROL;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.CLOSING;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.HOST_UNRESOLVED_ERROR;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.IO_INITIALIZATION_ERROR;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.SOCKET_INITIALIZATION_ERROR;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;

import ru.coolsoft.common.Command;
import ru.coolsoft.common.Defaults;
import ru.coolsoft.common.Protocol;
import ru.coolsoft.common.StreamId;

public class StreamingClient extends Thread {
    private final EventListener eventListener;
    private final Handler handler;
    private final String serverAddress;
    private HandlerThread handlerThread;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public StreamingClient(String address, EventListener listener) {
        serverAddress = address;
        eventListener = listener;

        handlerThread = new HandlerThread(StreamingClient.class.getSimpleName());
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper(), createSendRoutine(() -> out));
    }

    public void sendCommand(Command command, byte[] data) {
        Message msg = new Message();
        msg.arg1 = CONTROL.getId();
        msg.arg2 = command.getId();
        msg.obj = data;
        handler.sendMessage(msg);
    }

    @Override
    public void run() {
        InetAddress address;
        try {
            address = InetAddress.getByName(serverAddress);
        } catch (UnknownHostException e) {
            eventListener.onError(HOST_UNRESOLVED_ERROR, e);
            e.printStackTrace();
            return;
        }

        try {
            socket = new Socket(address, Defaults.SERVER_PORT);
            eventListener.onConnected();
        } catch (IOException e) {
            eventListener.onError(SOCKET_INITIALIZATION_ERROR, e);
            e.printStackTrace();
            return;
        }

        try {
            in = socket.getInputStream();
            out = socket.getOutputStream();
        } catch (IOException e) {
            eventListener.onError(IO_INITIALIZATION_ERROR, e);
            e.printStackTrace();
        }

        try {
            loop:
            while (true) {
                int streamId = in.read();
                switch (StreamId.byId(streamId)) {
                    case MEDIA:
                        byte[] media = Protocol.readData(in);
                        eventListener.onMedia(media);
                        break;
                    case CONTROL:
                        Command cmd = Command.byId(in.read());
                        byte[] data = Protocol.readData(in);
                        eventListener.onCommand(cmd, data);
                        break;
                    case END_OF_STREAM:
                        terminate();
                        break loop;
                    default:
                        throw new IllegalStateException("Unexpected value: " + Command.byId(streamId));
                }
            }
        } catch (IOException e) {
            terminate();
        }
    }

    public void terminate() {
        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
            if (handlerThread != null) {
                handlerThread.quitSafely();
                handlerThread = null;
            }
        } catch (IOException e) {
            eventListener.onError(CLOSING, e);
        }
        eventListener.onDisconnected();
    }

    public interface EventListener {
        enum Error {
            HOST_UNRESOLVED_ERROR,
            SOCKET_INITIALIZATION_ERROR,
            IO_INITIALIZATION_ERROR,
            CLOSING
        }

        void onConnected();

        void onDisconnected();

        void onFormat(List<byte[]> csdBuffers);

        void onMedia(byte[] data);

        void onCommand(Command command, byte[] data);

        void onError(Error situation, Throwable e);
    }
}
