package ru.coolsoft.common.enums;

import ru.coolsoft.annotations.ById;
import ru.coolsoft.annotations.ByIdDefault;
import ru.coolsoft.annotations.ByIdRefField;
import ru.coolsoft.common.Protocol;

@ById
public
enum StreamId {
    AUTHENTICATION(0),
    CONTROL(1),
    MEDIA(2),
    PADDING(3),

    END_OF_STREAM(Protocol.END_OF_STREAM),

    @ByIdDefault
    UNDEFINED(0x80000000);

    @ByIdRefField
    public final int id;

    StreamId(int streamId) {
        id = streamId;
    }
}
