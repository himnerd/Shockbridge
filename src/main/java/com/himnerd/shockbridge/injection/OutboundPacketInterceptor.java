package com.himnerd.shockbridge.injection;

import com.himnerd.shockbridge.ShockbridgePlugin;
import com.himnerd.shockbridge.network.VirtualSession;
import com.himnerd.shockbridge.util.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.nio.charset.StandardCharsets;

/**
 * Intercepts Java play-state packets received from Paper (via localhost TCP)
 * and translates them to Bedrock packets sent through the VirtualSession.
 * Also handles KeepAlive responses back to Paper.
 */
public class OutboundPacketInterceptor extends ChannelInboundHandlerAdapter {

    // Clientbound play packet IDs (protocol version dependent — update for target MC version)
    private static final int JAVA_BUNDLE_DELIMITER = 0x00;
    private static final int JAVA_BLOCK_UPDATE = 0x09;
    private static final int JAVA_DISCONNECT = 0x1D;
    private static final int JAVA_KEEP_ALIVE = 0x26;
    private static final int JAVA_CHUNK_DATA = 0x27;
    private static final int JAVA_GAME_EVENT = 0x22;
    private static final int JAVA_SYNC_PLAYER_POS = 0x42;
    private static final int JAVA_SET_TIME = 0x64;
    private static final int JAVA_SYSTEM_CHAT = 0x6F;
    private static final int JAVA_EXPLOSION = 0x20;
    private static final int JAVA_ENTITY_EVENT = 0x1E;
    private static final int JAVA_DAMAGE_EVENT = 0x1A;

    private final ShockbridgePlugin plugin;
    private final VirtualSession bedrockSession;

