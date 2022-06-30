package ru.coolsoft.common;

public enum Command {
    FLASHLIGHT(0),
    CAPS(1),
    UNDEFINED(-1);

    private final int id;
    public int aux;

    Command(int commandId) {
        id = commandId;
    }

    public int getId() {
        return id;
    }

    public static Command byId(int id) {
        switch (id) {
            case 0:
                return FLASHLIGHT;
            case 1:
                return CAPS;
            default:
                //ToDo: fix thread unsafe approach
                UNDEFINED.aux = id;
                return UNDEFINED;
        }
    }
}
