package app.noam.extension.geocaching;

import android.content.SharedPreferences;

/**
 * Log Templates are stored by the server only for Premium members. These stand in for the app's
 * TemplateApi calls so the repo keeps every template in its own local database instead: writes
 * succeed locally, and the list read reports "offline" so the repo serves the local copy.
 */
public final class GcLocalTemplates {
    private static final String NEXT_ID = "local_template_next_id";

    private GcLocalTemplates() {}

    public static Object create(String name, String body) throws Exception {
        SharedPreferences prefs = Utils.prefs();
        int id = prefs.getInt(NEXT_ID, 1_900_000_000);
        prefs.edit().putInt(NEXT_ID, id + 1).apply();
        Object template = Class.forName("cq6").getDeclaredConstructor(int.class, String.class, String.class)
            .newInstance(id, name, body);
        return success(template);
    }

    public static Object update(int id, String name, String body) throws Exception {
        Object template = Class.forName("cq6").getDeclaredConstructor(int.class, String.class, String.class)
            .newInstance(id, name, body);
        return success(template);
    }

    public static Object delete(int id) throws Exception {
        return success(Class.forName("szb").getField("a").get(null));
    }

    public static Object getAll() {
        return Utils.offlineFailure();
    }

    private static Object success(Object value) throws Exception {
        return Class.forName("xk9").getDeclaredConstructor(Object.class).newInstance(value);
    }
}