    public OutboundPacketInterceptor(ShockbridgePlugin plugin, VirtualSession bedrockSession) {
        this.plugin = plugin;
        this.bedrockSession = bedrockSession;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        try {
            if (buf.readableBytes() < 1) return;
            int packetId = readVarInt(buf);

            switch (packetId) {
                case JAVA_KEEP_ALIVE -> handleKeepAlive(ctx, buf);
                case JAVA_DISCONNECT -> handleDisconnect(buf);
                case JAVA_SYSTEM_CHAT -> handleSystemChat(buf);
                case JAVA_SYNC_PLAYER_POS -> handlePlayerPosition(ctx, buf);
                case JAVA_SET_TIME -> handleSetTime(buf);
                case JAVA_BLOCK_UPDATE -> handleBlockUpdate(buf);
                case JAVA_CHUNK_DATA -> handleChunkData(buf);
                case JAVA_EXPLOSION -> handleExplosion(buf);
                default -> {
                    // Unhandled packets are silently dropped for now
                    // Chunk, entity, and inventory packets will be added incrementally
                }
            }
        } catch (Exception e) {
            plugin.getDebugLogger().logException("OutboundInterceptor", e);
        } finally {
            if (buf.refCnt() > 0) buf.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        bedrockSession.disconnect("Java session closed");
        if (plugin.getJavaSessionInjector() != null) {
            plugin.getJavaSessionInjector().removeSession(bedrockSession.getSessionId());
        }
    }

    private void handleKeepAlive(ChannelHandlerContext ctx, ByteBuf buf) {
        long id = buf.readLong();

        // Respond to Paper to keep the Java connection alive
        ByteBuf response = Unpooled.buffer(9);
        writeVarInt(response, 0x18);
        response.writeLong(id);
        ctx.writeAndFlush(response);

        // Forward to Bedrock as NetworkStackLatency
        ByteBuf bedrock = Unpooled.buffer(9);
        bedrock.writeLongLE(id);
        bedrock.writeBoolean(true);
        bedrockSession.sendBedrockPacket(0xC5, bedrock);
    }

    private void handleDisconnect(ByteBuf buf) {
        String chatJson = readString(buf);
        String message = extractPlainText(chatJson);

        ByteBuf bedrock = Unpooled.buffer();
        bedrock.writeIntLE(0);
        ByteBufUtils.writeString(bedrock, message);
        bedrockSession.sendBedrockPacket(0x05, bedrock);
    }

    private void handleSystemChat(ByteBuf buf) {
        String chatJson = readString(buf);
        boolean overlay = buf.isReadable() && buf.readBoolean();
        String message = extractPlainText(chatJson);

        ByteBuf bedrock = Unpooled.buffer();
        bedrock.writeByte(overlay ? 4 : 0); // type: 0=raw, 4=tip/actionbar
        bedrock.writeBoolean(false);         // needs translation
        ByteBufUtils.writeString(bedrock, "");
        ByteBufUtils.writeString(bedrock, message);
        ByteBufUtils.writeString(bedrock, "");
        bedrockSession.sendBedrockPacket(0x09, bedrock);
    }

    private void handlePlayerPosition(ChannelHandlerContext ctx, ByteBuf buf) {
        if (buf.readableBytes() < 28) return;

        int teleportId = readVarInt(buf);
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();

        // Skip velocity deltas if present (3 doubles)
        if (buf.readableBytes() >= 24) {
            buf.skipBytes(24);
        }

        float yaw = buf.isReadable(4) ? buf.readFloat() : 0f;
        float pitch = buf.isReadable(4) ? buf.readFloat() : 0f;

        // Confirm teleportation back to Paper
        ByteBuf confirm = Unpooled.buffer(5);
        writeVarInt(confirm, 0x00);
        writeVarInt(confirm, teleportId);
        ctx.writeAndFlush(confirm);

        // Send MovePlayer to Bedrock
        ByteBuf bedrock = Unpooled.buffer(64);
        ByteBufUtils.writeVarLong(bedrock, 1L);
        bedrock.writeFloatLE((float) x);
        bedrock.writeFloatLE((float) y);
        bedrock.writeFloatLE((float) z);
        bedrock.writeFloatLE(pitch);
        bedrock.writeFloatLE(yaw);
        bedrock.writeFloatLE(yaw);
        bedrock.writeByte(2); // teleport mode
        bedrock.writeBoolean(true);
        ByteBufUtils.writeVarLong(bedrock, 0L);
        bedrock.writeIntLE(0);
        bedrock.writeIntLE(0);
        bedrock.writeLongLE(0L);
        bedrockSession.sendBedrockPacket(0x13, bedrock);
    }

    private void handleSetTime(ByteBuf buf) {
        if (buf.readableBytes() < 16) return;
        buf.readLong(); // world age (not needed for Bedrock)
        long timeOfDay = buf.readLong();

        ByteBuf bedrock = Unpooled.buffer(4);
        ByteBufUtils.writeSignedVarInt(bedrock, (int) (timeOfDay % 24000));
        bedrockSession.sendBedrockPacket(0x0A, bedrock);
    }

    private void handleBlockUpdate(ByteBuf buf) {
        long position = buf.readLong();
        int blockStateId = readVarInt(buf);

        int x = (int) (position >> 38);
        int y = (int) ((position >> 12) & 0xFFF);
        if (y >= 2048) y -= 4096; // sign extend 12-bit
        int z = (int) ((position << 26) >> 38);

        int bedrockId = plugin.getBlockPalette() != null
                ? plugin.getBlockPalette().toBedrock(blockStateId) : blockStateId;

        ByteBuf bedrock = Unpooled.buffer(32);
        ByteBufUtils.writeSignedVarInt(bedrock, x);
        ByteBufUtils.writeVarInt(bedrock, y);
        ByteBufUtils.writeSignedVarInt(bedrock, z);
        ByteBufUtils.writeVarInt(bedrock, bedrockId);
        ByteBufUtils.writeVarInt(bedrock, 3); // flags: network + priority
        ByteBufUtils.writeVarInt(bedrock, 0); // layer 0
        bedrockSession.sendBedrockPacket(0x15, bedrock);
    }

    private void handleChunkData(ByteBuf buf) {
        if (buf.readableBytes() < 8) return;

        int chunkX = buf.readInt();
        int chunkZ = buf.readInt();

        // Cache the raw chunk data off-heap
        if (plugin.getAsyncChunkCache() != null) {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);
            plugin.getAsyncChunkCache().cache(
                    bedrockSession.getSessionId(), chunkX, chunkZ, data);
        }

        // Send as Bedrock LevelChunk — simplified translation
        ByteBuf bedrock = Unpooled.buffer(buf.readableBytes() + 32);
        ByteBufUtils.writeSignedVarInt(bedrock, chunkX);
        ByteBufUtils.writeSignedVarInt(bedrock, chunkZ);
        ByteBufUtils.writeVarInt(bedrock, 0);  // dimension
        ByteBufUtils.writeVarInt(bedrock, 4);  // sub-chunk count
        bedrock.writeBoolean(false);           // cache enabled

        // Write chunk payload (block palette translation needed for full implementation)
        ByteBufUtils.writeVarInt(bedrock, buf.readableBytes());
        bedrock.writeBytes(buf);

        bedrockSession.sendBedrockPacket(0x3A, bedrock);

        // Update trajectory predictor
        updateChunkTrajectory(chunkX, chunkZ);
    }

