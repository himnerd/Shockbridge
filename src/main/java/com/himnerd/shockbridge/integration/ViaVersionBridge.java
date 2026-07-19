package com.himnerd.shockbridge.integration;

import com.himnerd.shockbridge.ShockbridgePlugin;
import lombok.Getter;
import org.bukkit.Bukkit;

import java.util.Map;

public class ViaVersionBridge {

    private static final Map<Integer, Integer> BEDROCK_TO_JAVA = Map.ofEntries(
            Map.entry(685, 769),   // 1.21.50 → Java 1.21.4
            Map.entry(686, 769),   // 1.21.51
            Map.entry(671, 768),   // 1.21.40 → Java 1.21.3
            Map.entry(662, 767),   // 1.21.30 → Java 1.21.2
            Map.entry(649, 766),   // 1.21.20 → Java 1.21.1
            Map.entry(630, 766),   // 1.21.0  → Java 1.21
            Map.entry(622, 765),   // 1.20.80 → Java 1.20.6
            Map.entry(618, 765),   // 1.20.70 → Java 1.20.5
            Map.entry(594, 764),   // 1.20.50 → Java 1.20.4
            Map.entry(589, 763),   // 1.20.40 → Java 1.20.2
            Map.entry(582, 763),   // 1.20.30
            Map.entry(575, 762),   // 1.20.10 → Java 1.20.1
            Map.entry(568, 762)    // 1.20.0  → Java 1.20
    );

    private static final int DEFAULT_JAVA_PROTOCOL = 769;

    private final ShockbridgePlugin plugin;
    @Getter private boolean viaVersionPresent;
    @Getter private boolean viaBackwardsPresent;
    @Getter private boolean viaRewindPresent;
    @Getter private int serverProtocolVersion = -1;

    public ViaVersionBridge(ShockbridgePlugin plugin) {
        this.plugin = plugin;
    }

    public void detect() {
        viaVersionPresent = Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
        viaBackwardsPresent = Bukkit.getPluginManager().getPlugin("ViaBackwards") != null;
        viaRewindPresent = Bukkit.getPluginManager().getPlugin("ViaRewind") != null;

        if (viaVersionPresent) {
            serverProtocolVersion = resolveServerProtocol();
            plugin.getLogger().info("[Via] ViaVersion detected — server protocol " + serverProtocolVersion);
            if (viaBackwardsPresent)
                plugin.getLogger().info("[Via] ViaBackwards detected — older Bedrock versions supported");
            if (viaRewindPresent)
                plugin.getLogger().info("[Via] ViaRewind detected — legacy Bedrock versions supported");
        }
    }

    public int mapToJavaProtocol(int bedrockProtocol) {
        if (!viaVersionPresent) {
            return serverProtocolVersion > 0 ? serverProtocolVersion : DEFAULT_JAVA_PROTOCOL;
        }
        Integer exact = BEDROCK_TO_JAVA.get(bedrockProtocol);
        if (exact != null) return exact;

        int best = DEFAULT_JAVA_PROTOCOL;
        int bestDist = Integer.MAX_VALUE;
        for (var entry : BEDROCK_TO_JAVA.entrySet()) {
            int dist = Math.abs(entry.getKey() - bedrockProtocol);
            if (dist < bestDist) {
                bestDist = dist;
                best = entry.getValue();
            }
        }
        return best;
    }

    public boolean canAcceptBedrockVersion(int bedrockProtocol) {
        if (viaVersionPresent) return true;
        int mapped = BEDROCK_TO_JAVA.getOrDefault(bedrockProtocol, -1);
        return mapped == serverProtocolVersion || mapped == DEFAULT_JAVA_PROTOCOL;
    }

    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("ViaVersion: ").append(viaVersionPresent ? "YES" : "NO");
        if (viaVersionPresent) {
            sb.append(" | Backwards: ").append(viaBackwardsPresent ? "YES" : "NO");
            sb.append(" | Rewind: ").append(viaRewindPresent ? "YES" : "NO");
            sb.append(" | Protocol: ").append(serverProtocolVersion);
        }
        return sb.toString();
    }

    private int resolveServerProtocol() {
        try {
            Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = viaClass.getMethod("getAPI").invoke(null);
            Object serverVersion = api.getClass().getMethod("getServerVersion").invoke(api);
            Object protocolVersion = serverVersion.getClass()
                    .getMethod("lowestSupportedProtocolVersion").invoke(serverVersion);
            return (int) protocolVersion.getClass().getMethod("getVersion").invoke(protocolVersion);
        } catch (Exception e) {
            plugin.getDebugLogger().logException("ViaVersionBridge", e);
            return -1;
        }
    }
}