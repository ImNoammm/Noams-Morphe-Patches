package app.nogoogle.gboard;

import android.view.inputmethod.EditorInfo;

/** "Always use incognito mode": every text field asks for Gboard's incognito mode, as private browser tabs do. */
public final class Incognito {
    private Incognito() {
    }

    /** Patched in at the start of Gboard's onStartInput and onStartInputView. */
    public static void apply(EditorInfo info) {
        if (info != null && NoGoogleSettings.bool(NoGoogleSettings.ALWAYS_INCOGNITO)) {
            info.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        }
    }
}
