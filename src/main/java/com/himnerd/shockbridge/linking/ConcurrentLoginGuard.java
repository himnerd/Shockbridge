package com.himnerd.shockbridge.linking;

import com.himnerd.shockbridge.ShockbridgePlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents duplicate sessions when a linked account attempts simultaneous logins
 * from both Java and Bedrock (or two Bedrock clients). Enforces strict session
 * priority:
 *   1. If the Java UUID is already online (as a Java player), Bedrock is rejected
 *   2. If the Java UUID is already online (as a Bedrock injection), the new connection is rejected
 *   3. First-come-first-served prevents item duplication vectors
 */
public class ConcurrentLoginGuard implements Listener {

    private final ShockbridgePlugin plugin;

    // UUID → session ID that claimed it
    private final Map<UUID, UUID> claimedUuids = new ConcurrentHashMap<>();
    // Set of Java UUIDs currently online on the Java side (real Java players)
    private final Set<UUID> onlineJavaPlayers = ConcurrentHashMap.newKeySet();

    public ConcurrentLoginGuard(ShockbridgePlugin plugin) {
        this.plugin = plugin;
        // Snapshot current online players
        for (Player p : Bukkit.getOnlinePlayers()) {
            onlineJavaPlayers.add(p.getUniqueId());
        }
    }

    /**
     * Attempt to claim a Java UUID for a Bedrock session.
     * Returns false if the UUID is already in use.
     */
    public boolean tryAcquire(UUID sessionId, UUID javaUuid) {
        // Check if a real Java player with this UUID is online
        if (onlineJavaPlayers.contains(javaUuid)) {
            // Only block if this is a linked account (unlinked Bedrock UUIDs are generated uniquely)
            AccountLinker linker = plugin.getAccountLinker();
            if (linker != null && linker.isJavaUuidLinked(javaUuid)) {
                plugin.getDebugLogger().log("ConcurrentLoginGuard: rejected session " + sessionId +
                        " — Java player " + javaUuid + " already online");
                return false;
            }
        }

        // Try atomic claim
        UUID existing = claimedUuids.putIfAbsent(javaUuid, sessionId);
        if (existing != null && !existing.equals(sessionId)) {
            plugin.getDebugLogger().log("ConcurrentLoginGuard: rejected session " + sessionId +
                    " — UUID " + javaUuid + " already claimed by session " + existing);
            return false;
        }

        plugin.getDebugLogger().log("ConcurrentLoginGuard: session " + sessionId +
                " acquired UUID " + javaUuid);
        return true;
    }

    /**
     * Release a session's UUID claim (on disconnect).
     */
    public void release(UUID sessionId) {
        claimedUuids.entrySet().removeIf(e -> e.getValue().equals(sessionId));
    }

    public int getActiveCount() {
        return claimedUuids.size();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        onlineJavaPlayers.add(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        onlineJavaPlayers.remove(uuid);
    }
}