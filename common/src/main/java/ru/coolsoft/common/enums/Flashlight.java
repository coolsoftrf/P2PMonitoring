package ru.coolsoft.common.enums;

import ru.coolsoft.annotations.ById;
import ru.coolsoft.annotations.ByIdDefault;
import ru.coolsoft.annotations.ByIdRefField;

@ById
public enum Flashlight {
    OFF(0),
    ON(1),
    UNAVAILABLE(-1),
    @ByIdDefault
    UNKNOWN(-2);

    @ByIdRefField
    public final int mode;

    Flashlight(int modeId) {
        mode = modeId;
    }
}