    private void handleExplosion(ByteBuf buf) {
        if (buf.readableBytes() < 24) return;

        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        float power = buf.isReadable(4) ? buf.readFloat() : 4.0f;

        // Trigger camera shake on Bedrock client for visceral feedback
        if (plugin.getShockbridgeConfig().isHapticsEnabled()) {
            float intensity = (float) (Math.min(power / 6.0f, 1.0f)
                    * plugin.getShockbridgeConfig().getCameraShakeIntensity());
            float duration = Math.min(power * 0.15f, 2.0f);

            ByteBuf shake = Unpooled.buffer(16);
            shake.writeFloatLE(intensity);
            shake.writeFloatLE(duration);
            shake.writeByte(0);  // type: positional
            shake.writeByte(0);  // action: add
            bedrockSession.sendBedrockPacket(0x9F, shake);
        }

        // Forward explosion to Bedrock as Explode packet
        ByteBuf bedrock = Unpooled.buffer(64);
        bedrock.writeFloatLE((float) x);
        bedrock.writeFloatLE((float) y);
        bedrock.writeFloatLE((float) z);
        ByteBufUtils.writeSignedVarInt(bedrock, (int) (power * 100));
        ByteBufUtils.writeVarInt(bedrock, 0); // records count
        bedrockSession.sendBedrockPacket(0x17, bedrock);
    }

    private void updateChunkTrajectory(int chunkX, int chunkZ) {
        if (plugin.getAsyncChunkCache() == null) return;
        var predictor = plugin.getAsyncChunkCache().getTrajectoryPredictor();
        if (predictor != null) {
            predictor.recordChunkLoad(bedrockSession.getSessionId(), chunkX, chunkZ);
        }

        // Dynamic render distance adjustment
        var drc = plugin.getDynamicRenderController();
        var profile = bedrockSession.getPerformanceProfile();
        if (drc != null && profile != null) {
            int newRadius = drc.calculateOptimalRadius(profile);
            if (newRadius != profile.getCurrentRenderDistance()) {
                profile.setCurrentRenderDistance(newRadius);
                ByteBuf radiusBuf = Unpooled.buffer(4);
                ByteBufUtils.writeSignedVarInt(radiusBuf, newRadius);
                bedrockSession.sendBedrockPacket(0x46, radiusBuf);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────

    private static String extractPlainText(String chatJson) {
        if (chatJson == null || chatJson.isEmpty()) return "";
        if (chatJson.startsWith("\"") && chatJson.endsWith("\"")) {
            return chatJson.substring(1, chatJson.length() - 1);
        }
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(chatJson).getAsJsonObject();
            if (obj.has("text")) return obj.get("text").getAsString();
            if (obj.has("translate")) return obj.get("translate").getAsString();
        } catch (Exception ignored) {
        }
        return chatJson.length() > 256 ? chatJson.substring(0, 256) : chatJson;
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0, shift = 0;
        byte b;
        do {
            b = buf.readByte();
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value & 0x7F);
    }

    private static String readString(ByteBuf buf) {
        int len = readVarInt(buf);
        if (len < 0 || len > buf.readableBytes()) return "";
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}