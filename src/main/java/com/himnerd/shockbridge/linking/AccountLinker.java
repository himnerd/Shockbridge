package com.himnerd.shockbridge.linking;

import com.himnerd.shockbridge.ShockbridgePlugin;
import com.himnerd.shockbridge.api.event.BedrockAccountLinkEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages persistent XUID ↔ Java UUID account links. When a linked Bedrock player
 * connects, the injection uses their Java UUID so Paper loads the correct profile
 * (inventory, stats, advancements, ender chest — everything).
 *
 * Storage: plugins/Shockbridge/linked-accounts.yml
 * Format:
 *   accounts:
 *     "2535416543234567":
 *       java-uuid: "069a79f4-44e9-4726-a5be-fca90e38aaf5"
 *       java-name: "Notch"
 *       linked-at: "2024-06-15T12:00:00Z"
 */
public class AccountLinker {

    private final ShockbridgePlugin plugin;
    private final File dataFile;
    private final Map<String, LinkedAccount> linksByXuid = new ConcurrentHashMap<>();
    private final Map<UUID, LinkedAccount> linksByJavaUuid = new ConcurrentHashMap<>();

    public AccountLinker(ShockbridgePlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "linked-accounts.yml");
        load();
    }

    public void link(String xuid, UUID javaUuid, String javaName) {
        LinkedAccount account = new LinkedAccount(xuid, javaUuid, javaName, Instant.now());

        // Remove any old link for either identity
        LinkedAccount oldByXuid = linksByXuid.remove(xuid);
        if (oldByXuid != null) linksByJavaUuid.remove(oldByXuid.getJavaUuid());
        LinkedAccount oldByUuid = linksByJavaUuid.remove(javaUuid);
        if (oldByUuid != null) linksByXuid.remove(oldByUuid.getXuid());

        linksByXuid.put(xuid, account);
        linksByJavaUuid.put(javaUuid, account);
        save();

        Bukkit.getPluginManager().callEvent(new BedrockAccountLinkEvent(xuid, javaUuid, javaName, false));

        plugin.getDebugLogger().log("AccountLinker: linked XUID " + xuid +
                " → " + javaName + " (" + javaUuid + ")");
    }

    public void unlink(String xuid) {
        LinkedAccount removed = linksByXuid.remove(xuid);
        if (removed != null) {
            linksByJavaUuid.remove(removed.getJavaUuid());
            save();
            Bukkit.getPluginManager().callEvent(
                    new BedrockAccountLinkEvent(xuid, removed.getJavaUuid(), removed.getJavaName(), true));
            plugin.getDebugLogger().log("AccountLinker: unlinked XUID " + xuid);
        }
    }

    public LinkedAccount getByXuid(String xuid) {
        return xuid != null ? linksByXuid.get(xuid) : null;
    }

    public LinkedAccount getByJavaUuid(UUID uuid) {
        return uuid != null ? linksByJavaUuid.get(uuid) : null;
    }

    public int getLinkCount() {
        return linksByXuid.size();
    }

    public boolean isJavaUuidLinked(UUID uuid) {
        return linksByJavaUuid.containsKey(uuid);
    }

    // ── Persistence ─────────────────────────────────────────

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection accounts = yaml.getConfigurationSection("accounts");
        if (accounts == null) return;

        for (String xuid : accounts.getKeys(false)) {
            ConfigurationSection entry = accounts.getConfigurationSection(xuid);
            if (entry == null) continue;

            String uuidStr = entry.getString("java-uuid", "");
            String name = entry.getString("java-name", "");
            String linkedStr = entry.getString("linked-at", "");

            try {
                UUID javaUuid = UUID.fromString(uuidStr);
                Instant linkedAt = linkedStr.isEmpty() ? Instant.now() : Instant.parse(linkedStr);
                LinkedAccount account = new LinkedAccount(xuid, javaUuid, name, linkedAt);
                linksByXuid.put(xuid, account);
                linksByJavaUuid.put(javaUuid, account);
            } catch (Exception e) {
                plugin.getLogger().warning("[AccountLinker] Skipping malformed entry for XUID " + xuid);
            }
        }

        plugin.getDebugLogger().log("AccountLinker: loaded " + linksByXuid.size() + " linked accounts");
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, LinkedAccount> entry : linksByXuid.entrySet()) {
            LinkedAccount a = entry.getValue();
            String path = "accounts." + a.getXuid();
            yaml.set(path + ".java-uuid", a.getJavaUuid().toString());
            yaml.set(path + ".java-name", a.getJavaName());
            yaml.set(path + ".linked-at", a.getLinkedAt().toString());
        }
        try {
            yaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[AccountLinker] Failed to save: " + e.getMessage());
        }
    }
}