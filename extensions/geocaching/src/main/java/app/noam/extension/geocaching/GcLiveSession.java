package app.noam.extension.geocaching;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A geocaching.com website session, signed in through the site's own form like c:geo does. */
public final class GcLiveSession {
    private static final String TAG = Utils.TAG;
    private static final String SIGNIN_URL =
        "https://www.geocaching.com/account/signin?returnUrl=%2Faccount%2Fsettings%2Fhomelocation";
    private static final Pattern TOKEN_PATTERN =
        Pattern.compile("name=\"__RequestVerificationToken\" type=\"hidden\" value=\"([^\"]+)\"");

    private static final CookieManager COOKIE_MANAGER = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private static volatile boolean loggedIn = false;

    private GcLiveSession() {}

    /**
     * Called as the app sends its own username/password sign-in: geocaching.com takes the same
     * account, so the website session signs in with it and never needs to ask separately.
     */
    public static void rememberAppLogin(Object loginBody) {
        try {
            java.lang.reflect.Field handle = loginBody.getClass().getDeclaredField("a");
            java.lang.reflect.Field password = loginBody.getClass().getDeclaredField("b");
            handle.setAccessible(true);
            password.setAccessible(true);
            String user = (String) handle.get(loginBody), pass = (String) password.get(loginBody);
            if (user == null || user.isEmpty() || pass == null || pass.isEmpty()) return;
            SharedPreferences prefs = Utils.prefs();
            if (prefs == null) return;
            saveLogin(prefs, user, pass);
            synchronized (GcLiveSession.class) {
                loggedIn = false;
            }
        } catch (Throwable t) {
            Utils.logError("App sign-in not shared with the website", t);
        }
    }

    static synchronized boolean ensureSession() {
        if (loggedIn) return true;
        SharedPreferences prefs = Utils.prefs();
        if (prefs == null) return false;
        String username = prefs.getString("gc_username", null);
        String password = savedPassword(prefs);
        if (username == null || password == null) {
            String[] entered = promptForCredentials();
            if (entered == null) return false;
            username = entered[0];
            password = entered[1];
            saveLogin(prefs, username, password);
        }
        loggedIn = login(username, password);
        return loggedIn;
    }

    private static final String KEY_ALIAS = "geocaching_website_login";

