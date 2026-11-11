package com.asoft.ios;

import android.app.Application;

/**
 * The app's process, before any of its screens or services: where the app notices how its process before ended
 * and catches its own crashes (AppExit), whichever part of it Android starts first (a screen, the setup's
 * service, adb's intents in a debug build).
 */
public class AsoftApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // the GPU check runs in a process of its own (GpuCheckService): a driver that crashes takes only that one,
        // and its check says so itself
        if (getPackageName().equals(Application.getProcessName())) {
            AppExit.onStart(this);
        }
    }
}
