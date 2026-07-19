package com.himnerd.shockbridge.debug;

import com.himnerd.shockbridge.ShockbridgePlugin;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Toggleable nanosecond-resolution diagnostic logger for the alpha phase.
 * When enabled, tracks translation deltas, buffer allocations, and failed packet mappings.
 */
public class AlphaDebugLogger {

    private final Logger logger;
    private volatile boolean enabled;

    public AlphaDebugLogger(ShockbridgePlugin plugin) {
        this.logger = plugin.getLogger();
        this.enabled = plugin.getConfig().getBoolean("shockbridge.debug-logging", false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public void log(String message) {
        if (!enabled) return;
        logger.info("[DEBUG] " + message);
    }

    public void logPacketDrop(InetSocketAddress sender, String reason) {
        if (!enabled) return;
        logger.fine("[DEBUG] Packet dropped from " + sender + ": " + reason);
    }

    public void logUnknownPacket(InetSocketAddress sender, byte messageId) {
        if (!enabled) return;
        logger.fine("[DEBUG] Unknown packet 0x" + String.format("%02X", messageId & 0xFF) + " from " + sender);
    }

    public void logConnection(InetSocketAddress sender, String message) {
        if (!enabled) return;
        String prefix = sender != null ? "[" + sender + "] " : "";
        logger.info("[DEBUG] " + prefix + message);
    }

    public void logException(String context, Throwable throwable) {
        logger.warning("[CRASH] " + context + ": " + throwable.getMessage());
        if (enabled) {
            throwable.printStackTrace();
        }
    }

    public void logTranslation(UUID sessionId, int packetId, long nanos) {
        if (!enabled) return;
        logger.fine("[DEBUG] Translated 0x" + Integer.toHexString(packetId) +
                " session=" + sessionId + " in " + nanos + "ns");
    }

    public void logBufferMetrics(int leaked, int tracked, long totalBytes) {
        if (!enabled) return;
        logger.info("[DEBUG] Buffers: leaked=" + leaked + " tracked=" + tracked + " bytes=" + totalBytes);
    }
}