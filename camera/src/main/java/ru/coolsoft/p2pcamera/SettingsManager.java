package ru.coolsoft.p2pcamera;

import static ru.coolsoft.common.Defaults.SERVER_PORT;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.Collection;
import java.util.Map;

public class SettingsManager {
    private static SettingsManager instance;
    private final static String USER_ACCESS_PREFIX = "access";
    private final static String USER_SHADOW_PREFIX = "shadow";
    private final static String USER_PREFIX_DELIMITER = ".";

    private final Context appContext;
    private final SharedPreferences preferences;

    private SettingsManager(Context context) {
        appContext = context.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
    }

    public static SettingsManager getInstance(Context context) {
        if (instance == null) {
            instance = new SettingsManager(context);
        }
        return instance;
    }

    public String getPort() {
        return preferences.getString(appContext.getString(R.string.pref_key_port), String.valueOf(SERVER_PORT));
    }

    public boolean isPortDefault() {
        return !preferences.contains(appContext.getString(R.string.pref_key_port));
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

    public void getUserAccessList(Collection<String> blackList, Collection<String> whiteList, Collection<String> others) {
        int splitStart = USER_SHADOW_PREFIX.length() + USER_PREFIX_DELIMITER.length();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(USER_SHADOW_PREFIX)) {
                String user = entry.getKey().substring(splitStart);
                Collection<String> list;
                switch (getUserAccess(user)) {
                    case GRANTED:
                        list = whiteList;
                        break;
                    case DENIED:
                        list = blackList;
                        break;
                    default:
                        list = others;
                }
                list.add(user);
            }
        }
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
        return String.format("%s%s%s", prefix, USER_PREFIX_DELIMITER, user);
    }

    public void removeUser(String user) {
        preferences.edit()
                .remove(getUserKey(USER_ACCESS_PREFIX, user))
                .remove(getUserKey(USER_SHADOW_PREFIX, user))
                .apply();
    }

    public enum UserAccess {
        GRANTED,
        DENIED,
        NOT_DECIDED
    }
}
