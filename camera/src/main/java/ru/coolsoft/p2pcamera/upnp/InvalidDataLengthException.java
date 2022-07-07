package ru.coolsoft.p2pcamera.upnp;

import java.text.MessageFormat;

public class InvalidDataLengthException extends RuntimeException {
    InvalidDataLengthException(Operation operation, int opDataLength, int receivedDataLength) {
        super(MessageFormat.format("Invalid packet length received for {0}. Expected: {1}, received: {2}",
                operation.name(), opDataLength, receivedDataLength));
    }
}
