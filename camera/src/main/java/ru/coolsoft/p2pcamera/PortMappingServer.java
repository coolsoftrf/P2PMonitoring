package ru.coolsoft.p2pcamera;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import androidx.annotation.Nullable;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;
import org.bitlet.weupnp.PortMappingEntry;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

public class PortMappingServer {
    private final static int MESSAGE_DISCOVER_GATEWAYS = 1;
    private final static int MESSAGE_REQUEST_MAPPING = 2;
    private final static int MESSAGE_REMOVE_MAPPING = 3;

    private final HandlerThread handlerThread = new HandlerThread(PortMappingServer.class.getSimpleName());
    private final Handler handler;

    private final PortMappingListener mappingListener;
    private final GatewayDiscover gatewayDiscover;
    private GatewayDevice activeGW;


    public PortMappingServer(PortMappingListener listener) {
        gatewayDiscover = new GatewayDiscover();
        mappingListener = listener;
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper(), msg -> {
            switch (msg.what) {
                case MESSAGE_DISCOVER_GATEWAYS:
                    try {
                        mappingListener.onGatewaysDiscovered(gatewayDiscover.discover());
                    } catch (IOException | SAXException | ParserConfigurationException e) {
                        mappingListener.onPortMappingServerError(Situation.DISCOVERY_ERROR, e);
                    }
                    break;
                case MESSAGE_REQUEST_MAPPING: {
                    int port = msg.arg1;
                    String protocol = PortMappingProtocol.values()[msg.arg2].toString();
                    String description = (String) msg.obj;
                    activeGW = gatewayDiscover.getValidGateway();
                    if (activeGW == null) {
                        mappingListener.onPortMappingServerError(Situation.UPNP_GATEWAY_NOT_FOUND_ERROR, null);
                        break;
                    }

                    PortMappingEntry mappingEntry = new PortMappingEntry();
                    try {
                        String extAddress = activeGW.getExternalIPAddress();
                        if (activeGW.getSpecificPortMappingEntry(port, protocol, mappingEntry)) {
                            if (mappingEntry.getRemoteHost() == null) {
                                mappingEntry.setRemoteHost(extAddress);
                            }
                            mappingListener.onAlreadyMapped(mappingEntry);
                        } else {

                            if (activeGW.addPortMapping(
                                    port, port, activeGW.getLocalAddress().getHostAddress(), protocol, description)) {
                                mappingListener.onMappingDone(new InetSocketAddress(extAddress, port));
                            } else {
                                mappingListener.onPortMappingServerError(Situation.MAPPING_ERROR, null);
                            }
                        }
                    } catch (IOException | SAXException e) {
                        mappingListener.onPortMappingServerError(Situation.MAPPING_ERROR, e);
                    }
                    break;
                }
                case MESSAGE_REMOVE_MAPPING: {
                    if (activeGW != null) {
                        int port = msg.arg1;
                        String protocol = PortMappingProtocol.values()[msg.arg2].toString();

                        try {
                            activeGW.deletePortMapping(port, protocol);
                        } catch (IOException | SAXException e) {
                            mappingListener.onPortMappingServerError(Situation.MAPPING_ERROR, e);
                        }
                    }
                    break;
                }
                default:
            }
            return true;
        });

        System.setProperty("org.xml.sax.driver", "org.xmlpull.v1.sax2.Driver");
    }

    public void stopServer() {
        handlerThread.quitSafely();
    }

    public void discoverGateways() {
        handler.sendEmptyMessage(MESSAGE_DISCOVER_GATEWAYS);
    }

    public void mapPort(short internalPort, PortMappingProtocol protocol, String description) {
        handler.sendMessage(Message.obtain(
                handler, MESSAGE_REQUEST_MAPPING, internalPort, protocol.ordinal(), description
        ));
    }

    public void removeMapping(short internalPort, PortMappingProtocol protocol) {
        handler.sendMessage(Message.obtain(
                handler, MESSAGE_REMOVE_MAPPING, internalPort, protocol.ordinal()
        ));
    }

    public interface PortMappingListener {
        void onGatewaysDiscovered(Map<InetAddress, GatewayDevice> gateways);

        void onMappingDone(InetSocketAddress info);

        void onAlreadyMapped(PortMappingEntry entry);

        void onPortMappingServerError(Situation situation, @Nullable Throwable e);
    }

    public enum PortMappingProtocol {
        TCP,
        UDP
    }

    public enum Situation {
        DISCOVERY_ERROR,
        UPNP_GATEWAY_NOT_FOUND_ERROR,
        MAPPING_ERROR
    }
}
