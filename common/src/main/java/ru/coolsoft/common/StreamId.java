package ru.coolsoft.common;

public enum StreamId {
    CONTROL(0),
    MEDIA(1),
    END_OF_STREAM(Protocol.END_OF_STREAM),
    UNDEFINED(-256);

    private final int id;

    StreamId(int streamId) {
        id = streamId;
    }

    public int getId() {
        return id;
    }

    public static StreamId byId(int streamId){
        switch (streamId){
            case 0:
                return CONTROL;
            case 1:
                return MEDIA;
            case Protocol.END_OF_STREAM:
                return END_OF_STREAM;
            default:
                return UNDEFINED;
        }
    }
}
