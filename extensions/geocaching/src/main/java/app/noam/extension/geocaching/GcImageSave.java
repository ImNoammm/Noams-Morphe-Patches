package app.noam.extension.geocaching;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.RequiresApi;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/** A Download button in the log/cache photo viewer that saves the shown photo to Pictures/Geocaching. */
@RequiresApi(29)
public final class GcImageSave {
    private GcImageSave() {}

    /** Called from ImageGalleryActivity.onCreateOptionsMenu. */
    public static void addMenu(Activity activity, Menu menu) {
        MenuItem item = menu.add(Menu.NONE, Menu.NONE, 0, "Download");
        item.setIcon(android.R.drawable.ic_menu_save);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        item.setOnMenuItemClickListener(m -> {
            String url = currentUrl(activity);
            if (url == null) {
                Utils.toast("No photo to download");
            } else {
                Utils.toast("Downloading photo...");
                new Thread(() -> save(url)).start();
            }
            return true;
        });
    }

    private static String currentUrl(Activity activity) {
        try {
            List<?> images = (List<?>) field(activity, "I");
            Object pager = field(activity, "L");
            int index = (int) pager.getClass().getMethod("getCurrentItem").invoke(pager);
            if (images == null || index < 0 || index >= images.size()) return null;
            Object image = images.get(index);
            String best = null;
            for (Class<?> c = image.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    f.setAccessible(true);
                    String value = (String) f.get(image);
                    if (value == null || !value.startsWith("http")) continue;
                    boolean thumb = value.toLowerCase(Locale.ROOT).contains("thumb")
                        || f.getName().toLowerCase(Locale.ROOT).contains("thumb");
                    if (best == null || !thumb) best = value;
                    if (!thumb) return best;
                }
            }
            return best;
        } catch (Throwable t) {
            Utils.logError("Photo address unavailable", t);
            return null;
        }
    }

    private static void save(String url) {
        ContentResolver resolver = Utils.context().getContentResolver();
        Uri saved = null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            String type = conn.getContentType();
            String mime = type != null && type.startsWith("image/") ? type.split(";")[0] : "image/jpeg";
            String extension = mime.endsWith("png") ? ".png" : mime.endsWith("gif") ? ".gif" : mime.endsWith("webp") ? ".webp" : ".jpg";

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "geocaching-" + System.currentTimeMillis() + extension);
            values.put(MediaStore.Images.Media.MIME_TYPE, mime);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Geocaching");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            saved = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (saved == null) throw new IllegalStateException("No place to save the photo");
            try (InputStream in = conn.getInputStream(); OutputStream out = resolver.openOutputStream(saved)) {
                if (out == null) throw new IllegalStateException("Photo file not writable");
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            } finally {
                conn.disconnect();
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(saved, values, null, null);
            Utils.toast("Saved to Pictures/Geocaching");
        } catch (Throwable t) {
            if (saved != null) resolver.delete(saved, null, null);
            Utils.logError("Photo download failed", t);
            Utils.toast("Couldn't download the photo");
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
