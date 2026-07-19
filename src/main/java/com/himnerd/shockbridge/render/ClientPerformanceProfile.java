package com.himnerd.shockbridge.render;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Real-time performance metrics for a single Bedrock client. Updated continuously
 * from KeepAlive roundtrips and NACK frequency. The DynamicRenderController uses
 * these metrics to expand or contract per-client chunk boundaries.
 */
@Getter
public class ClientPerformanceProfile {

    private final UUID sessionId;
    private final int deviceOsId;
    private final String deviceModel;
    private final HardwareCategory hardwareCategory;

    @Setter private volatile int currentRenderDistance;
    private volatile long lastPingNanos;
    private volatile int averagePingMs;
    private volatile float packetLossRate;

    // Rolling averages
    private final long[] pingHistory = new long[16];
    private int pingIndex;
    private int nackCount;
    private int totalPackets;

    public ClientPerformanceProfile(UUID sessionId, int deviceOsId, String deviceModel) {
        this.sessionId = sessionId;
        this.deviceOsId = deviceOsId;
        this.deviceModel = deviceModel;
        this.hardwareCategory = classifyHardware(deviceOsId, deviceModel);
        this.currentRenderDistance = hardwareCategory.defaultRenderDistance;
    }

    public void recordPing(long pingMs) {
        pingHistory[pingIndex % pingHistory.length] = pingMs;
        pingIndex++;
        lastPingNanos = System.nanoTime();

        // Calculate rolling average
        long sum = 0;
        int count = Math.min(pingIndex, pingHistory.length);
        for (int i = 0; i < count; i++) {
            sum += pingHistory[i];
        }
        averagePingMs = (int) (sum / count);
    }

    public void recordNack() {
        nackCount++;
        recalculateLoss();
    }

    public void recordPacket() {
        totalPackets++;
        recalculateLoss();
    }

    private void recalculateLoss() {
        if (totalPackets > 0) {
            packetLossRate = (float) nackCount / totalPackets;
        }
    }

    // ── Hardware classification ─────────────────────────────

    public enum HardwareCategory {
        HIGH(16),
        MEDIUM(10),
        LOW(6),
        MINIMAL(4);

        public final int defaultRenderDistance;

        HardwareCategory(int defaultRenderDistance) {
            this.defaultRenderDistance = defaultRenderDistance;
        }
    }

    private static HardwareCategory classifyHardware(int deviceOsId, String deviceModel) {
        // High-end: Windows 10, Xbox, PlayStation, dedicated
        if (deviceOsId == 7 || deviceOsId == 13 || deviceOsId == 11 || deviceOsId == 9 || deviceOsId == 15) {
            return HardwareCategory.HIGH;
        }
        // Medium: Nintendo Switch, iOS (newer), macOS
        if (deviceOsId == 12 || deviceOsId == 2 || deviceOsId == 3) {
            return HardwareCategory.MEDIUM;
        }
        // Low: Android, FireOS
        if (deviceOsId == 1 || deviceOsId == 4) {
            return HardwareCategory.LOW;
        }
        // VR/other
        if (deviceOsId == 5 || deviceOsId == 6) {
            return HardwareCategory.MINIMAL;
        }
        return HardwareCategory.MEDIUM;
    }
}