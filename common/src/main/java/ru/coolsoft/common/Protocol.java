package ru.coolsoft.common;

import static ru.coolsoft.common.StreamId.CONTROL;

import android.os.Handler;

import java.io.IOException;
import java.io.OutputStream;

public class Protocol {
    public final static int END_OF_STREAM = -1;

    public static Handler.Callback createSendRoutine(Supplier<OutputStream> outputStreamSupplier) {
        return msg -> {
            try {
                boolean isCommand = msg.arg1 == CONTROL.getId();
                int datalen;
                int len = 1;
                if (msg.obj != null) {
                    datalen = ((byte[]) msg.obj).length;
                    //ToDo: allow chunks longer than 255
                    // - remaster via sequential/buffered direct stream writing (+ flush)
                    len += 1 + datalen;//data len byte + data itself
                } else {
                    datalen = 0;
                }
                if (isCommand) {
                    len++;
                }

                byte[] data = new byte[len];
                data[0] = (byte) msg.arg1;
                if (isCommand) {
                    data[1] = (byte) msg.arg2;
                }
                if (datalen > 0) {
                    data[isCommand ? 2 : 1] = (byte) datalen;
                    System.arraycopy((byte[]) msg.obj, 0, data, isCommand ? 3 : 2, datalen);
                }
                outputStreamSupplier.get().write(data);
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        };
    }

    public interface Supplier<T> {
        T get();
    }
}
