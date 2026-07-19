package com.himnerd.shockbridge.translation;

import com.himnerd.shockbridge.ShockbridgePlugin;
import com.himnerd.shockbridge.identity.IdentityChainResult;
import com.himnerd.shockbridge.identity.MicrosoftAuthValidator;
import com.himnerd.shockbridge.network.TranslatedPacket;
import com.himnerd.shockbridge.network.VirtualSession;
import com.himnerd.shockbridge.util.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Dispatches individual Bedrock game packets to type-specific translators.
 * Handles the login handshake sequence via the session's LoginSequencer,
 * and translates gameplay packets into Java protocol equivalents.
 */
public class PacketTranslator {

    public static final int BEDROCK_LOGIN = 0x01;
    public static final int BEDROCK_RESOURCE_PACK_RESPONSE = 0x08;
    public static final int BEDROCK_TEXT = 0x09;
    public static final int BEDROCK_MOVE_PLAYER = 0x13;
    public static final int BEDROCK_INTERACT = 0x21;
    public static final int BEDROCK_PLAYER_ACTION = 0x24;
    public static final int BEDROCK_INVENTORY_TRANSACTION = 0x1E;
    public static final int BEDROCK_SET_LOCAL_PLAYER_INITIALIZED = 0x71;
    public static final int BEDROCK_REQUEST_NETWORK_SETTINGS = 0xC1;
    public static final int BEDROCK_RESOURCE_PACK_CHUNK_REQUEST = 0x54;
    public static final int BEDROCK_REQUEST_CHUNK_RADIUS = 0x45;

    private final ShockbridgePlugin plugin;
    @Getter private final MovementTranslator movementTranslator;

    public PacketTranslator(ShockbridgePlugin plugin) {
        this.plugin = plugin;
        this.movementTranslator = new MovementTranslator(plugin);
    }

    public TranslatedPacket translate(VirtualSession session, int bedrockPacketId, ByteBuf payload) {
        long start = System.nanoTime();

        TranslatedPacket result = switch (bedrockPacketId) {
            case BEDROCK_REQUEST_NETWORK_SETTINGS -> translateRequestNetworkSettings(session, payload);
            case BEDROCK_LOGIN -> translateLogin(session, payload);
            case BEDROCK_RESOURCE_PACK_RESPONSE -> translateResourcePackResponse(session, payload);
            case BEDROCK_RESOURCE_PACK_CHUNK_REQUEST -> translateResourcePackChunkRequest(session, payload);
            case BEDROCK_REQUEST_CHUNK_RADIUS -> translateRequestChunkRadius(session, payload);
            case BEDROCK_SET_LOCAL_PLAYER_INITIALIZED -> translatePlayerInitialized(session, payload);
            case BEDROCK_MOVE_PLAYER -> translateMovePlayer(session, payload);
            case BEDROCK_TEXT -> translateText(session, payload);
            default -> null;
        };

        long elapsed = System.nanoTime() - start;
        plugin.getDebugLogger().logTranslation(session.getSessionId(), bedrockPacketId, elapsed);

        return result;
    }

    // ── Login sequence ──────────────────────────────────────────────

    private TranslatedPacket translateRequestNetworkSettings(VirtualSession session, ByteBuf payload) {
        if (payload.readableBytes() < 4) return null;
        int protocolVersion = payload.readInt();
        session.setBedrockProtocol(protocolVersion);
        session.getLoginSequencer().handleRequestNetworkSettings(protocolVersion);
        return new TranslatedPacket(session.getSessionId(), -1, new byte[0], 0);
    }

