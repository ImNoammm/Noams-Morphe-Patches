package app.noam.extension.geocaching;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.lang.ref.WeakReference;

public final class Utils {
    public static final String TAG = "MorpheGeocaching";

    private static Application application;
    private static WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Utils() {}

    /** Called by the patches at the start of GeoApplication.onCreate(). */
    public static void onApplicationCreate(Application app) {
        if (application != null || app == null) return;
        application = app;
        app.registerActivityLifecycleCallbacks(new ActivityTracker());
    }

    /**
     * The app's failure result for "no connection" (wk9 of NetworkFailure$f). Callers that get it
     * keep their local data instead of replacing it with a server answer.
     */
    public static Object offlineFailure() {
        try {
            Object offline = Class.forName("com.groundspeak.geocaching.intro.network.utils.NetworkFailure$f")
                .getField("a").get(null);
            return Class.forName("wk9").getDeclaredConstructor(Object.class).newInstance(offline);
        } catch (Throwable t) {
            logError("Offline result unavailable", t);
            return null;
        }
    }

    /** The app's own Material dialog, so pickers and confirmations look like the rest of the app. */
    public static void showDialog(Context context, CharSequence title, CharSequence message, CharSequence[] items,
                                  android.content.DialogInterface.OnClickListener onItem,
                                  CharSequence positive, android.content.DialogInterface.OnClickListener onPositive,
                                  CharSequence negative, android.content.DialogInterface.OnCancelListener onCancel) {
        try {
            Class<?> builderClass = Class.forName("com.google.android.material.dialog.MaterialAlertDialogBuilder");
            Object builder = builderClass.getConstructor(Context.class).newInstance(context);
            builderClass.getMethod("setTitle", CharSequence.class).invoke(builder, title);
            if (message != null) builderClass.getMethod("setMessage", CharSequence.class).invoke(builder, message);
            if (items != null) builderClass.getMethod("setItems", CharSequence[].class,
                android.content.DialogInterface.OnClickListener.class).invoke(builder, items, onItem);
            if (positive != null) builderClass.getMethod("setPositiveButton", CharSequence.class,
                android.content.DialogInterface.OnClickListener.class).invoke(builder, positive, onPositive);
            if (negative != null) builderClass.getMethod("setNegativeButton", CharSequence.class,
                android.content.DialogInterface.OnClickListener.class).invoke(builder, negative,
                (android.content.DialogInterface.OnClickListener) (d, w) -> d.cancel());
            if (onCancel != null) builderClass.getMethod("setOnCancelListener",
                android.content.DialogInterface.OnCancelListener.class).invoke(builder, onCancel);
            ((android.app.Dialog) builderClass.getMethod("create").invoke(builder)).show();
        } catch (Throwable t) {
            logError("Material dialog unavailable, using the plain one", t);
            android.app.AlertDialog.Builder plain = new android.app.AlertDialog.Builder(context).setTitle(title);
            if (message != null) plain.setMessage(message);
            if (items != null) plain.setItems(items, onItem);
            if (positive != null) plain.setPositiveButton(positive, onPositive);
            if (negative != null) plain.setNegativeButton(negative, (d, w) -> d.cancel());
            if (onCancel != null) plain.setOnCancelListener(onCancel);
            plain.show();
        }
    }

    public static Context context() {
        return application;
    }

    public static Activity resumedActivity() {
        return resumedActivity.get();
    }

    public static void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else MAIN.post(runnable);
    }

    public static SharedPreferences prefs() {
        Context c = application;
        if (c == null) return null;
        return c.getSharedPreferences("gc_live_session", Context.MODE_PRIVATE);
    }

    public static void toast(CharSequence text) {
        if (application == null || text == null || text.length() == 0) return;
        runOnMain(() -> {
            try {
                Toast.makeText(application, text, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                logError("toast failed", t);
            }
        });
    }

    public static int dp(float value) {
        Context c = application;
        float density = c == null ? 2.75f : c.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    public static void log(String message) {
        Log.d(TAG, message);
    }

    public static void logError(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
    }

    private static final class ActivityTracker implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

        @Override
        public void onActivityStarted(Activity activity) {}

        @Override
        public void onActivityResumed(Activity activity) {
            resumedActivity = new WeakReference<>(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (resumedActivity.get() == activity) resumedActivity = new WeakReference<>(null);
        }

        @Override
        public void onActivityStopped(Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

        @Override
        public void onActivityDestroyed(Activity activity) {}
    }
}
