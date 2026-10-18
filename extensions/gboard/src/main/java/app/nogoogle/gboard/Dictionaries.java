package app.nogoogle.gboard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Download dictionaries from Google" (off by default). Only English comes with Gboard: the other
 * languages' dictionaries (word suggestions, autocorrect) are downloaded from Google when a language is
 * used. With the switch on, Gboard's own downloader gets them through the network helper app, which for
 * this reaches only Google's dictionary folder. With it off, a language without a dictionary shows a hint.
 */
public final class Dictionaries {
    private static final String TAG = "NoGoogleDict";
    static final String FOLDER = "https://www.gstatic.com/android/keyboard/dictionarypack/";
    // A language's manifest, fetched before its dictionary: <metadata url>-perlang/<language tag>.json?v=...
    private static final String PER_LANGUAGE = "-perlang/";
    private static final Map<String, Boolean> BUNDLED = new ConcurrentHashMap<>(); // language tag: comes with Gboard
    private static final Set<String> HINTED = ConcurrentHashMap.newKeySet();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<InputMethodService> keyboard = new WeakReference<>(null); // main thread only
    private static String waiting; // main thread only: a hint for when the keyboard shows

    private Dictionaries() {
    }

    /** Patched in after Gboard looks up the version of a language's bundled dictionary (0: none). */
    public static void bundled(Object locale, int version) {
        if (locale instanceof Locale) BUNDLED.put(((Locale) locale).toLanguageTag(), version > 0);
    }

    /** Patched over the result of the downloader's INTERNET permission check (0: granted). */
    public static int permission(int granted, String url) {
        if (granted == PackageManager.PERMISSION_GRANTED || !isDictionary(url)) return granted;
        if (NoGoogleSettings.bool(NoGoogleSettings.DICTIONARY_DOWNLOADS)) return PackageManager.PERMISSION_GRANTED;
        String tag = language(url);
        if (tag != null && Boolean.FALSE.equals(BUNDLED.get(tag))) {
            hint(tag, "No word suggestions for " + Locale.forLanguageTag(tag).getDisplayName()
                    + ": turn on \"Download dictionaries from Google\" in No-Google settings");
        }
        return granted;
    }

    /** Patched in after the downloader creates its connection (not yet connected): dictionaries come through the helper. */
    public static HttpURLConnection connection(HttpURLConnection stock) {
        Context c = NoGoogleSettings.context();
        if (stock == null || c == null || !isDictionary(stock.getURL().toString())
                || c.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
                || !NoGoogleSettings.bool(NoGoogleSettings.DICTIONARY_DOWNLOADS)) {
            return stock;
        }
        Log.i(TAG, "downloading " + stock.getURL());
        return new HelperConnection(stock.getURL());
    }

    private static boolean isDictionary(String url) {
        return url != null && url.startsWith(FOLDER);
    }

    /** The language of a manifest URL, as Locale tags it, or null for any other file. */
    private static String language(String url) {
        int at = url.indexOf(PER_LANGUAGE);
        int end = url.indexOf(".json", at + 1);
        if (at < 0 || end <= at + PER_LANGUAGE.length()) return null;
        return Locale.forLanguageTag(url.substring(at + PER_LANGUAGE.length(), end)).toLanguageTag();
    }

    /** Patched in at the start of Gboard's onStartInputView: a hint that came while it was hidden shows now. */
    public static void keyboardShown(InputMethodService ime) {
        keyboard = new WeakReference<>(ime);
        if (waiting != null) MAIN.postDelayed(Dictionaries::showWaiting, 500); // once it is on screen
    }

    /**
     * A note on the keyboard, once per key while it runs: Android drops a keyboard's toasts while its
     * notifications are off (as they are until allowed).
     */
    private static void hint(String key, String message) {
        if (!HINTED.add(key)) return;
        Log.i(TAG, message);
        MAIN.post(() -> {
            waiting = message;
            showWaiting();
        });
    }

