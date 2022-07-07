package ru.coolsoft.p2pcamera.upnp;

import static ru.coolsoft.p2pcamera.upnp.Operation.OpCodes.OP_EXTERNAL_ADDRESS_OPCODE;

/**
 * 0                   1
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | Vers = 0      | OP = 0        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public class ExternalAddressRequestDO extends PmpDataObject {

    public ExternalAddressRequestDO() {
        super(OP_EXTERNAL_ADDRESS_OPCODE);
    }

    @Override
    int getPacketLen() {
        return 2;
    }
}