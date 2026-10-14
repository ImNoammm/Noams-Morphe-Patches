package app.noam.extension.geocaching;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Keeps the map looking exactly as you left it. Coming back from a cache, the camera isn't moved
 * (no new search). Coming back from another tab, the app rebuilds the map, so the last frame of the
 * old map is shown on top while the new one draws underneath, then fades away.
 */
@RequiresApi(29)
public final class GcMapView {
    private static final long COVER_MS = 1100;
    private static final Set<Object> PLACED = Collections.newSetFromMap(new WeakHashMap<>());
    private static Bitmap lastFrame;
    private static WeakReference<Activity> lastActivity = new WeakReference<>(null);

    private GcMapView() {}

    /** Called from MapLibreFragment.onResume: true only the first time a map view is shown. */
    public static boolean shouldPlaceCamera(Object fragment) {
        try {
            View mapView = mapView(fragment);
            if (mapView == null) return true;
            return PLACED.add(mapView);
        } catch (Throwable t) {
            Utils.logError("Map view lookup failed", t);
            return true;
        }
    }

    /** Called from MapLibreFragment.onPause, while the map is still on screen. */
    public static void capture(Object fragment) {
        try {
            View mapView = mapView(fragment);
            Activity activity = (Activity) fragment.getClass().getMethod("getActivity").invoke(fragment);
            if (!(mapView instanceof ViewGroup) || activity == null || mapView.getWidth() == 0) return;
            // MapLibre draws into its own surface, which a window copy would show as a hole.
            View surface = renderView((ViewGroup) mapView);
            if (surface instanceof TextureView) {
                Bitmap frame = ((TextureView) surface).getBitmap();
                if (frame != null) keep(frame, activity);
            } else if (surface instanceof SurfaceView && surface.getWidth() > 0) {
                Bitmap frame = Bitmap.createBitmap(surface.getWidth(), surface.getHeight(), Bitmap.Config.ARGB_8888);
                PixelCopy.request((SurfaceView) surface, frame, result -> {
                    if (result == PixelCopy.SUCCESS) keep(frame, activity);
                }, new Handler(Looper.getMainLooper()));
            }
        } catch (Throwable t) {
            Utils.logError("Map frame not kept", t);
        }
    }

    private static void keep(Bitmap frame, Activity activity) {

        lastFrame = frame;
        lastActivity = new WeakReference<>(activity);
    }

    private static View renderView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextureView || child instanceof SurfaceView) return child;
            if (child instanceof ViewGroup) {
                View found = renderView((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Called at the end of MapLibreFragment.onViewCreated: cover the fresh map with the last frame. */
    public static void cover(Object fragment) {
        try {
            View mapView = mapView(fragment);
            Activity activity = (Activity) fragment.getClass().getMethod("getActivity").invoke(fragment);
            Bitmap frame = lastFrame;
            if (mapView == null || frame == null || activity == null || lastActivity.get() != activity) return;
            ImageView cover = new ImageView(mapView.getContext());
            cover.setImageBitmap(frame);
            cover.setScaleType(ImageView.ScaleType.FIT_XY);
            // Beside the map view (which stays hidden until the new map is ready), under the map buttons.
            ViewGroup screen = (ViewGroup) mapView.getParent();
            if (screen == null) return;
            screen.addView(cover, screen.indexOfChild(mapView) + 1, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            cover.postDelayed(() -> cover.animate().alpha(0f).setDuration(250)
                .withEndAction(() -> screen.removeView(cover)), COVER_MS);
        } catch (Throwable t) {
            Utils.logError("Map cover failed", t);
        }
    }

    private static View mapView(Object fragment) throws Exception {
        Object binding = field(fragment, "J");
        return binding == null ? null : (View) field(binding, "D");
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
