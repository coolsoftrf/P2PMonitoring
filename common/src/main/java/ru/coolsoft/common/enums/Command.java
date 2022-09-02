package ru.coolsoft.common.enums;

import ru.coolsoft.annotations.ById;
import ru.coolsoft.annotations.ByIdDefault;
import ru.coolsoft.annotations.ByIdRefField;
import ru.coolsoft.common.Protocol;

@ById
public enum Command {
    FLASHLIGHT(0),
    CAPS(1),
    AVAILABILITY(2),
    FORMAT(3),
    END_OF_STREAM(Protocol.END_OF_STREAM),
    @ByIdDefault
    UNDEFINED(-256);

    @ByIdRefField
    public final int id;

    Command(int commandId) {
        id = commandId;
    }
}