    /** The password is kept encrypted with a key that never leaves the Android Keystore. */
    private static void saveLogin(SharedPreferences prefs, String username, String password) {
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keystoreKey());
            byte[] iv = cipher.getIV();
            byte[] sealed = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            byte[] stored = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, stored, 0, iv.length);
            System.arraycopy(sealed, 0, stored, iv.length, sealed.length);
            prefs.edit().putString("gc_username", username)
                .putString("gc_password_sealed", android.util.Base64.encodeToString(stored, android.util.Base64.NO_WRAP))
                .remove("gc_password").apply();
        } catch (Exception e) {
            Utils.logError("Website sign-in not saved", e);
        }
    }

    private static String savedPassword(SharedPreferences prefs) {
        String plain = prefs.getString("gc_password", null);
        if (plain != null) {
            saveLogin(prefs, prefs.getString("gc_username", ""), plain);
            return plain;
        }
        String sealed = prefs.getString("gc_password_sealed", null);
        if (sealed == null) return null;
        try {
            byte[] stored = android.util.Base64.decode(sealed, android.util.Base64.NO_WRAP);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keystoreKey(),
                new javax.crypto.spec.GCMParameterSpec(128, stored, 0, 12));
            return new String(cipher.doFinal(stored, 12, stored.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Utils.logError("Saved website sign-in unreadable", e);
            return null;
        }
    }

    private static javax.crypto.SecretKey keystoreKey() throws Exception {
        java.security.KeyStore store = java.security.KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (javax.crypto.SecretKey) store.getKey(KEY_ALIAS, null);
        javax.crypto.KeyGenerator generator = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }

    /** Blocks the calling (background) thread until the user submits the dialog, or cancels. */
    private static String[] promptForCredentials() {
        Activity activity = Utils.resumedActivity();
        if (activity == null) return null;
        CountDownLatch latch = new CountDownLatch(1);
        String[] result = new String[2];
        Utils.runOnMain(() -> {
            try {
                LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
                layout.setPadding(pad, pad, pad, pad);
                EditText userField = new EditText(activity);
                userField.setHint("geocaching.com username or email");
                EditText passField = new EditText(activity);
                passField.setHint("geocaching.com password");
                passField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                layout.addView(userField);
                layout.addView(passField);
                new AlertDialog.Builder(activity)
                    .setTitle("Sign in (same as c:geo)")
                    .setView(layout)
                    .setCancelable(false)
                    .setPositiveButton("Sign in", (dialog, which) -> {
                        result[0] = userField.getText().toString().trim();
                        result[1] = passField.getText().toString();
                        latch.countDown();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> latch.countDown())
                    .show();
            } catch (Throwable t) {
                Utils.logError("Credential prompt failed", t);
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (result[0] == null || result[0].isEmpty() || result[1] == null || result[1].isEmpty()) return null;
        return result;
    }

    private static boolean login(String username, String password) {
        try {
            String signinPage = get(SIGNIN_URL);
            Matcher matcher = TOKEN_PATTERN.matcher(signinPage);
            if (!matcher.find()) {
                Utils.log("No CSRF token on sign-in page");
                return false;
            }
            String token = matcher.group(1);
            String body = "__RequestVerificationToken=" + urlEncode(token)
                + "&ReturnUrl=" + urlEncode("/account/settings/homelocation")
                + "&UsernameOrEmail=" + urlEncode(username)
                + "&Password=" + urlEncode(password);
            post("https://www.geocaching.com/account/signin", body);
            for (java.net.HttpCookie cookie : COOKIE_MANAGER.getCookieStore().getCookies()) {
                if ("gspkauth".equalsIgnoreCase(cookie.getName())) return true;
            }
            Utils.log("Sign-in did not produce a session cookie");
            return false;
        } catch (Throwable t) {
            Utils.logError("Sign-in failed", t);
            return false;
        }
    }

    static String get(String url) throws IOException {
        HttpURLConnection conn = open(url, "GET");
        return readBody(conn);
    }

    static byte[] getImage(String url) throws IOException {
        HttpURLConnection conn = open(url, "GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        String type = conn.getContentType();
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            conn.disconnect();
            throw new IOException("Not an image: " + url);
        }
        if (conn.getContentLength() > 4 * 1024 * 1024) {
            conn.disconnect();
            throw new IOException("Image too large: " + url);
        }
        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > 4 * 1024 * 1024) throw new IOException("Image too large: " + url);
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static volatile String cachedBearer = null;
    private static volatile long bearerExpiresAt = 0;

    /** Bearer token for /api/proxy/* calls, fetched from the website's own token endpoint. */
    static synchronized String ensureToken() {
        if (cachedBearer != null && System.currentTimeMillis() < bearerExpiresAt) return cachedBearer;
        try {
            String json = get("https://www.geocaching.com/account/oauth/token");
            JSONObject obj = new JSONObject(json);
            String tokenType = obj.optString("token_type", "Bearer");
            String accessToken = obj.getString("access_token");
            long expiresInMs = obj.optLong("expires_in", 1800) * 1000L;
            cachedBearer = tokenType + " " + accessToken;
            bearerExpiresAt = System.currentTimeMillis() + (long) (expiresInMs * 0.8);
            return cachedBearer;
        } catch (Throwable t) {
            Utils.logError("Token fetch failed", t);
            return null;
        }
    }

    /** GET with an extra Authorization header, for /api/proxy/* calls. */
    static String getWithAuth(String url, String authorization) throws IOException {
        HttpURLConnection conn = open(url, "GET");
        conn.setRequestProperty("Authorization", authorization);
        return readBody(conn);
    }

    private static void post(String url, String body) throws IOException {
        HttpURLConnection conn = open(url, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        readBody(conn);
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        java.net.CookieHandler.setDefault(COOKIE_MANAGER);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) geocaching-morphe");
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        InputStream stream;
        try {
            stream = conn.getInputStream();
        } catch (IOException e) {
            stream = conn.getErrorStream();
            if (stream == null) throw e;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) sb.append(buffer, 0, read);
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }
}
