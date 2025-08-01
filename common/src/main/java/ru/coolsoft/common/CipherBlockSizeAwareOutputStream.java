package ru.coolsoft.common;

import static ru.coolsoft.common.enums.StreamId.PADDING;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

public class CipherBlockSizeAwareOutputStream extends FilterOutputStream {

    private final Cipher cipher;
    private final int cipherBlockSize;
    private final byte[] ibuffer = new byte[1];
    private byte[] obuffer;
    private boolean closed;
    private int written;

    public CipherBlockSizeAwareOutputStream(OutputStream os, Cipher c) {
        super(os);
        cipher = c;
        cipherBlockSize = c.getBlockSize();
    }

    @Override
    public void write(int b) throws IOException {
        ibuffer[0] = (byte) b;
        write(ibuffer, 0, 1);
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        obuffer = cipher.update(b, off, len);
        if (obuffer != null) {
            out.write(obuffer);
            written = (written + len) % cipherBlockSize;
        }
        obuffer = null;
    }

    @Override
    public void flush() throws IOException {
        byte[] padding = new byte[(cipherBlockSize - written) % cipherBlockSize + cipherBlockSize];
        Arrays.fill(padding, (byte) PADDING.id);
        write(padding, 0, padding.length);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }

        closed = true;
        try {
            obuffer = cipher.doFinal();
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            obuffer = null;
            throw new IOException(e);
        }
        try {
            flush();
        } catch (IOException ignored) {
        }
        out.close();
    }
}