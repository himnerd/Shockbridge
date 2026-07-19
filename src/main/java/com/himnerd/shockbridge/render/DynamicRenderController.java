package com.himnerd.shockbridge.render;

import com.himnerd.shockbridge.ShockbridgePlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamically adjusts per-client render distance based on real-time performance metrics.
 * Operates independently of global server view-distance — each Bedrock client gets an
 * individually tuned chunk radius that contracts during network congestion and expands
 * when conditions improve.
 *
 * Algorithm:
 *   1. Start at hardware category default
 *   2. High ping (>150ms) → decrease by 2
 *   3. Very high ping (>300ms) → decrease by 4
 *   4. Packet loss >5% → decrease by 2
 *   5. Stable low ping (<80ms) + low loss (<1%) → increase by 1 (up to max)
 *   6. Never go below config min or above config max
 */
public class DynamicRenderController {

    private final ShockbridgePlugin plugin;
    private final Map<UUID, ClientPerformanceProfile> profiles = new ConcurrentHashMap<>();

    public DynamicRenderController(ShockbridgePlugin plugin) {
        this.plugin = plugin;
    }

    public ClientPerformanceProfile createProfile(UUID sessionId, int deviceOsId, String deviceModel) {
        ClientPerformanceProfile profile = new ClientPerformanceProfile(sessionId, deviceOsId, deviceModel);
        profiles.put(sessionId, profile);
        plugin.getDebugLogger().log("DynamicRender: created profile for " + sessionId +
                " — hardware=" + profile.getHardwareCategory() + ", default radius=" + profile.getCurrentRenderDistance());
        return profile;
    }

    public int calculateOptimalRadius(ClientPerformanceProfile profile) {
        int min = plugin.getShockbridgeConfig().getMinRenderDistance();
        int max = plugin.getShockbridgeConfig().getMaxRenderDistance();
        int base = profile.getHardwareCategory().defaultRenderDistance;

        int adjustment = 0;

        // Ping-based adjustment
        int avgPing = profile.getAveragePingMs();
        if (avgPing > 300) {
            adjustment -= 4;
        } else if (avgPing > 150) {
            adjustment -= 2;
        } else if (avgPing < 80) {
            adjustment += 1;
        }

        // Packet loss adjustment
        float loss = profile.getPacketLossRate();
        if (loss > 0.10f) {
            adjustment -= 3;
        } else if (loss > 0.05f) {
            adjustment -= 2;
        } else if (loss < 0.01f) {
            adjustment += 1;
        }

        int optimal = Math.max(min, Math.min(max, base + adjustment));

        // Smooth: don't change by more than 2 at a time
        int current = profile.getCurrentRenderDistance();
        if (optimal > current + 2) optimal = current + 2;
        if (optimal < current - 2) optimal = current - 2;

        return Math.max(min, Math.min(max, optimal));
    }

    public ClientPerformanceProfile getProfile(UUID sessionId) {
        return profiles.get(sessionId);
    }

    public void removeProfile(UUID sessionId) {
        profiles.remove(sessionId);
    }

    public int getProfileCount() {
        return profiles.size();
    }

    public void shutdown() {
        profiles.clear();
    }
}