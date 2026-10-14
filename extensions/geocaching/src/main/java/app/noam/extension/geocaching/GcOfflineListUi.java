package app.noam.extension.geocaching;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local list data for the stock Lists hub, plus its offline cache rows.
 */
public final class GcOfflineListUi {
    private static final int BG = 0xFF1B1B1B;
    private static final int CARD = 0xFF262626;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int SUB = 0xFFAAAAAA;
    private static final int HEART = 0xFFE91E8C;

    private static final String[] SORTS = {
        "Distance from me", "Cache name", "Favorite points",
    };
    private static int sortIndex = 0;
    private static final AtomicBoolean imagesMigrationAttempted = new AtomicBoolean();

    // Live references to the currently-shown list, so it can refresh when caches are added/removed.
    private static java.lang.ref.WeakReference<Context> ctxRef = new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<LinearLayout> listRef = new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<TextView> countRef = new java.lang.ref.WeakReference<>(null);
    private static String shownList = GcLocalLists.ALL;

    private GcOfflineListUi() {}

    /** Repopulates the visible local list, if one is on screen. Safe to call from any thread. */
    public static void refresh() {
        Utils.runOnMain(() -> {
            Context ctx = ctxRef.get();
            LinearLayout list = listRef.get();
            TextView count = countRef.get();
            if (ctx != null && list != null && count != null) populate(ctx, list, count, shownList);
        });
    }

    /** Feeds the local lists to the app's own Lists hub paging source. */
    public static Object createListPage(Object pagingSource) {
        try {
            Class<?> statusClass = Class.forName("com.geocaching.api.list.type.ListInfo$DownloadStatus");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object offline = Enum.valueOf((Class<? extends Enum>) statusClass, "OFFLINE");
            String username = Utils.prefs().getString("gc_username", "");
            java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            dateFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String now = dateFormat.format(new java.util.Date());
            java.util.List<Object> infos = new ArrayList<>();
            infos.add(listInfo(GcLocalLists.ALL, "Offline caches", "Every cache saved on this device",
                GcOfflineStore.count(), username, now, offline, statusClass));
            JSONArray lists = GcLocalLists.all();
            for (int i = 0; i < lists.length(); i++) {
                JSONObject list = lists.optJSONObject(i);
                if (list == null) continue;
                JSONArray caches = list.optJSONArray("caches");
                String created = dateFormat.format(new java.util.Date(list.optLong("created", System.currentTimeMillis())));
                infos.add(listInfo(list.optString("code"), list.optString("name", "List"), "Saved on this device",
                    caches == null ? 0 : caches.length(), username, created, offline, statusClass));
            }
            java.lang.reflect.Field callback = pagingSource.getClass().getDeclaredField("c");
            callback.setAccessible(true);
            Object listener = callback.get(pagingSource);
            listener.getClass().getMethod("invoke", Object.class).invoke(listener, Integer.valueOf(infos.size()));
            if (imagesMigrationAttempted.compareAndSet(false, true) && isOnline()) {
                new Thread(GcOfflineStore::cacheMissingImages).start();
            } else if (!isOnline()) {
                imagesMigrationAttempted.set(false);
            }
            return Class.forName("androidx.paging.r").getConstructor(java.util.List.class, Integer.class)
                .newInstance(infos, null);
        } catch (Throwable t) {
            Utils.logError("Local list page failed", t);
            return null;
        }
    }

    private static Object listInfo(String code, String name, String description, int count, String owner,
                                   String date, Object offline, Class<?> statusClass) throws Exception {
        return Class.forName("qc6").getConstructor(String.class, String.class,
            String.class, String.class, boolean.class, boolean.class, boolean.class,
            boolean.class, int.class, String.class, String.class, int.class, statusClass, long.class)
            .newInstance(code, name, description, owner, false, false, false, true, count, date, date, 2, offline,
                System.currentTimeMillis());
    }

    public static boolean showOfflineList(Activity activity) {
        if (!isOfflineList(activity)) {
            return false;
        }
        activity.setContentView(createView(activity, listCode(activity)));
        return true;
    }

