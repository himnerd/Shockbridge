package com.himnerd.shockbridge.api;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Public API for Shockbridge — the Bedrock ↔ Java protocol bridge.
 * <p>
 * Access via {@link ShockbridgeProvider#get()}.
 * <pre>
 *   ShockbridgeAPI api = ShockbridgeProvider.get();
 *   if (api != null && api.isBedrockPlayer(player)) {
 *       BedrockPlayer bp = api.getBedrockPlayer(player).orElse(null);
 *       player.sendMessage("Welcome, " + bp.getGamertag() + "!");
 *   }
 * </pre>
 */
public interface ShockbridgeAPI {

    // ── Bridge Status ───────────────────────────────────────

    /**
     * @return true if the Shockbridge protocol bridge is currently active and accepting connections
     */
    boolean isActive();

    /**
     * @return number of currently connected Bedrock sessions
     */
    int getBedrockSessionCount();

    // ── Player Queries ──────────────────────────────────────

    /**
     * Check if a player connected through the Bedrock protocol bridge.
     *
     * @param player the online player to check
     * @return true if this player is a Bedrock client connected via Shockbridge
     */
    boolean isBedrockPlayer(Player player);

    /**
     * Check if a session UUID belongs to an active Bedrock session.
     *
     * @param sessionId the session UUID to check
     * @return true if an active Bedrock session exists with this ID
     */
    boolean isBedrockSession(UUID sessionId);

    /**
     * Get detailed Bedrock player information for an online player.
     *
     * @param player the online player
     * @return the Bedrock player info, or empty if not a Bedrock client
     */
    Optional<BedrockPlayer> getBedrockPlayer(Player player);

    /**
     * Get detailed Bedrock player information by session UUID.
     *
     * @param sessionId the session UUID
     * @return the Bedrock player info, or empty if no active session
     */
    Optional<BedrockPlayer> getBedrockPlayer(UUID sessionId);

    /**
     * Get all currently connected Bedrock players.
     *
     * @return unmodifiable collection of active Bedrock player snapshots
     */
    Collection<BedrockPlayer> getOnlineBedrockPlayers();

    // ── Account Linking ─────────────────────────────────────

    /**
     * Link a Bedrock XUID to a Java account UUID. Overwrites any existing link
     * for either identity.
     *
     * @param xuid     the Bedrock Xbox User ID
     * @param javaUuid the Java account UUID to link to
     * @param javaName the Java account name (for display purposes)
     */
    void linkAccount(String xuid, UUID javaUuid, String javaName);

    /**
     * Remove an existing Bedrock ↔ Java account link.
     *
     * @param xuid the Bedrock Xbox User ID to unlink
     */
    void unlinkAccount(String xuid);

    /**
     * Look up the linked Java UUID for a Bedrock XUID.
     *
     * @param xuid the Bedrock Xbox User ID
     * @return the linked Java UUID, or empty if not linked
     */
    Optional<UUID> getLinkedJavaUuid(String xuid);

    /**
     * Look up the linked Bedrock XUID for a Java account UUID.
     *
     * @param javaUuid the Java account UUID
     * @return the linked XUID, or empty if not linked
     */
    Optional<String> getLinkedXuid(UUID javaUuid);

    /**
     * Check if a Java UUID has a linked Bedrock account.
     *
     * @param javaUuid the Java account UUID
     * @return true if a Bedrock account is linked to this Java UUID
     */
    boolean isAccountLinked(UUID javaUuid);

    /**
     * @return total number of persistent account links
     */
    int getLinkedAccountCount();

    // ── ID Conversion ───────────────────────────────────────

    /**
     * Convert a Bedrock block runtime ID to its Java block state ID.
     *
     * @param bedrockBlockId the Bedrock runtime block ID
     * @return the equivalent Java block state ID, or 0 if unmapped
     */
    int convertBlockToJava(int bedrockBlockId);

    /**
     * Convert a Java block state ID to its Bedrock block runtime ID.
     *
     * @param javaBlockId the Java block state ID
     * @return the equivalent Bedrock runtime block ID, or 0 if unmapped
     */
    int convertBlockToBedrock(int javaBlockId);

    /**
     * Convert a Bedrock item ID to its Java item ID.
     *
     * @param bedrockItemId the Bedrock item ID
     * @return the equivalent Java item ID, or 0 if unmapped
     */
    int convertItemToJava(int bedrockItemId);

    /**
     * Convert a Java item ID to its Bedrock item ID.
     *
     * @param javaItemId the Java item ID
     * @return the equivalent Bedrock item ID, or 0 if unmapped
     */
    int convertItemToBedrock(int javaItemId);

    // ── Custom Item Translation ─────────────────────────────

    /**
     * Translate a Java custom item (identified by base material + custom_model_data)
     * to its Bedrock runtime identifier string.
     *
     * @param material          the Java base material name (e.g., "diamond_sword")
     * @param customModelData   the custom_model_data integer value
     * @return the Bedrock identifier (e.g., "shockbridge:laser_rifle"), or empty if unmapped
     */
    Optional<String> translateCustomItemToBedrock(String material, int customModelData);

    /**
     * Translate a Java custom item (identified by item_model component string)
     * to its Bedrock runtime identifier string.
     *
     * @param itemModelId the minecraft:item_model component value
     * @return the Bedrock identifier, or empty if unmapped
     */
    Optional<String> translateCustomItemToBedrock(String itemModelId);

    /**
     * Translate a Bedrock custom item identifier back to its Java material + custom_model_data.
     *
     * @param bedrockIdentifier the Bedrock identifier (e.g., "shockbridge:laser_rifle")
     * @return a string in the format "material:customModelData", or empty if unmapped
     */
    Optional<String> translateCustomItemToJava(String bedrockIdentifier);

    /**
     * @return total number of custom item mappings registered from the asset scan
     */
    int getCustomItemMappingCount();
}