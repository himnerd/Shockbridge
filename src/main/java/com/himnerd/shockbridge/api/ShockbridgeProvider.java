package com.himnerd.shockbridge.api;

/**
 * Static accessor for the Shockbridge API. Other plugins should use this to obtain the API instance.
 * <pre>
 *   ShockbridgeAPI api = ShockbridgeProvider.get();
 *   if (api != null) {
 *       // Shockbridge is installed and loaded
 *   }
 * </pre>
 */
public final class ShockbridgeProvider {

    private static ShockbridgeAPI instance;

    private ShockbridgeProvider() {}

    /**
     * Get the Shockbridge API instance.
     *
     * @return the API instance, or null if Shockbridge is not loaded
     */
    public static ShockbridgeAPI get() {
        return instance;
    }

    /**
     * @return true if the API is available
     */
    public static boolean isAvailable() {
        return instance != null;
    }

    /**
     * Internal — called by the Shockbridge plugin on enable.
     */
    public static void register(ShockbridgeAPI api) {
        instance = api;
    }

    /**
     * Internal — called by the Shockbridge plugin on disable.
     */
    public static void unregister() {
        instance = null;
    }
}