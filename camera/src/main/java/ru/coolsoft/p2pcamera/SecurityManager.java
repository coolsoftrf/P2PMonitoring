package ru.coolsoft.p2pcamera;

import static android.content.Context.MODE_PRIVATE;

import android.app.Activity;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SecurityManager {
    private static final List<SecurityManager> instances = new ArrayList<>();
    private final static String USER_ACCESS_PREFIX = "access";
    private final static String USER_SHADOW_PREFIX = "shadow";

    private final WeakReference<Activity> activity;
    private final SharedPreferences preferences;

    private SecurityManager(Activity activity) {
        this.activity = new WeakReference<>(activity);
        preferences = activity.getPreferences(MODE_PRIVATE);
    }

    public static SecurityManager getInstance(Activity activity) {
        Iterator<SecurityManager> iterator = instances.iterator();
        while (iterator.hasNext()) {
            SecurityManager sm = iterator.next();
            WeakReference<Activity> wr = sm.activity;
            Activity key = wr.get();
            if (key == null) {
                iterator.remove();
            }
            if (key == activity) {
                return sm;
            }
        }

        SecurityManager sm = new SecurityManager(activity);
        instances.add(sm);
        return sm;
    }

    public UserAccess getUserAccess(String user) {
        if (user == null) {
            return UserAccess.DENIED;
        }
        return UserAccess.valueOf(preferences.getString(
                getUserKey(USER_ACCESS_PREFIX, user),
                UserAccess.NOT_DECIDED.toString())
        );
    }

    public void setUserAccess(String user, UserAccess access) {
        preferences.edit()
                .putString(getUserKey(USER_ACCESS_PREFIX, user), access.toString())
                .apply();
    }

    @Nullable
    public String getUserShadow(String user) {
        if (user == null) {
            return null;
        }
        return preferences.getString(
                getUserKey(USER_SHADOW_PREFIX, user),
                null);
    }

    public void setUserShadow(String user, String shadow) {
        preferences.edit()
                .putString(getUserKey(USER_SHADOW_PREFIX, user), shadow)
                .apply();
    }

    @NonNull
    private String getUserKey(String prefix, String user) {
        return String.format("%s.%s", prefix, user);
    }

    public enum UserAccess {
        GRANTED,
        DENIED,
        NOT_DECIDED
    }
}
