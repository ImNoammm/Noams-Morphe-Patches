package app.noam.extension.geocaching;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every cache the map has shown, kept on the device, so areas you've looked at still show their
 * caches without a connection. Newest first; the oldest are dropped past MAX.
 */
final class GcMapCache {
    private static final int MAX = 6000;
    private static final Object LOCK = new Object();
    private static LinkedHashMap<String, JSONObject> caches;
    private static boolean writing;

    private GcMapCache() {}

    private static File file() {
        return new File(Utils.context().getFilesDir(), "map_caches.json");
    }

    private static Map<String, JSONObject> loaded() {
        if (caches != null) return caches;
        caches = new LinkedHashMap<>(1024, 0.75f, true);
        try {
            File f = file();
            if (f.exists()) {
                try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    JSONArray array = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject o = array.optJSONObject(i);
                        if (o != null && o.has("code")) caches.put(o.optString("code"), o);
                    }
                }
            }
        } catch (Throwable t) {
            Utils.logError("Map cache unreadable", t);
        }
        return caches;
    }

    /** Remembers search results and writes them out in the background. */
    static void store(JSONArray results) {
        synchronized (LOCK) {
            Map<String, JSONObject> map = loaded();
            for (int i = 0; i < results.length(); i++) {
                JSONObject o = results.optJSONObject(i);
                if (o != null && o.has("code")) map.put(o.optString("code"), o);
            }
            while (map.size() > MAX) map.remove(map.keySet().iterator().next());
            if (writing) return;
            writing = true;
        }
        new Thread(GcMapCache::write).start();
    }

    private static void write() {
        String json;
        synchronized (LOCK) {
            writing = false;
            json = new JSONArray(new ArrayList<>(loaded().values())).toString();
        }
        File tmp = new File(file().getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Utils.logError("Map cache not saved", t);
            return;
        }
        if (!tmp.renameTo(file())) Utils.log("Map cache rename failed");
    }

    /** The stored caches closest to origin, nearest first. */
    static List<JSONObject> nearest(double[] origin, int limit) {
        List<JSONObject> all;
        synchronized (LOCK) {
            all = new ArrayList<>(loaded().values());
        }
        List<double[]> keyed = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            JSONObject posted = all.get(i).optJSONObject("postedCoordinates");
            if (posted == null) continue;
            double dLat = posted.optDouble("latitude") - origin[0];
            double dLon = (posted.optDouble("longitude") - origin[1]) * Math.cos(Math.toRadians(origin[0]));
            keyed.add(new double[]{dLat * dLat + dLon * dLon, i});
        }
        Collections.sort(keyed, (a, b) -> Double.compare(a[0], b[0]));
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < keyed.size() && i < limit; i++) result.add(all.get((int) keyed.get(i)[1]));
        return result;
    }
}
