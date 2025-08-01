package ru.coolsoft.common;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class Constants {
    public static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    public static final String ALIAS_MONITORING = "pk";
    public static final String SSL_PROTOCOL = "TLSv1.2";

    public static final int AUTH_OK = 0;
    public static final int AUTH_OK_SKIP_SHA = 127;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AUTH_DENIED_SERVER_ERROR,
            AUTH_DENIED_SECURITY_ERROR,
            AUTH_DENIED_WRONG_CREDENTIALS,
            AUTH_DENIED_NOT_ALLOWED
    })
    public @interface AuthFailureCause {
    }

    public static final int AUTH_DENIED_SERVER_ERROR = 1;
    public static final int AUTH_DENIED_SECURITY_ERROR = 2;
    public static final int AUTH_DENIED_WRONG_CREDENTIALS = 3;
    public static final int AUTH_DENIED_NOT_ALLOWED = 4;

    public static final String CIPHER_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    public static final String CIPHER_ALGORITHM = "AES";
    public static final String CIPHER_IV = "СмотретьНеВр3дн0"; //16 chars long
    public static final String CIPHER_IV_CHARSET = "ISO-8859-5";

    public static final int UNUSED = -1;
    public static final int SIZEOF_INT = 4;
    public static final int SIZEOF_LONG = 8;

    public static final byte CAMERA_UNAVAILABLE = 0;
    public static final byte CAMERA_AVAILABLE = 1;
}
