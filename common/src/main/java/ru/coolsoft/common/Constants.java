package ru.coolsoft.common;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class Constants {
    public static final int AUTH_OK = 0;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({AUTH_DENIED_SERVER_ERROR, AUTH_DENIED_WRONG_CREDENTIALS, AUTH_DENIED_NOT_ALLOWED})
    public @interface AuthFailureCause{}
    public static final int AUTH_DENIED_SERVER_ERROR = 1;
    public static final int AUTH_DENIED_WRONG_CREDENTIALS = 2;
    public static final int AUTH_DENIED_NOT_ALLOWED = 3;

    public static final int UNUSED = -1;
    public static final int SIZEOF_INT = 4;
    public static final int SIZEOF_LONG = 8;

    public static final byte CAMERA_UNAVAILABLE = 0;
    public static final byte CAMERA_AVAILABLE = 1;
}
