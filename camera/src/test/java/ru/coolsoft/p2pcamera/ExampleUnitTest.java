package ru.coolsoft.p2pcamera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static ru.coolsoft.common.Constants.CIPHER_ALGORITHM;
import static ru.coolsoft.common.Constants.CIPHER_IV;
import static ru.coolsoft.common.Constants.CIPHER_IV_CHARSET;
import static ru.coolsoft.common.Constants.CIPHER_TRANSFORMATION;
import static ru.coolsoft.common.enums.StreamId.PADDING;

import org.junit.Test;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import ru.coolsoft.common.CipherBlockSizeAwareOutputStream;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }

    @Test(timeout = 5000)
    public void testBlockCipherOutputStream() {
        //any 32 bytes
        byte[] sha = "0123456789ABCDEFghijklmnopqrstuv".getBytes(StandardCharsets.US_ASCII);

        byte[] dataIn = new byte[31];
        for (int i = 0; i < dataIn.length; i++) {
            dataIn[i] = (byte) i;
        }

        SecretKeySpec secretKeySpec = new SecretKeySpec(sha, CIPHER_ALGORITHM);
        IvParameterSpec paramSpec = new IvParameterSpec(CIPHER_IV.getBytes(Charset.forName(CIPHER_IV_CHARSET)));

        CipherBlockSizeAwareOutputStream cout;
        PipedInputStream in = new PipedInputStream(1024);
        PipedOutputStream out;
        try {
            out = new PipedOutputStream(in);
        } catch (IOException e) {
            fail("out stream initialization");
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, paramSpec);
            cout = new CipherBlockSizeAwareOutputStream(out, cipher);
        } catch (GeneralSecurityException e) {
            fail("cout");
            return;
        }

        CipherInputStream cin;
        try {
            Cipher inCipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            inCipher.init(Cipher.DECRYPT_MODE, secretKeySpec, paramSpec);
            cin = new CipherInputStream(in, inCipher);
        } catch (GeneralSecurityException e) {
            fail("cin");
            return;
        }

        for (int i = 0; i < 2; i++) {
            try {
                cout.write(dataIn);
                cout.flush();
            } catch (IOException e) {
                fail("writing, iteration:" + i);
                return;
            }

            byte[] dataOut = new byte[dataIn.length];
            int pos = 0;
            while (pos < dataIn.length) {
                try {
                    //skip leading paddings
                    byte ibyte;
                    //noinspection StatementWithEmptyBody
                    while ((ibyte = (byte) cin.read()) == PADDING.id) ;
                    if (ibyte == -1) {
                        fail("EOF while skipping paddings, iteration:" + i + ", pos: " + pos);
                        return;
                    }
                    dataOut[pos++] = ibyte;

                    int read = cin.read(dataOut, pos, dataIn.length - pos);
                    if (read == -1) {
                        fail("EOF while reading data, iteration:" + i + ", pos: " + pos);
                        return;
                    }
                    pos += read;
                } catch (IOException e) {
                    fail("reading, iteration:" + i + ", pos:" + pos + ". " + e.getMessage());
                    return;
                }
            }

            assertArrayEquals("Invalid decryption, iteration:" + i, dataIn, dataOut);
        }
        //ToDo: Validate encrypted outputs are different between iterations
    }
}