    private TranslatedPacket translateLogin(VirtualSession session, ByteBuf payload) {
        if (payload.readableBytes() < 4) return null;
        int protocolVersion = payload.readInt();
        plugin.getDebugLogger().logConnection(session.getAddress(),
                "Login received protocol=" + protocolVersion);

        // Extract JWT identity chain and client data from remaining payload
        String chainJson = null;
        String clientDataJwt = null;
        try {
            if (payload.readableBytes() >= 4) {
                int chainLen = payload.readIntLE();
                if (chainLen > 0 && chainLen <= payload.readableBytes()) {
                    byte[] chainBytes = new byte[chainLen];
                    payload.readBytes(chainBytes);
                    chainJson = new String(chainBytes, StandardCharsets.UTF_8);
                }
            }
            if (payload.readableBytes() >= 4) {
                int clientDataLen = payload.readIntLE();
                if (clientDataLen > 0 && clientDataLen <= payload.readableBytes()) {
                    byte[] clientDataBytes = new byte[clientDataLen];
                    payload.readBytes(clientDataBytes);
                    clientDataJwt = new String(clientDataBytes, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            plugin.getDebugLogger().logException("LoginChainParse", e);
        }

        MicrosoftAuthValidator authValidator = plugin.getAuthValidator();
        if (chainJson != null && authValidator != null) {
            final String chain = chainJson;
            final String clientData = clientDataJwt;
            authValidator.validate(chain, clientData).thenAccept(result -> {
                session.executeOnNetworkThread(() -> {
                    session.setAuthResult(result);

                    if (result.getDisplayName() != null) {
                        session.setXboxGamertag(result.getDisplayName());
                    }
                    if (result.getXuid() != null) {
                        session.setXuid(result.getXuid());
                    }

                    // Enforce Microsoft auth if configured
                    if (plugin.getShockbridgeConfig().isRequireMicrosoftAuth() && !result.isAuthenticated()) {
                        plugin.getDebugLogger().logConnection(session.getAddress(),
                                "Authentication failed: " + result.getFailureReason());
                        session.disconnect("Microsoft authentication required");
                        return;
                    }

                    // Create performance profile from device info
                    if (plugin.getDynamicRenderController() != null) {
                        var profile = plugin.getDynamicRenderController().createProfile(
                                session.getSessionId(), result.getDeviceOsId(), result.getDeviceModel());
                        session.setPerformanceProfile(profile);
                    }

                    plugin.getDebugLogger().logConnection(session.getAddress(),
                            "Authenticated: " + result.getDisplayName() +
                            " (XUID=" + result.getXuid() + ", device=" + result.getDeviceOs() +
                            ", verified=" + result.isAuthenticated() + ")");

                    session.getLoginSequencer().handleLogin();
                });
            }).exceptionally(ex -> {
                plugin.getDebugLogger().logException("AuthCallback", ex);
                session.disconnect("Authentication error");
                return null;
            });
        } else {
            // No chain data — proceed without auth (trusts gamertag)
            session.getLoginSequencer().handleLogin();
        }

        return new TranslatedPacket(session.getSessionId(), -1, new byte[0], 0);
    }

    private TranslatedPacket translateResourcePackResponse(VirtualSession session, ByteBuf payload) {
        if (payload.readableBytes() < 1) return null;
        int status = payload.readByte() & 0xFF;
        session.getLoginSequencer().handleResourcePackResponse(status);
        return new TranslatedPacket(session.getSessionId(), -1, new byte[0], 0);
    }

    private TranslatedPacket translatePlayerInitialized(VirtualSession session, ByteBuf payload) {
        session.getLoginSequencer().handlePlayerInitialized();
        return new TranslatedPacket(session.getSessionId(), -1, new byte[0], 0);
    }

    private TranslatedPacket translateResourcePackChunkRequest(VirtualSession session, ByteBuf payload) {
        if (payload.readableBytes() < 1) return null;
        String packId = ByteBufUtils.readString(payload);
        int chunkIndex = payload.readableBytes() >= 4 ? payload.readIntLE() : 0;
        session.getLoginSequencer().handleResourcePackChunkRequest(packId, chunkIndex);
        return new TranslatedPacket(session.getSessionId(), -1, new byte[0], 0);
    }

    // ── Gameplay translators ────────────────────────────────────────

    private TranslatedPacket translateMovePlayer(VirtualSession session, ByteBuf payload) {
        if (payload.readableBytes() < 25) return null;

        ByteBufUtils.readVarLong(payload); // runtime entity ID

        float x = payload.readFloatLE();
        float y = payload.readFloatLE();
        float z = payload.readFloatLE();
        float pitch = payload.readFloatLE();
        float yaw = payload.readFloatLE();
        byte mode = payload.isReadable() ? payload.readByte() : 0;
        boolean onGround = payload.isReadable() && payload.readBoolean();

        double[] smoothed = movementTranslator.smooth(session.getSessionId(), x, y, z, yaw, pitch);

        // Java ServerboundMovePlayerPacket.PosRot
        byte[] javaPayload = new byte[33];
        ByteBuffer buf = ByteBuffer.wrap(javaPayload);
        buf.putDouble(smoothed[0]);
        buf.putDouble(smoothed[1]);
        buf.putDouble(smoothed[2]);
        buf.putFloat((float) smoothed[3]);
        buf.putFloat((float) smoothed[4]);
        buf.put(onGround ? (byte) 1 : (byte) 0);

        return new TranslatedPacket(session.getSessionId(), 0x1C, javaPayload, 0);
    }

    private TranslatedPacket translateText(VirtualSession session, ByteBuf payload) {
        return new TranslatedPacket(session.getSessionId(), -1, new byte[0], 0);
    }

    private TranslatedPacket translateRequestChunkRadius(VirtualSession session, ByteBuf payload) {
        if (payload.readableBytes() < 1) return null;
        int requestedRadius = ByteBufUtils.readSignedVarInt(payload);

        // Clamp to config bounds and send confirmation
        int min = plugin.getShockbridgeConfig().getMinRenderDistance();
        int max = plugin.getShockbridgeConfig().getMaxRenderDistance();
        int granted = Math.min(max, Math.max(min, requestedRadius));

        if (session.getPerformanceProfile() != null) {
            session.getPerformanceProfile().setCurrentRenderDistance(granted);
        }

        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer(4);
        ByteBufUtils.writeSignedVarInt(buf, granted);
        session.sendBedrockPacket(0x46, buf);

        return new TranslatedPacket(session.getSessionId(), -1, new byte[0], 0);
    }
}