    public static boolean isOfflineList(Activity activity) {
        return GcLocalLists.isLocal(listCode(activity));
    }

    private static String listCode(Activity activity) {
        return activity.getIntent().getStringExtra("GeocacheListDetailsActivity.LIST_CODE");
    }

    /** Keeps the stock list's subscription cleanup while the local list has none to release. */
    public static void finishListStop(Activity activity) {
        if (isOfflineList(activity)) return;
        try {
            Object subscriptions = activity.getClass().getMethod("F").invoke(activity);
            subscriptions.getClass().getMethod("unsubscribe").invoke(subscriptions);
        } catch (Throwable t) {
            Utils.logError("Stock list cleanup failed", t);
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    public static boolean isOnline() {
        ConnectivityManager manager = (ConnectivityManager) Utils.context().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities = manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static View createView(Activity ctx, String listCode) {

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Utils.dp(16);
        header.setPadding(pad, pad, pad, Utils.dp(8));

        TextView title = new TextView(ctx);
        title.setText("‹  " + GcLocalLists.name(listCode));
        title.setTextColor(TEXT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setOnClickListener(v -> ctx.finish());
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        header.addView(title, titleLp);

        Spinner sortSpinner = new Spinner(ctx);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, SORTS);
        sortSpinner.setAdapter(adapter);
        sortSpinner.setSelection(sortIndex);
        header.addView(sortSpinner);
        if (!GcLocalLists.ALL.equals(listCode)) {
            Button delete = new Button(ctx);
            delete.setText("Delete");
            delete.setTextColor(SUB);
            delete.setBackgroundColor(Color.TRANSPARENT);
            delete.setOnClickListener(v -> Utils.showDialog(ctx,
                "Delete \"" + GcLocalLists.name(listCode) + "\"?",
                "The List is removed. Its caches stay saved in Offline caches.", null, null,
                "Delete", (d, w) -> {
                    GcLocalLists.delete(listCode);
                    ctx.finish();
                }, "Cancel", null));
            header.addView(delete);
        }
        root.addView(header);

        TextView count = new TextView(ctx);
        count.setTextColor(SUB);
        count.setPadding(pad, 0, pad, Utils.dp(8));
        root.addView(count);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll);

        final Context fctx = ctx;
        ctxRef = new java.lang.ref.WeakReference<>(fctx);
        shownList = listCode;
        listRef = new java.lang.ref.WeakReference<>(list);
        countRef = new java.lang.ref.WeakReference<>(count);
        // Refresh whenever the tab's view is (re)attached, e.g. returning to the Lists tab.
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                populate(fctx, list, count, listCode);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {}
        });
        populate(fctx, list, count, listCode);
        sortSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                sortIndex = position;
                populate(fctx, list, count, listCode);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        return root;
    }

