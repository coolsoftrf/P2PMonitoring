package ru.coolsoft.common;

import static ru.coolsoft.common.Constants.SIZEOF_INT;
import static ru.coolsoft.common.StreamId.CONTROL;

import android.os.Handler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class Protocol {
    public final static int END_OF_STREAM = -1;
    public final static int MEDIA_BUFFER_SIZE = 65536;

    public static Handler.Callback createSendRoutine(Supplier<OutputStream> outputStreamSupplier) {
        return msg -> {
            try {
                boolean isCommand = msg.arg1 == CONTROL.id;
                int dataLen;
                if (msg.obj != null) {
                    dataLen = ((byte[]) msg.obj).length;
                } else {
                    dataLen = 0;
                }

                OutputStream out = outputStreamSupplier.get();
                out.write(msg.arg1);
                if (isCommand) {
                    out.write(msg.arg2);
                }

                ByteBuffer buf = ByteBuffer.allocate(SIZEOF_INT);
                buf.putInt(dataLen);
                out.write(buf.array());
                if (dataLen > 0) {
                    out.write((byte[]) msg.obj);
                }
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        };
    }

    public static byte[] readData(InputStream in) {
        byte[] lenBuffer = new byte[4];
        readAllBytes(in, lenBuffer);
        int len = ByteBuffer.wrap(lenBuffer).getInt();

        byte[] data = new byte[len];
        readAllBytes(in, data);
        return data;
    }

    private static void readAllBytes(InputStream in, byte[] buffer) {
        int remainder = buffer.length;
        int acquired = 0;
        try {
            do {
                int read = in.read(buffer, acquired, remainder);
                if (read == END_OF_STREAM) {
                    break;
                }
                acquired += read;
                remainder -= read;
            } while (remainder > 0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public interface Supplier<T> {
        T get();
    }
}
