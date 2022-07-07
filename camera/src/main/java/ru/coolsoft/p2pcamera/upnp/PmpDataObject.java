package ru.coolsoft.p2pcamera.upnp;

import static ru.coolsoft.p2pcamera.upnp.Operation.OpCodes.OP_EXTERNAL_ADDRESS_OPCODE;

import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.nio.ByteBuffer;

abstract class PmpDataObject {
    private final static byte VERS = 0;
    protected final int HEADER_SIZE = 2;
    final Operation op;

    //for requests
    protected PmpDataObject(byte opCode) {
        op = Operation.getByCode(opCode);
    }

    //for responses
    protected PmpDataObject(byte[] data) {
        op = Operation.getByCode(data[1]);
        checkDataLength(data.length);
    }

    abstract int getPacketLen();

    ByteBuffer getData() {
        ByteBuffer out = ByteBuffer.allocate(getPacketLen());
        return out.put(VERS).put(op.opCode);
    }

    static PmpDataObject resolveResponse(byte[] data) throws UnknownServiceException, UnknownHostException {
        if (data.length < 2) {
            throw new IllegalArgumentException("invalid data length");
        }
        switch (data[1]) {
            case OP_EXTERNAL_ADDRESS_OPCODE:
                return new ExternalAddressResponseDO(data);
            default:
                throw new UnknownServiceException("Unknown op code received: " + data[1]);
        }
    }

    private void checkDataLength(int actualLength) {
        if (actualLength != getPacketLen()) {
            throw new InvalidDataLengthException(op, getPacketLen(), actualLength);
        }
    }
}
