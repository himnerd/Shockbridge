package com.himnerd.shockbridge.haptics;

import com.himnerd.shockbridge.ShockbridgePlugin;
import com.himnerd.shockbridge.injection.JavaSessionInjector;
import com.himnerd.shockbridge.network.VirtualSession;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.UUID;

/**
 * Listens to Bukkit damage and explosion events, translating them into Bedrock
 * native camera shakes and controller haptics. While Java players see standard
 * particles and knockback, Bedrock clients experience visceral physical feedback
 * through the CameraShake packet system.
 */
public class HapticsListener implements Listener {

    private final ShockbridgePlugin plugin;

    public HapticsListener(ShockbridgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!plugin.getShockbridgeConfig().isHapticsEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        VirtualSession session = findBedrockSession(player);
        if (session == null) return;

        double intensity = plugin.getShockbridgeConfig().getCameraShakeIntensity();

        EntityDamageEvent.DamageCause cause = event.getCause();
        double damage = event.getFinalDamage();

        switch (cause) {
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> {
                CameraShakeController.explosionShake(session, (float) damage, intensity);
            }
            case FALL -> {
                if (damage >= 4.0) {
                    float power = (float) Math.min(damage / 10.0, 1.0);
                    CameraShakeController.shake(session,
                            (float) (power * intensity), 0.3f,
                            CameraShakeController.SHAKE_POSITIONAL,
                            CameraShakeController.ACTION_ADD);
                }
            }
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> {
                if (damage >= 6.0) {
                    CameraShakeController.criticalHitShake(session, intensity);
                }
            }
            default -> {
                // No haptic feedback for other damage types
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!plugin.getShockbridgeConfig().isHapticsEnabled()) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        VirtualSession session = findBedrockSession(attacker);
        if (session == null) return;

        double intensity = plugin.getShockbridgeConfig().getCameraShakeIntensity();

        // Give the attacker feedback on critical hits
        if (event.isCritical()) {
            CameraShakeController.criticalHitShake(session, intensity * 0.5);
        }
    }

    /**
     * Find the VirtualSession for a Paper player if they're a Bedrock client.
     */
    private VirtualSession findBedrockSession(Player player) {
        JavaSessionInjector injector = plugin.getJavaSessionInjector();
        if (injector == null) return null;

        // Search injected sessions by matching Java UUID → Bedrock session
        if (plugin.getSessionManager() == null) return null;
        for (var entry : plugin.getSessionManager().getAllSessions().entrySet()) {
            VirtualSession vs = entry.getValue();
            if (vs.getInjectionChannel() != null && vs.isConnected()) {
                JavaSessionInjector.InjectedSession is = injector.getInjectedSession(vs.getSessionId());
                if (is != null && is.isInPlayState()) {
                    // Match by checking if this session's linked UUID or generated UUID matches the player
                    UUID playerUuid = player.getUniqueId();
                    if (vs.getLinkedAccount() != null && vs.getLinkedAccount().getJavaUuid().equals(playerUuid)) {
                        return vs;
                    }
                    // Check gamertag-derived UUID
                    String gt = vs.getXboxGamertag();
                    if (gt != null) {
                        UUID derived = UUID.nameUUIDFromBytes(("Shockbridge:" + gt)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        if (derived.equals(playerUuid)) return vs;
                    }
                }
            }
        }
        return null;
    }
}