    private static void showWaiting() {
        InputMethodService ime = keyboard.get();
        Window w = ime == null || ime.getWindow() == null ? null : ime.getWindow().getWindow();
        View parent = w == null ? null : w.getDecorView();
        if (waiting == null || parent == null || !ime.isInputViewShown() || parent.getWindowToken() == null) return;
        float dp = ime.getResources().getDisplayMetrics().density;
        TextView note = new TextView(ime);
        note.setText(waiting);
        waiting = null;
        note.setTextColor(0xFFFFFFFF);
        note.setTextSize(14);
        note.setMaxWidth((int) (ime.getResources().getDisplayMetrics().widthPixels * 0.9f));
        note.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), (int) (10 * dp));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF0303134);
        background.setCornerRadius(18 * dp);
        note.setBackground(background);
        PopupWindow popup = new PopupWindow(note, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        note.setOnClickListener(v -> popup.dismiss());
        try {
            popup.showAtLocation(parent, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, (int) (8 * dp));
            MAIN.postDelayed(() -> {
                try {
                    popup.dismiss();
                } catch (RuntimeException ignored) { // the keyboard went away meanwhile
                }
            }, 7000);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot show the hint", e);
        }
    }

    /**
     * Gboard's downloader reads a dictionary through this as through its own connection: the network
     * helper's download stream (status, file size, then the body from the requested offset).
     */
    private static final class HelperConnection extends HttpURLConnection {
        private final Object lock = new Object();
        private ParcelFileDescriptor pfd; // guarded by lock; null once disconnected
        private InputStream body;
        private int code = -1;
        private long length = -1; // of the body, -1 if unknown

        HelperConnection(URL url) {
            super(url);
        }

        @Override
        public void connect() throws IOException {
            if (connected) return;
            long from = 0;
            String range = getRequestProperty("Range"); // "bytes=<offset>-" when resuming
            if (range != null && range.startsWith("bytes=") && range.endsWith("-")) {
                try {
                    from = Long.parseLong(range.substring(6, range.length() - 1));
                } catch (NumberFormatException ignored) {
                }
            }
            ParcelFileDescriptor p;
            try {
                p = ModelDownloads.open(url.toString(), from);
            } catch (IOException e) {
                // No helper, one that doesn't serve this keyboard, or one too old for this folder.
                String m = String.valueOf(e.getMessage());
                String fix = m.startsWith("blocked") ? "update" : m.startsWith("reinstall") ? "reinstall"
                        : m.startsWith("install") ? "install" : null;
                if (fix != null) hint("helper", "To download dictionaries, " + fix + " the network helper app in No-Google settings");
                throw e;
            }
            synchronized (lock) {
                pfd = p;
            }
            try {
                DataInputStream in = new DataInputStream(new FileInputStream(p.getFileDescriptor()));
                code = in.readInt();
                long total = in.readLong();
                length = code == 200 ? total : code == 206 && total > 0 ? total - from : -1;
                body = new Body(in, this);
            } catch (IOException e) {
                disconnect();
                throw e; // the site couldn't be reached: Gboard tries again later
            }
            connected = true;
        }

        @Override
        public int getResponseCode() throws IOException {
            connect();
            return code;
        }

        @Override
        public String getHeaderField(String name) {
            return connected && length >= 0 && "Content-Length".equalsIgnoreCase(name) ? String.valueOf(length) : null;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            connect();
            if (code >= 400) throw new FileNotFoundException(url.toString());
            return body;
        }

        @Override
        public void disconnect() {
            ParcelFileDescriptor p;
            synchronized (lock) {
                p = pfd;
                pfd = null;
            }
            if (p == null) return;
            try {
                ModelDownloads.close(p);
            } catch (IOException ignored) {
            }
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        private void checkError() throws IOException {
            ParcelFileDescriptor p;
            synchronized (lock) {
                p = pfd;
            }
            if (p != null) p.checkError();
        }
    }

    /** A dictionary's body; its end reports a transfer the helper broke off. */
    private static final class Body extends FilterInputStream {
        private final HelperConnection connection;

        Body(InputStream in, HelperConnection connection) {
            super(in);
            this.connection = connection;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b < 0) connection.checkError();
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n < 0) connection.checkError();
            return n;
        }

        @Override
        public void close() {
            connection.disconnect();
        }
    }
}
