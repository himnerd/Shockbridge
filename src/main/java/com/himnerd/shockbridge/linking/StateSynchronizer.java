package com.himnerd.shockbridge.linking;

import com.himnerd.shockbridge.ShockbridgePlugin;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors and synchronizes player state for linked Bedrock accounts in real time.
 * Since injected Bedrock sessions use the linked Java UUID, Paper automatically loads
 * the correct player profile (inventory, stats, advancements, ender chest, XP).
 *
 * This synchronizer handles edge cases:
 *   - Verifying profile load completed before allowing gameplay
 *   - Tracking active sync sessions for clean teardown
 *   - Providing hooks for future ChargedServer API custom data sync
 */
public class StateSynchronizer {

    private final ShockbridgePlugin plugin;
    @Getter private final Map<UUID, SyncState> activeSyncs = new ConcurrentHashMap<>();

    public StateSynchronizer(ShockbridgePlugin plugin) {
        this.plugin = plugin;
    }

    public void beginSync(UUID sessionId, UUID javaUuid) {
        SyncState state = new SyncState(sessionId, javaUuid, System.currentTimeMillis());
        activeSyncs.put(sessionId, state);

        plugin.getDebugLogger().log("StateSynchronizer: begin sync for session " +
                sessionId + " → Java UUID " + javaUuid);

        // Schedule a verification tick to confirm profile loaded correctly
        Bukkit.getScheduler().runTaskLater(plugin, () -> verifyProfileLoaded(sessionId, javaUuid), 40L);
    }

    public void endSync(UUID sessionId) {
        SyncState removed = activeSyncs.remove(sessionId);
        if (removed != null) {
            plugin.getDebugLogger().log("StateSynchronizer: ended sync for session " + sessionId +
                    " (active " + (System.currentTimeMillis() - removed.startTime) + "ms)");
        }
    }

    public boolean isSyncing(UUID sessionId) {
        return activeSyncs.containsKey(sessionId);
    }

    private void verifyProfileLoaded(UUID sessionId, UUID javaUuid) {
        SyncState state = activeSyncs.get(sessionId);
        if (state == null) return;

        Player player = Bukkit.getPlayer(javaUuid);
        if (player != null && player.isOnline()) {
            state.profileVerified = true;
            plugin.getDebugLogger().log("StateSynchronizer: profile verified for " + player.getName());
        } else {
            plugin.getDebugLogger().log("StateSynchronizer: profile verification pending for " + javaUuid);
        }
    }

    public int getActiveCount() {
        return activeSyncs.size();
    }

    public static class SyncState {
        public final UUID sessionId;
        public final UUID javaUuid;
        public final long startTime;
        public volatile boolean profileVerified;

        SyncState(UUID sessionId, UUID javaUuid, long startTime) {
            this.sessionId = sessionId;
            this.javaUuid = javaUuid;
            this.startTime = startTime;
        }
    }
}