package app.nogoogle.gboard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.util.List;

/**
 * The app icon (Gboard's LauncherActivity, a transparent screen) walks through the setup, each time
 * from the first step still missing: turn the keyboard on in Android's keyboard list (the picker
 * follows by itself), choose it in Android's keyboard picker, and once both are done open its
 * settings. Gboard's own launcher waits for Google's Firebase first, so without it the icon did nothing.
 */
public final class KeyboardPicker extends ContentObserver implements ViewTreeObserver.OnWindowFocusChangeListener {
    // Android closes the picker when the keyboard moves to a newly focused window, which ours is.
    private static final long SETTLE_MS = 400;
    private static final long QUICK_CLOSE_MS = 600; // closed faster than a person could: ask again
    private static final long PICKER_TIMEOUT_MS = 1500;
    private static final int MAX_TRIES = 3;
    private static KeyboardPicker current; // main thread only

    private final Activity activity;
    private final InputMethodManager imm;
    private final View decor;
    private boolean waitingForEnable; // sent to Android's keyboard list
    private boolean watching;
    private boolean wantPicker;
    private boolean scheduled;
    private int tries;
    private long askedAt; // uptime of the open picker request, 0 when none
    private boolean pickerUp;

    private KeyboardPicker(Activity activity, InputMethodManager imm) {
        super(new Handler(Looper.getMainLooper()));
        this.activity = activity;
        this.imm = imm;
        decor = activity.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnWindowFocusChangeListener(this);
    }

    /** Patched in right after LauncherActivity.onResume's super call; Gboard's launcher code is skipped. */
    public static void onLauncherResume(Activity a) {
        InputMethodManager imm = a.getSystemService(InputMethodManager.class);
        InputMethodInfo me = imm == null ? null : ours(a, imm.getInputMethodList());
        if (me == null) {
            a.finishAndRemoveTask();
            return;
        }
        if (current == null || current.activity != a) current = new KeyboardPicker(a, imm);
        current.next(me);
    }

    private void next(InputMethodInfo me) {
        String label = me.loadLabel(activity.getPackageManager()).toString();
        if (ours(activity, imm.getEnabledInputMethodList()) == null) {
            if (waitingForEnable) { // back from the keyboard list without turning it on
                close();
                return;
            }
            waitingForEnable = true;
            watching = true;
            activity.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS), false, this);
            Toast.makeText(activity, "Enable " + label, Toast.LENGTH_LONG).show();
            // In this task, so that Back (or turning it on, see onChange) comes back here.
            if (!start(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))) close();
            return;
        }
        waitingForEnable = false;
        stopWatching();
        String selected = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (!me.getId().equals(selected)) {
            if (!wantPicker) {
                wantPicker = true;
                Toast.makeText(activity, "Choose " + label, Toast.LENGTH_LONG).show();
            }
            if (activity.hasWindowFocus()) schedule(); // otherwise once the window gets focus
            return;
        }
        if (wantPicker || me.getSettingsActivity() == null) { // chosen meanwhile: done
            close();
            return;
        }
        detach();
        start(new Intent().setClassName(me.getPackageName(), me.getSettingsActivity()));
        activity.finish(); // not the task: the settings opened in it
    }

    /** The keyboard was turned on in Android's keyboard list: back here, where the picker comes up. */
    @Override
    public void onChange(boolean selfChange) {
        if (!waitingForEnable || activity.isFinishing() || ours(activity, imm.getEnabledInputMethodList()) == null) return;
        activity.startActivity(new Intent(activity, activity.getClass()).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    /** Android only shows the picker for the focused window, once the keyboard has settled on it. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (!wantPicker) return;
        if (!hasFocus) {
            if (askedAt != 0) pickerUp = true;
            return;
        }
        if (!pickerUp) {
            if (askedAt == 0) schedule();
            return;
        }
        pickerUp = false;
        boolean quick = SystemClock.uptimeMillis() - askedAt < QUICK_CLOSE_MS;
        askedAt = 0;
        if (!quick) {
            close(); // a keyboard was picked or the picker closed; the next tap asks again if needed
        } else if (tries < MAX_TRIES) {
            schedule();
        } else {
            keyboardListInstead();
        }
    }

    private void schedule() {
        if (scheduled) return;
        scheduled = true;
        decor.postDelayed(this::ask, SETTLE_MS);
    }

    private void ask() {
        scheduled = false;
        if (activity.isFinishing() || !activity.hasWindowFocus()) return;
        int attempt = ++tries;
        askedAt = SystemClock.uptimeMillis();
        imm.showInputMethodPicker();
        decor.postDelayed(() -> checkShown(attempt), PICKER_TIMEOUT_MS);
    }

    /** No picker came up for this request: the keyboard list instead of an invisible screen. */
    private void checkShown(int attempt) {
        if (attempt == tries && askedAt != 0 && !pickerUp && !activity.isFinishing() && activity.hasWindowFocus()) {
            keyboardListInstead();
        }
    }

    private void keyboardListInstead() {
        detach();
        start(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        activity.finishAndRemoveTask();
    }

    /** A ROM without that settings screen gets a hint instead of a crash. */
    private boolean start(Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, "Open Android's keyboard settings to set up this keyboard", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Ends the setup screen, and with it the keyboard list it may have opened in its task. */
    private void close() {
        detach();
        activity.finishAndRemoveTask();
    }

    private void stopWatching() {
        if (!watching) return;
        watching = false;
        activity.getContentResolver().unregisterContentObserver(this);
    }

    private void detach() {
        stopWatching();
        decor.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
        if (current == this) current = null;
    }

    private static InputMethodInfo ours(Context c, List<InputMethodInfo> list) {
        for (InputMethodInfo i : list) {
            if (c.getPackageName().equals(i.getPackageName())) return i;
        }
        return null;
    }
}
