package ru.coolsoft.p2pcamera.upnp;

import static ru.coolsoft.p2pcamera.upnp.Operation.OpCodes.OP_EXTERNAL_ADDRESS_OPCODE;
import static ru.coolsoft.p2pcamera.upnp.Operation.OpCodes.OP_MAP_TCP_OPCODE;
import static ru.coolsoft.p2pcamera.upnp.Operation.OpCodes.OP_MAP_UDP_OPCODE;
import static ru.coolsoft.p2pcamera.upnp.Operation.OpCodes.UNDEFINED_OPCODE;

public enum Operation {
    OP_EXTERNAL_ADDRESS(OP_EXTERNAL_ADDRESS_OPCODE),
    OP_FORWARD_UDP(OP_MAP_UDP_OPCODE),
    OP_FORWARD_TCP(OP_MAP_TCP_OPCODE),
    UNDEFINED(UNDEFINED_OPCODE);

    final byte opCode;

    Operation(byte code) {
        opCode = code;
    }

    static Operation getByCode(byte opCode) {
        switch (opCode) {
            case OP_EXTERNAL_ADDRESS_OPCODE:
                return OP_EXTERNAL_ADDRESS;
            case OP_MAP_UDP_OPCODE:
                return OP_FORWARD_UDP;
            case OP_MAP_TCP_OPCODE:
                return OP_FORWARD_TCP;
            default:
                return UNDEFINED;
        }
    }

    static class OpCodes {
        final static byte OP_EXTERNAL_ADDRESS_OPCODE = 0;
        final static byte OP_MAP_UDP_OPCODE = 1;
        final static byte OP_MAP_TCP_OPCODE = 2;
        final static byte UNDEFINED_OPCODE = -1;
    }
}
