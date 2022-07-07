package ru.coolsoft.p2pcamera.upnp;

import static ru.coolsoft.common.Constants.SIZEOF_INET_ADDRESS;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

/**
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | Vers = 0      | OP = 128 + 0  | Result Code (net byte order)  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | Seconds Since Start of Epoch (in network byte order)          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | External IPv4 Address (a.b.c.d)                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public class ExternalAddressResponseDO extends PmpDataObject {
    private final short resultCode;
    private final int secondsOfEpoch;
    private final InetAddress externalIPv4Address;

    public ExternalAddressResponseDO(byte[] data) throws UnknownHostException {
        super(data);
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.position(HEADER_SIZE);

        resultCode = buffer.getShort();
        secondsOfEpoch=buffer.getInt();
        byte[] address = new byte[SIZEOF_INET_ADDRESS];
        buffer.get(address);
        externalIPv4Address = InetAddress.getByAddress(address);
    }

    public InetAddress getExternalIPv4Address() {
        return externalIPv4Address;
    }

    @Override
    int getPacketLen() {
        return 12;
    }
}