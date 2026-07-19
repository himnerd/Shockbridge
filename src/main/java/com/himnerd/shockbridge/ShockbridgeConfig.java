package com.himnerd.shockbridge;

import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

@Getter
public class ShockbridgeConfig {

    private final ShockbridgePlugin plugin;
    private boolean alphaEnabled;
    private String bindAddress;
    private int bindPort;
    private int queueCapacity;
    private boolean debugLogging;
    private int maxSessionsPerIp;
    private int rateLimitTokens;
    private int rateLimitRefillPerSecond;
    private double movementSmoothingFactor;
    private int compressionThreshold;
    private String resourcePackSource;
    private boolean useBungeeForwarding;
    private boolean requireMicrosoftAuth;
    private int chunkCacheSize;
    private int chunkPreloadRadius;
    private int minRenderDistance;
    private int maxRenderDistance;
    private double cameraShakeIntensity;
    private boolean hapticsEnabled;

    public ShockbridgeConfig(ShockbridgePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        this.alphaEnabled = cfg.getBoolean("shockbridge.alpha-enabled", false);
        this.bindAddress = cfg.getString("shockbridge.bind-address", "0.0.0.0");
        this.bindPort = cfg.getInt("shockbridge.bind-port", 19132);
        this.queueCapacity = cfg.getInt("shockbridge.queue-capacity", 8192);
        this.debugLogging = cfg.getBoolean("shockbridge.debug-logging", false);
        this.maxSessionsPerIp = cfg.getInt("shockbridge.max-sessions-per-ip", 3);
        this.rateLimitTokens = cfg.getInt("shockbridge.rate-limit.tokens", 200);
        this.rateLimitRefillPerSecond = cfg.getInt("shockbridge.rate-limit.refill-per-second", 100);
        this.movementSmoothingFactor = cfg.getDouble("shockbridge.movement-smoothing-factor", 0.6);
        this.compressionThreshold = cfg.getInt("shockbridge.compression-threshold", 256);
        this.resourcePackSource = cfg.getString("shockbridge.resource-pack.source", "auto");
        this.useBungeeForwarding = cfg.getBoolean("shockbridge.injection.use-bungee-forwarding", false);
        this.requireMicrosoftAuth = cfg.getBoolean("shockbridge.identity.require-microsoft-auth", true);
        this.chunkCacheSize = cfg.getInt("shockbridge.chunk-cache.buffer-size-mb", 64);
        this.chunkPreloadRadius = cfg.getInt("shockbridge.chunk-cache.preload-radius", 3);
        this.minRenderDistance = cfg.getInt("shockbridge.render-distance.min", 4);
        this.maxRenderDistance = cfg.getInt("shockbridge.render-distance.max", 16);
        this.cameraShakeIntensity = cfg.getDouble("shockbridge.haptics.camera-shake-intensity", 1.0);
        this.hapticsEnabled = cfg.getBoolean("shockbridge.haptics.enabled", true);
    }
}