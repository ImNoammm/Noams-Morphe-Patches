package app.noam.extension.geocaching;

/** Optional features; each one's patch replaces its method here to return true. */
public final class GcFeatures {
    private GcFeatures() {}

    /** Every opened cache is kept on the device and opens offline. */
    public static boolean offlineCacheCopies() {
        return false;
    }

    /** Every cache the map shows is kept on the device, so the map still shows them offline. */
    public static boolean offlineMapCaches() {
        return false;
    }

    /** The map type picker's Street, Satellite, Terrain and Hybrid layers draw on the keyless map. */
    public static boolean mapLayers() {
        return false;
    }

    /** Description links to checkers and other sites open in a page inside the app. */
    public static boolean linksInApp() {
        return false;
    }
}
