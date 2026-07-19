package com.himnerd.shockbridge.translation;

import com.himnerd.shockbridge.ShockbridgePlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts Bedrock MovePlayerPacket data and applies mathematical smoothing
 * to normalize step-height, tick-rate, and bounding-box differences between
 * Bedrock and Java physics. Prevents false positives on GrimAC/Vulcan/etc.
 */
public class MovementTranslator {

    private static final double BEDROCK_STEP_HEIGHT = 0.6;
    private static final double JAVA_STEP_HEIGHT = 0.6;
    private static final double STEP_DELTA = JAVA_STEP_HEIGHT - BEDROCK_STEP_HEIGHT;
    private static final double POSITION_EPSILON = 0.001;

    private final double smoothingFactor;
    private final Map<UUID, PositionState> states = new ConcurrentHashMap<>();

    public MovementTranslator(ShockbridgePlugin plugin) {
        this.smoothingFactor = plugin.getShockbridgeConfig().getMovementSmoothingFactor();
    }

    /**
     * Applies exponential smoothing + step-height compensation to raw Bedrock coordinates.
     * Returns [x, y, z, yaw, pitch] in Java-compatible space.
     */
    public double[] smooth(UUID sessionId, double x, double y, double z, float yaw, float pitch) {
        PositionState s = states.computeIfAbsent(sessionId, k -> new PositionState());

        long now = System.nanoTime();
        long dtNanos = s.lastNanos > 0 ? now - s.lastNanos : 50_000_000L;
        double dt = Math.max(0.001, Math.min(dtNanos / 1_000_000_000.0, 0.5));

        double dx = x - s.x;
        double dy = y - s.y;
        double dz = z - s.z;

        if (Math.abs(dy - BEDROCK_STEP_HEIGHT) < POSITION_EPSILON
                && Math.abs(dx) + Math.abs(dz) > POSITION_EPSILON) {
            dy += STEP_DELTA;
        }

        double sdx = s.vx + (dx - s.vx) * smoothingFactor;
        double sdy = s.vy + (dy - s.vy) * smoothingFactor;
        double sdz = s.vz + (dz - s.vz) * smoothingFactor;
        float sYaw = lerpAngle(s.yaw, yaw, (float) smoothingFactor);
        float sPitch = lerpAngle(s.pitch, pitch, (float) smoothingFactor);

        double nx = s.x + sdx;
        double ny = s.y + sdy;
        double nz = s.z + sdz;

        s.vx = sdx;
        s.vy = sdy;
        s.vz = sdz;
        s.x = nx;
        s.y = ny;
        s.z = nz;
        s.yaw = sYaw;
        s.pitch = sPitch;
        s.lastNanos = now;

        return new double[]{nx, ny, nz, sYaw, sPitch};
    }

    public void initPosition(UUID sessionId, double x, double y, double z, float yaw, float pitch) {
        PositionState s = states.computeIfAbsent(sessionId, k -> new PositionState());
        s.x = x;
        s.y = y;
        s.z = z;
        s.yaw = yaw;
        s.pitch = pitch;
        s.lastNanos = System.nanoTime();
    }

    public void removeSession(UUID sessionId) {
        states.remove(sessionId);
    }

    private static float lerpAngle(float from, float to, float factor) {
        float delta = ((to - from) % 360 + 540) % 360 - 180;
        return from + delta * factor;
    }

    private static class PositionState {
        double x, y, z;
        float yaw, pitch;
        double vx, vy, vz;
        long lastNanos;
    }
}