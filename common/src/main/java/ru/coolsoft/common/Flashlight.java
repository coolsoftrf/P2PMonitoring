package ru.coolsoft.common;

public enum Flashlight {
    OFF(0),
    ON(1),
    UNAVAILABLE(-1),
    UNKNOWN(-2);

    public final int mode;

    Flashlight(int modeId) {
        mode = modeId;
    }

    public int getModeId() {
        return mode;
    }

    public static Flashlight getById(int id) {
        switch (id) {
            case 0:
                return OFF;
            case 1:
                return ON;
            case -1:
                return UNAVAILABLE;
            default:
                return UNKNOWN;
        }
    }
}
