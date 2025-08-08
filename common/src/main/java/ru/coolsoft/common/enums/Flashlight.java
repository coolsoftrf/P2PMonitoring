package ru.coolsoft.common.enums;

import lombok.AllArgsConstructor;
import lombok.Lookup;

@AllArgsConstructor
@Lookup(field = "mode", defaultValue = "UNKNOWN")
public enum Flashlight {
    OFF(0),
    ON(1),
    UNAVAILABLE(-1),
    UNKNOWN(-2);

    public final int mode;
}
