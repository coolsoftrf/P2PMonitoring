package ru.coolsoft.p2pcamera.upnp;

import java.nio.ByteBuffer;

/**
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | Vers = 0      | OP = x        | Reserved                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | Internal Port                 | Suggested External Port       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | Requested Port Mapping Lifetime in Seconds                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public class MapRequestDO extends PmpDataObject {
    private final short internalPort;
    private final short externalPort;
    private final int lifetime;

    public MapRequestDO(PmpServer.Protocol protocol, short port, int lifetimeSeconds) {
        super(protocol.id);
        externalPort = internalPort = port;
        lifetime = lifetimeSeconds;
    }

    @Override
    int getPacketLen() {
        return 12;
    }

    @Override
    ByteBuffer getData() {
        return super.getData().putShort((short) 0)
                .putShort(internalPort).putShort(externalPort)
                .putInt(lifetime);
    }
}