    private static void populate(Context ctx, LinearLayout list, TextView count, String listCode) {
        list.removeAllViews();
        ArrayList<JSONObject> caches = new ArrayList<>();
        java.util.List<String> members = GcLocalLists.caches(listCode);
        if (members == null) {
            JSONArray all = GcOfflineStore.all();
            for (int i = 0; i < all.length(); i++) caches.add(all.optJSONObject(i));
        } else {
            for (String code : members) {
                JSONObject saved = GcOfflineStore.load(code);
                if (saved != null) caches.add(saved);
            }
        }
        sort(caches);

        count.setText(caches.size() == 1 ? "1 cache" : caches.size() + " caches");

        if (caches.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("No caches saved yet.\nUse \"Add to list\" on any cache to save it here.");
            empty.setTextColor(SUB);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Utils.dp(48), 0, 0);
            list.addView(empty);
            return;
        }
        for (JSONObject o : caches) list.addView(row(ctx, o, list, count, listCode));
    }

    private static View row(Context ctx, JSONObject o, LinearLayout list, TextView count, String listCode) {
        final String code = o.optString("code", "");
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundColor(CARD);
        int m = Utils.dp(8), p = Utils.dp(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(m, m / 2, m, m / 2);
        card.setLayoutParams(lp);
        card.setPadding(p, p, p, p);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(makeText(ctx, o.optString("name", code), TEXT, 16, true));
        String sub = code + "   D" + trimNum(o.optDouble("difficulty", 0))
            + " / T" + trimNum(o.optDouble("terrain", 0))
            + "   " + typeName(o.optInt("geocacheType", 2))
            + " · " + sizeName(o.optInt("containerType", 8));
        texts.addView(makeText(ctx, sub, SUB, 13, false));
        int fav = o.optInt("favoritePoints", 0);
        if (fav > 0) texts.addView(makeText(ctx, "♥ " + fav, HEART, 12, false));
        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        Button remove = new Button(ctx);
        remove.setText("✕");
        remove.setTextColor(SUB);
        remove.setBackgroundColor(Color.TRANSPARENT);
        remove.setOnClickListener(v -> {
            GcLocalLists.remove(listCode, code);
            Utils.toast("Removed " + code);
            populate(ctx, list, count, listCode);
        });
        card.addView(remove);

        card.setOnClickListener(v -> openCache(ctx, code));
        return card;
    }

    private static void openCache(Context ctx, String code) {
        try {
            Intent intent = new Intent();
            intent.setClassName(ctx, "com.groundspeak.geocaching.intro.geocachedetails.GeocacheDetailsActivity2");
            intent.putExtra("geocacheRefCode", code);
            intent.putExtra("analyticsSource", "Local List");
            intent.putExtra("refresh", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Utils.logError("open cache from local list failed", t);
        }
    }

    private static void sort(ArrayList<JSONObject> caches) {
        Comparator<JSONObject> cmp;
        switch (sortIndex) {
            case 1: cmp = (a, b) -> a.optString("name", "").compareToIgnoreCase(b.optString("name", "")); break;
            case 2: cmp = (a, b) -> Integer.compare(b.optInt("favoritePoints", 0), a.optInt("favoritePoints", 0)); break;
            default:
                Location location = lastLocation();
                cmp = (a, b) -> Double.compare(distance(a, location), distance(b, location));
                break;
        }
        Collections.sort(caches, cmp);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private static Location lastLocation() {
        try {
            LocationManager manager = (LocationManager) Utils.context().getSystemService(Context.LOCATION_SERVICE);
            Location latest = null;
            if (manager != null) for (String provider : manager.getProviders(true)) {
                Location location = manager.getLastKnownLocation(provider);
                if (location != null && (latest == null || location.getTime() > latest.getTime())) latest = location;
            }
            return latest;
        } catch (SecurityException e) {
            return null;
        }
    }

    private static double distance(JSONObject cache, Location here) {
        double lat = cache.optDouble("latitude", 0), lon = cache.optDouble("longitude", 0);
        if (here == null || lat == 0 || lon == 0) return Double.MAX_VALUE;
        float[] result = new float[1];
        Location.distanceBetween(here.getLatitude(), here.getLongitude(), lat, lon, result);
        return result[0];
    }

    private static TextView makeText(Context ctx, String s, int color, int sizeSp, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static String trimNum(double d) {
        if (d == Math.floor(d)) return String.valueOf((int) d);
        return String.valueOf(d);
    }

    private static String typeName(int id) {
        switch (id) {
            case 2: return "Traditional";
            case 3: return "Multi-cache";
            case 4: return "Virtual";
            case 5: return "Letterbox";
            case 6: return "Event";
            case 8: return "Mystery";
            case 11: return "Webcam";
            case 137: return "Earthcache";
            case 1858: return "Wherigo";
            default: return "Cache";
        }
    }

    private static String sizeName(int id) {
        switch (id) {
            case 2: return "Micro";
            case 3: return "Regular";
            case 4: return "Large";
            case 8: return "Small";
            case 5: return "Virtual";
            default: return "Other";
        }
    }
}
