package ru.coolsoft.common;

import ru.coolsoft.annotations.ById;
import ru.coolsoft.annotations.ByIdDefault;
import ru.coolsoft.annotations.ByIdRefField;

@ById
public
enum StreamId {
    AUTHENTICATION(0),
    CONTROL(1),
    MEDIA(2),
    END_OF_STREAM(Protocol.END_OF_STREAM),
    @ByIdDefault
    UNDEFINED(-256);

    @ByIdRefField
    public final int id;

    StreamId(int streamId) {
        id = streamId;
    }
}
