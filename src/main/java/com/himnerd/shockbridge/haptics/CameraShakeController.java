package com.himnerd.shockbridge.haptics;

import com.himnerd.shockbridge.network.VirtualSession;
import com.himnerd.shockbridge.util.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Dispatches native Bedrock camera instruction packets for visceral feedback.
 * Supports camera shakes (explosions, impacts), controller haptics (via the
 * camera shake system which Bedrock automatically maps to controller vibration),
 * and programmatic camera offset for environmental effects.
 *
 * Bedrock CameraShake packet (0x9F):
 *   float LE: intensity (0.0–4.0)
 *   float LE: duration in seconds
 *   byte: shakeType (0=positional, 1=rotational)
 *   byte: action (0=add, 1=stop)
 */
public class CameraShakeController {

    public static final byte SHAKE_POSITIONAL = 0;
    public static final byte SHAKE_ROTATIONAL = 1;
    public static final byte ACTION_ADD = 0;
    public static final byte ACTION_STOP = 1;

    /**
     * Send a camera shake to a Bedrock client.
     */
    public static void shake(VirtualSession session, float intensity, float duration,
                             byte shakeType, byte action) {
        ByteBuf buf = Unpooled.buffer(10);
        buf.writeFloatLE(Math.max(0f, Math.min(4f, intensity)));
        buf.writeFloatLE(Math.max(0f, duration));
        buf.writeByte(shakeType);
        buf.writeByte(action);
        session.sendBedrockPacket(0x9F, buf);
    }

    /**
     * Positional shake scaled to explosion power. Feels like ground impact.
     */
    public static void explosionShake(VirtualSession session, float power, double intensityMultiplier) {
        float intensity = (float) (Math.min(power / 4.0f, 1.5f) * intensityMultiplier);
        float duration = Math.min(power * 0.2f, 2.5f);
        shake(session, intensity, duration, SHAKE_POSITIONAL, ACTION_ADD);
    }

    /**
     * Rotational shake for critical hits and heavy melee impacts.
     */
    public static void criticalHitShake(VirtualSession session, double intensityMultiplier) {
        float intensity = (float) (0.3f * intensityMultiplier);
        shake(session, intensity, 0.15f, SHAKE_ROTATIONAL, ACTION_ADD);
    }

    /**
     * Environmental shake for custom events (earthquakes, spell effects, etc.)
     */
    public static void environmentalShake(VirtualSession session, float intensity,
                                          float duration, double intensityMultiplier) {
        float adjusted = (float) (intensity * intensityMultiplier);
        shake(session, adjusted, duration, SHAKE_POSITIONAL, ACTION_ADD);
    }

    /**
     * Stop all active camera shakes on this client.
     */
    public static void stopAll(VirtualSession session) {
        shake(session, 0f, 0f, SHAKE_POSITIONAL, ACTION_STOP);
    }
}