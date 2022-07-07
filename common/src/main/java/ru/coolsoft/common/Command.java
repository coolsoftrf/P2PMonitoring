package ru.coolsoft.common;

public enum Command {
    FLASHLIGHT(0),
    CAPS(1),
    FORMAT(2),
    UNDEFINED(-1);

    public final int id;
    public int aux;

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
                return FORMAT;
            default:
                //ToDo: fix thread unsafe approach
                UNDEFINED.aux = id;
                return UNDEFINED;
        }
    }
}
