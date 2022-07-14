package ru.coolsoft.common;

public enum Command {
    FLASHLIGHT(0),
    CAPS(1),
    AVAILABILITY(2),
    FORMAT(3),
    END_OF_STREAM(Protocol.END_OF_STREAM),
    UNDEFINED(-256);

    public final int id;

    Command(int commandId) {
        id = commandId;
    }

    public static Command byId(int id) {
        switch (id) {
            case 0:
                return FLASHLIGHT;
            case 1:
                return CAPS;
            case 2:
                return AVAILABILITY;
            case 3:
                return FORMAT;
            case Protocol.END_OF_STREAM:
                return END_OF_STREAM;
            default:
                return UNDEFINED;
        }
    }
}
