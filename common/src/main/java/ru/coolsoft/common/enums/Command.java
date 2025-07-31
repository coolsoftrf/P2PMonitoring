package ru.coolsoft.common.enums;

import lombok.AllArgsConstructor;
import lombok.Lookup;
import ru.coolsoft.common.Protocol;

@AllArgsConstructor
@Lookup(field = "id", constructorArgumentOrdinal = 0, defaultValue = "UNDEFINED")
public enum Command {
    FLASHLIGHT(0),
    CAPS(1),
    AVAILABILITY(2),
    FORMAT(3),
    END_OF_STREAM(Protocol.END_OF_STREAM),
    UNDEFINED(-256);

    public final int id;
}
