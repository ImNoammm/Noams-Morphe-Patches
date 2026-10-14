package app.noam.extension.geocaching;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Replaces the app's /mobile/v1/users/statistics call, which only answers Premium members. The same
 * numbers (D/T grid, averages, streaks, found today) are computed from your own finds list, which
 * the app already reads for Basic members through /mobile/v1/users/{code}/finds.
 */
public final class GcUserStats {
    private static final String API = "https://api.groundspeak.com";
    private static final String PKG = "com.groundspeak.geocaching.intro.network.api.stats.";
    private static final int PAGE = 50;

    /** Finds per D/T square (difficulty-major, 9x9) from the last time statistics were computed. */
    private static volatile int[] lastGrid;

    private GcUserStats() {}

    /** Your D/T grid, computing it from your finds when Statistics hasn't been opened yet. */
    static int[] foundGrid() throws Exception {
        if (lastGrid == null) buildResponse();
        return lastGrid;
    }

    /** Called in place of StatsApi.getUserStatistics; resumes the caller with an xk9 result. */
    public static Object statsAsync(Object continuation) {
        new Thread(() -> {
            Object result;
            try {
                result = Class.forName("xk9").getDeclaredConstructor(Object.class).newInstance(buildResponse());
            } catch (Throwable t) {
                Utils.logError("Statistics from finds failed", t);
                result = Utils.offlineFailure();
            }
            try {
                continuation.getClass().getMethod("resumeWith", Object.class).invoke(continuation, result);
            } catch (Throwable t) {
                Utils.logError("Statistics resume failed", t);
            }
        }).start();
        try {
            Class<?> cls = Class.forName("kotlin.coroutines.intrinsics.CoroutineSingletons");
            for (Object c : cls.getEnumConstants()) {
                if (((Enum<?>) c).name().equals("COROUTINE_SUSPENDED")) return c;
            }
        } catch (Throwable t) {
            Utils.logError("COROUTINE_SUSPENDED lookup failed", t);
        }
        return null;
    }

    private static Object buildResponse() throws Exception {
        Object user = currentUser();
        String reference = (String) field(user, "w");
        String token = (String) user.getClass().getMethod("b").invoke(user);
        if (reference == null || token == null) throw new IllegalStateException("Not signed in");

        int[] grid = new int[81];
        double difficultySum = 0, terrainSum = 0;
        int counted = 0;
        TreeSet<String> days = new TreeSet<>();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());

        int skip = 0, total = Integer.MAX_VALUE;
        while (skip < total) {
            JSONObject page = new JSONObject(get(API + "/mobile/v1/users/" + reference + "/finds?referenceCode="
                + reference + "&take=" + PAGE + "&skip=" + skip + "&sort=recency", token));
            total = page.optInt("total", 0);
            JSONArray data = page.optJSONArray("data");
            if (data == null || data.length() == 0) break;
            for (int i = 0; i < data.length(); i++) {
                JSONObject cache = data.getJSONObject(i);
                double d = cache.optDouble("difficulty", 0), t = cache.optDouble("terrain", 0);
                if (d >= 1 && d <= 5 && t >= 1 && t <= 5) {
                    grid[(int) Math.round((d - 1) * 2) * 9 + (int) Math.round((t - 1) * 2)]++;
                    difficultySum += d;
                    terrainSum += t;
                    counted++;
                }
                JSONObject caller = cache.optJSONObject("callerSpecific");
                String found = caller == null ? "" : caller.optString("found", "");
                if (found.length() >= 10) days.add(found.substring(0, 10));
            }
            skip += data.length();
        }

        lastGrid = grid;
        ArrayList<Integer> counts = new ArrayList<>(81);
        for (int c : grid) counts.add(c);
        Object dt = construct(PKG + "UserStatsResponse$DifficultyTerrainStatistics",
            new Class<?>[]{int.class, float.class, float.class, java.util.List.class},
            7, counted == 0 ? 0f : (float) (difficultySum / counted), counted == 0 ? 0f : (float) (terrainSum / counted), counts);

        String[] longest = longestRun(days);
        String[] current = currentRun(days, today);
        Object streaks = construct(PKG + "UserStatsResponse$StreakStatistics",
            new Class<?>[]{int.class, Class.forName(PKG + "Streak"), Class.forName(PKG + "Streak")},
            3, streak(current), streak(longest));

        Utils.log("Statistics from " + skip + " finds, " + days.size() + " caching days");
        return construct(PKG + "UserStatsResponse",
            new Class<?>[]{int.class, dt.getClass(), boolean.class, streaks.getClass()},
            7, dt, days.contains(today), streaks);
    }

    /** {count, startDay, endDay} of the longest run of consecutive caching days. */
    private static String[] longestRun(TreeSet<String> days) throws Exception {
        String[] best = {"0", null, null};
        String start = null, previous = null;
        int length = 0;
        for (String day : days) {
            if (previous != null && day.equals(nextDay(previous))) {
                length++;
            } else {
                start = day;
                length = 1;
            }
            if (length > Integer.parseInt(best[0])) best = new String[]{String.valueOf(length), start, day};
            previous = day;
        }
        return best;
    }

    /** The run ending today or yesterday; a streak is still alive until a full day is missed. */
    private static String[] currentRun(TreeSet<String> days, String today) throws Exception {
        String end = days.contains(today) ? today : previousDay(today);
        if (!days.contains(end)) return new String[]{"0", null, null};
        String start = end;
        int length = 1;
        while (days.contains(previousDay(start))) {
            start = previousDay(start);
            length++;
        }
        return new String[]{String.valueOf(length), start, end};
    }

    private static Object streak(String[] run) throws Exception {
        return construct(PKG + "Streak", new Class<?>[]{String.class, int.class, int.class, String.class},
            run[1] == null ? null : run[1] + "T00:00:00", 7, Integer.parseInt(run[0]),
            run[2] == null ? null : run[2] + "T00:00:00");
    }

    private static String nextDay(String day) throws Exception {
        return shift(day, 1);
    }

    private static String previousDay(String day) throws Exception {
        return shift(day, -1);
    }

    private static String shift(String day, int amount) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(format.parse(day));
        calendar.add(Calendar.DAY_OF_MONTH, amount);
        return format.format(calendar.getTime());
    }

    static String currentReferenceCode() throws Exception {
        return (String) field(currentUser(), "w");
    }

    private static Object currentUser() throws Exception {
        Object component = Class.forName("com.groundspeak.geocaching.intro.a").getMethod("a").invoke(null);
        Object provider = field(component, "h");
        return provider.getClass().getMethod("get").invoke(provider);
    }

    private static String get(String url, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("Authorization", "bearer " + token);
        conn.setRequestProperty("Consumer-Key", consumerKey());
        conn.setRequestProperty("Accept", "application/json");
        int status = conn.getResponseCode();
        InputStream stream = status < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) body.append(buffer, 0, read);
        } finally {
            conn.disconnect();
        }
        if (status >= 400) throw new IllegalStateException("HTTP " + status + " for finds: " + body);
        return body.toString();
    }

    /** The app's own API client id; the Statistics patch copies it in from the app while patching. */
    private static String consumerKey() {
        return "";
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object construct(String className, Class<?>[] types, Object... args) throws Exception {
        Constructor<?> ctor = Class.forName(className).getDeclaredConstructor(types);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }
}
