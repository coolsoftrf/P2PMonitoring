package ru.coolsoft.common;

import static ru.coolsoft.common.Constants.SIZEOF_INT;
import static ru.coolsoft.common.Constants.UNUSED;
import static ru.coolsoft.common.StreamId.AUTHENTICATION;

import android.os.Handler;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StreamCorruptedException;
import java.nio.ByteBuffer;

public class Protocol {
    public final static int END_OF_STREAM = -1; // by Streams contract
    public final static int MEDIA_BUFFER_SIZE = 65536;

    public static Handler.Callback createSendRoutine(Supplier<OutputStream, StreamId> outputStreamSupplier) {
        return msg -> {
            try {
                int dataLen;
                if (msg.obj != null) {
                    dataLen = ((byte[]) msg.obj).length;
                } else {
                    dataLen = 0;
                }

                OutputStream out = outputStreamSupplier.get(StreamId.byId(msg.arg1));
                out.write(msg.arg1);
                if (msg.arg2 != UNUSED) {
                    out.write(msg.arg2);
                }
                if (msg.arg1 == AUTHENTICATION.id && dataLen == 0) {
                    return true;
                }

                ByteBuffer buf = ByteBuffer.allocate(SIZEOF_INT);
                buf.putInt(dataLen);
                out.write(buf.array());
                if (dataLen > 0) {
                    out.write((byte[]) msg.obj);
                }
                out.flush();
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        };
    }

    public static byte[] readData(InputStream in) throws StreamCorruptedException, EOFException {
        byte[] lenBuffer = new byte[4];
        readAllBytes(in, lenBuffer);
        int len = ByteBuffer.wrap(lenBuffer).getInt();
        if (len < 0 || len > MEDIA_BUFFER_SIZE) {
            throw new StreamCorruptedException("Invalid data len");
        }

        byte[] data = new byte[len];
        readAllBytes(in, data);
        return data;
    }

    //ToDo: throw and handle an exception in case of END_OF_STREAM
    private static void readAllBytes(InputStream in, byte[] buffer) throws EOFException {
        int remainder = buffer.length;
        int acquired = 0;
        do {
            int read;
            try {
                read = in.read(buffer, acquired, remainder);
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
            if (read == END_OF_STREAM) {
                throw new EOFException();
            }
            acquired += read;
            remainder -= read;
        } while (remainder > 0);
    }

    public interface Supplier<T, P> {
        T get(P param);
    }
}
