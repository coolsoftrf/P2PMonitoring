package ru.coolsoft.common.enums;

import lombok.AllArgsConstructor;
import lombok.Lookup;
import ru.coolsoft.common.Protocol;

@AllArgsConstructor
@Lookup(field = "id", constructorArgumentOrdinal = 0, defaultValue = "UNDEFINED")
public enum StreamId {
    AUTHENTICATION(0),
    CONTROL(1),
    MEDIA(2),
    PADDING(3),

    END_OF_STREAM(Protocol.END_OF_STREAM),

    UNDEFINED(0x80000000);

    public final int id;
}
