package ru.coolsoft.p2pmonitor;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import ru.coolsoft.common.Command;
import ru.coolsoft.common.Defaults;
import ru.coolsoft.common.StreamId;

import static ru.coolsoft.common.Protocol.createSendRoutine;
import static ru.coolsoft.common.StreamId.CONTROL;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.CLOSING;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.HOST_UNRESOLVED_ERROR;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.IO_INITIALIZATION_ERROR;
import static ru.coolsoft.p2pmonitor.StreamingClient.EventListener.Error.SOCKET_INITIALIZATION_ERROR;

public class StreamingClient extends Thread {
    private final EventListener eventListener;
    private final Handler handler;
    private final String serverAddress;
    private HandlerThread handlerThread;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private boolean running = false;

    public StreamingClient(String address, EventListener listener) {
        serverAddress = address;
        eventListener = listener;

        handlerThread = new HandlerThread(StreamingClient.class.getSimpleName());
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper(), createSendRoutine(()->out));
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
            running = true;
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
            while (running) {
                int streamId = in.read();
                switch (StreamId.byId(streamId)) {
                    case CONTROL:
                        Command cmd = Command.byId(in.read());
                        int len = in.read();
                        byte[] data = new byte[len];
                        readAllBytes(data);
                        eventListener.onCommand(cmd, data);
                        break;
                    case END_OF_STREAM: //Server disconnected
                        running = false;
                        terminate();
                        break;
                    default:
                        throw new IllegalStateException("Unexpected value: " + Command.byId(streamId));
                }
            }
        } catch (IOException e) {
            running = false;
            terminate();
        }
    }

    public void terminate() {
        if (socket != null) {
            try {
                socket.close();
                socket = null;
                handlerThread.quitSafely();
                handlerThread = null;
            } catch (IOException e) {
                eventListener.onError(CLOSING, e);
            }
        }
        eventListener.onDisconnected(); //ToDo: cleanup in listener
    }

    private void readAllBytes(byte[] buffer) {
        try {
            //FixMe:
            // - read ALL bytes
            // - consider «-1» as EOF - here and elsewhere
            in.read(buffer);
        } catch (IOException e) {
            e.printStackTrace();
        }
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

        void onCommand(Command command, byte[] data);

        void onError(Error situation, Throwable e);
    }
}
