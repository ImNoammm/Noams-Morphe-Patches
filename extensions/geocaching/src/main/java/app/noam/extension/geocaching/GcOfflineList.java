package app.noam.extension.geocaching;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.ArrayList;

/** "Add to list" saves the caches on the device, into a local list. */
public final class GcOfflineList {
    private GcOfflineList() {}

    /** Saves the caches on the device (downloading the ones not saved yet) and adds them to a list. */
    private static void save(Activity activity, ArrayList<String> codes, String listCode) {
        final ArrayList<String> toSave = new ArrayList<>();
        int already = 0;
        for (String c : codes) {
            if (GcLocalLists.contains(listCode, c)) already++;
            else toSave.add(c);
        }
        String listName = GcLocalLists.name(listCode);
        if (toSave.isEmpty()) {
            Utils.toast("Already in " + listName);
            activity.finish();
            return;
        }
        final int alreadyCount = already;
        Utils.toast(toSave.size() == 1
            ? "Saving " + toSave.get(0) + " to " + listName + "..."
            : "Saving " + toSave.size() + " caches to " + listName + "...");
        new Thread(() -> {
            int ok = 0;
            for (String c : toSave) {
                if (GcOfflineStore.isDownloaded(c) || GcOfflineStore.download(c)) {
                    GcLocalLists.add(listCode, c);
                    ok++;
                }
            }
            String msg = ok == toSave.size()
                ? (ok == 1 ? "Saved to " : ok + " saved to ") + listName
                : ok + "/" + toSave.size() + " saved (some failed)";
            if (alreadyCount > 0) msg += " (" + alreadyCount + " already there)";
            Utils.toast(msg);
            GcOfflineListUi.refresh();
        }).start();
        activity.finish();
    }

    /** Called from AddGeocacheToListActivity.onCreate; false lets the app's own screen run. */
    /** Activities whose Add to list this class took over; their stock lifecycle setup never ran. */
    private static final java.util.Set<Activity> CLAIMED =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Lets AddGeocacheToListActivity's onStart, refresh and menu skip the setup onCreate never ran. */
    public static boolean isClaimed(Activity activity) {
        return CLAIMED.contains(activity);
    }

    public static boolean handleAddToList(Activity activity) {
        boolean claimed = handle(activity);
        if (claimed) CLAIMED.add(activity);
        return claimed;
    }

    private static boolean handle(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null) return false;
            ArrayList<String> codes = new ArrayList<>();

            String single = intent.getStringExtra("geoRefCode");
            if (single != null && !single.trim().isEmpty()) codes.add(single.trim());

            if (intent.hasExtra("geocacheRefList")) {
                Bundle extras = intent.getExtras();
                Object raw = extras != null ? extras.get("geocacheRefList") : null;
                if (raw instanceof CharSequence) {
                    for (String c : raw.toString().split(",")) {
                        c = c.trim();
                        if (!c.isEmpty()) codes.add(c);
                    }
                } else if (raw instanceof String[]) {
                    for (String c : (String[]) raw) {
                        if (c != null && !c.trim().isEmpty()) codes.add(c.trim());
                    }
                } else if (raw instanceof ArrayList) {
                    for (Object o : (ArrayList<?>) raw) {
                        if (o != null && !o.toString().trim().isEmpty()) codes.add(o.toString().trim());
                    }
                }
            }

            if (codes.isEmpty()) return false;

            org.json.JSONArray lists = GcLocalLists.all();
            if (lists.length() == 0) {
                save(activity, codes, GcLocalLists.ALL);
                return true;
            }
            String[] listCodes = new String[lists.length() + 1];
            String[] names = new String[lists.length() + 1];
            listCodes[0] = GcLocalLists.ALL;
            names[0] = "Offline caches only";
            for (int i = 0; i < lists.length(); i++) {
                listCodes[i + 1] = lists.optJSONObject(i).optString("code");
                names[i + 1] = lists.optJSONObject(i).optString("name", "List");
            }
            Utils.showDialog(activity, "Add to List", null, names,
                (dialog, which) -> save(activity, codes, listCodes[which]),
                null, null, null, dialog -> activity.finish());
            return true;
        } catch (Throwable t) {
            Utils.logError("add-to-list reroute failed", t);
            return false;
        }
    }
}
