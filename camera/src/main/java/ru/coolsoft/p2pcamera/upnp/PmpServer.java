package ru.coolsoft.p2pcamera.upnp;

import static ru.coolsoft.common.Constants.NAT_PMP_PORT;
import static ru.coolsoft.p2pcamera.upnp.Operation.OpCodes.OP_MAP_TCP_OPCODE;
import static ru.coolsoft.p2pcamera.upnp.Operation.OpCodes.OP_MAP_UDP_OPCODE;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;

import ru.coolsoft.p2pcamera.CameraApplication;

public class PmpServer extends Thread {
    private static final String LOG_TAG = PmpServer.class.getSimpleName();
    private static final int MAX_PACKET_LEN = 16;

    private final DatagramSocket socket;
    private final PmpEventListener pmpListener;
    private InetSocketAddress gateway;
    private boolean running;

    private HandlerThread handlerThread;
    private final Handler handler;


    public PmpServer(/*InetAddress localAddr,*/ PmpEventListener eventListener) throws SocketException {
        ConnectivityManager cm = (ConnectivityManager) CameraApplication.getAppContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        Network[] nets = cm.getAllNetworks();

        nets:
        for (Network net : nets) {
            for (RouteInfo route : cm.getLinkProperties(net).getRoutes()) {
                InetAddress gwAddress = route.getGateway();
                if (!gwAddress.isAnyLocalAddress()) {
                    Log.i(LOG_TAG, MessageFormat.format("Gateway found: {0}", gwAddress));
                    gateway = new InetSocketAddress(gwAddress, NAT_PMP_PORT);
                    break nets;
                }
            }
        }

        pmpListener = eventListener;
        socket = new DatagramSocket();
        Log.i(LOG_TAG, String.format("NAT-PMP server bound to port %d", socket.getLocalPort()));

        handlerThread = new HandlerThread(PmpServer.class.getSimpleName());
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper(), msg -> {
            try {
                byte[] data = (byte[]) msg.obj;

                DatagramPacket packet = new DatagramPacket(data, data.length, gateway);
                socket.send(packet);
            } catch (IOException e) {
                Log.e(LOG_TAG, "failed to send request", e);
            }
            return true;
        });
    }

    @Override
    public void run() {
        byte[] buf = new byte[MAX_PACKET_LEN];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        running = true;
        while (running) {
            try {
                socket.receive(packet);
                PmpDataObject result = PmpDataObject.resolveResponse(packet.getData());
                if (result instanceof ExternalAddressResponseDO) {
                    pmpListener.onExternalAddressReceived(((ExternalAddressResponseDO) result).getExternalIPv4Address());
                } else {
                    Log.w(LOG_TAG, "Unexpected packet received");
                }
            } catch (IOException e) {
                Log.e(LOG_TAG, "socket interrupted", e);
            }
        }
    }

    public void requestExternalAddress() {
        ByteBuffer buffer = new ExternalAddressRequestDO().getData();
        Message msg = new Message();
        msg.obj = buffer.array();
        handler.sendMessage(msg);
    }

    public void requestPortForward(Protocol protocol, short port) {
        ByteBuffer buffer = new MapRequestDO(protocol, port, 30 * 24 * 3600).getData();
        Message msg = new Message();
        msg.obj = buffer.array();
        handler.sendMessage(msg);
    }

    public void stopServer() {
        //ToDo: cancel port forwarding

        running = false;
        socket.close();
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
    }

    public interface PmpEventListener {
        void onExternalAddressReceived(InetAddress address);
    }

    public enum Protocol {
        UDP(OP_MAP_UDP_OPCODE),
        TCP(OP_MAP_TCP_OPCODE);

        public final byte id;

        Protocol(byte protocolId) {
            id = protocolId;
        }
    }
}
