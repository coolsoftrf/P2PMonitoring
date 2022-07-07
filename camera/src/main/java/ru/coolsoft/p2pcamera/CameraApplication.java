package ru.coolsoft.p2pcamera;

import android.app.Application;
import android.content.Context;

public class CameraApplication extends Application {
    private static Application sApp;

    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
    }

    public static Context getAppContext(){
        return sApp.getApplicationContext();
    }
}
