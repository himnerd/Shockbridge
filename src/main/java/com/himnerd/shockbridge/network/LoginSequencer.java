package com.himnerd.shockbridge.network;

import com.himnerd.shockbridge.ShockbridgePlugin;
import com.himnerd.shockbridge.linking.AccountLinker;
import com.himnerd.shockbridge.linking.ConcurrentLoginGuard;
import com.himnerd.shockbridge.linking.LinkedAccount;
import com.himnerd.shockbridge.resourcepack.ResourcePackBridge;
import com.himnerd.shockbridge.translation.ShockbridgeMappingRegistry;
import com.himnerd.shockbridge.translation.ShockbridgeMappingRegistry.CustomItemState;
import com.himnerd.shockbridge.util.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/**
 * Drives the Bedrock login handshake for a single session:
 * RequestNetworkSettings → NetworkSettings → Login → PlayStatus → ResourcePacks → StartGame → Spawn.
 * Handles resource pack transfer when a converted Bedrock pack is available.
 * All methods run on the Netty IO thread that owns the parent VirtualSession.
 */
public class LoginSequencer {

    private static final int PACK_CHUNK_SIZE = 1048576; // 1 MB per chunk

    public enum State {
        AWAITING_NETWORK_SETTINGS,
        AWAITING_LOGIN,
        AWAITING_RESOURCE_RESPONSE,
        DOWNLOADING_PACKS,
        AWAITING_RESOURCE_COMPLETED,
        AWAITING_SPAWN,
        SPAWNED
    }

    private final ShockbridgePlugin plugin;
    private final VirtualSession session;
    @Getter private State state = State.AWAITING_NETWORK_SETTINGS;

    public LoginSequencer(ShockbridgePlugin plugin, VirtualSession session) {
        this.plugin = plugin;
        this.session = session;
    }

    public void handleRequestNetworkSettings(int clientProtocol) {
        ByteBuf buf = Unpooled.buffer(16);
        buf.writeShortLE(plugin.getShockbridgeConfig().getCompressionThreshold());
        buf.writeShortLE(0);         // compression algorithm: zlib
        buf.writeBoolean(false);     // client throttle enabled
        buf.writeByte(0);            // client throttle threshold
        buf.writeFloatLE(0.0f);      // client throttle scalar
        session.sendBedrockPacket(0xA3, buf);
        session.setCompressionEnabled(true);

        state = State.AWAITING_LOGIN;
        plugin.getDebugLogger().logConnection(session.getAddress(),
                "Sent NetworkSettings — protocol=" + clientProtocol);
    }

    public void handleLogin() {
        sendPlayStatus(0); // LOGIN_SUCCESS
        sendResourcePacksInfo();
        state = State.AWAITING_RESOURCE_RESPONSE;
        plugin.getDebugLogger().logConnection(session.getAddress(), "Login accepted");
    }

    public void handleResourcePackResponse(int status) {
        switch (status) {
            case 2 -> { // SEND_PACKS
                sendResourcePackDataInfo();
                state = State.DOWNLOADING_PACKS;
            }
            case 3 -> { // HAVE_ALL_PACKS
                sendResourcePackStack();
                state = State.AWAITING_RESOURCE_COMPLETED;
            }
            case 4 -> { // COMPLETED
                sendStartGame();
                sendBiomeDefinitionList();
                sendAvailableEntityIdentifiers();
                sendCreativeContent();
                sendPlayStatus(3); // PLAYER_SPAWN
                state = State.AWAITING_SPAWN;
            }
            default -> plugin.getDebugLogger().logConnection(session.getAddress(),
                    "Unhandled resource pack response: " + status);
        }
    }

    public void handleResourcePackChunkRequest(String packId, int chunkIndex) {
        sendResourcePackChunk(chunkIndex);
    }

    public void handlePlayerInitialized() {
        state = State.SPAWNED;
        plugin.getDebugLogger().logConnection(session.getAddress(), "Player fully initialized");

        if (plugin.getJavaSessionInjector() != null) {
            String gamertag = session.getXboxGamertag();
            if (gamertag == null || gamertag.isEmpty()) gamertag = "Bedrock" + session.getSessionId().toString().substring(0, 8);

            // Resolve UUID: linked account takes priority, otherwise generate from gamertag
            UUID playerUuid;
            String xuid = session.getXuid();
            AccountLinker linker = plugin.getAccountLinker();

            if (xuid != null && linker != null) {
                LinkedAccount linked = linker.getByXuid(xuid);
                if (linked != null) {
                    playerUuid = linked.getJavaUuid();
                    session.setLinkedAccount(linked);
                    plugin.getDebugLogger().logConnection(session.getAddress(),
                            "Account linked: XUID " + xuid + " → Java UUID " + playerUuid);
                } else {
                    playerUuid = UUID.nameUUIDFromBytes(("Shockbridge:" + gamertag).getBytes(StandardCharsets.UTF_8));
                }
            } else {
                playerUuid = UUID.nameUUIDFromBytes(("Shockbridge:" + gamertag).getBytes(StandardCharsets.UTF_8));
            }

            // Check concurrent login guard
            ConcurrentLoginGuard guard = plugin.getConcurrentLoginGuard();
            if (guard != null && !guard.tryAcquire(session.getSessionId(), playerUuid)) {
                session.disconnect("Account already in use from another session");
                return;
            }

            // Start real-time state sync if account is linked
            if (session.getLinkedAccount() != null && plugin.getStateSynchronizer() != null) {
                plugin.getStateSynchronizer().beginSync(session.getSessionId(), playerUuid);
            }

            plugin.getJavaSessionInjector().inject(session, gamertag, playerUuid);
        }
    }

    // ── Resource pack transfer ─────────────────────────────

    private ResourcePackBridge getPackBridge() {
        return plugin.getResourcePackBridge();
    }

    private boolean hasConvertedPack() {
        ResourcePackBridge rpb = getPackBridge();
        return rpb != null && rpb.isPackReady();
    }

    private void sendResourcePackDataInfo() {
        ResourcePackBridge rpb = getPackBridge();
        if (rpb == null || !rpb.isPackReady()) return;

        byte[] data = rpb.getBedrockPackData();
        int chunkCount = (int) Math.ceil((double) data.length / PACK_CHUNK_SIZE);

        ByteBuf buf = Unpooled.buffer(128);
        ByteBufUtils.writeString(buf, rpb.getPackUuid().toString());
        buf.writeIntLE(PACK_CHUNK_SIZE);
        buf.writeIntLE(chunkCount);
        buf.writeLongLE(data.length);
        byte[] hash = rpb.getPackHash();
        ByteBufUtils.writeVarInt(buf, hash.length);
        buf.writeBytes(hash);
        buf.writeBoolean(false); // not premium
        buf.writeByte(1);        // pack type: resources
        session.sendBedrockPacket(0x52, buf);

        plugin.getDebugLogger().logConnection(session.getAddress(),
                "Sent ResourcePackDataInfo — " + chunkCount + " chunks, " + data.length + " bytes");
    }

    private void sendResourcePackChunk(int chunkIndex) {
        ResourcePackBridge rpb = getPackBridge();
        if (rpb == null || !rpb.isPackReady()) return;

        byte[] data = rpb.getBedrockPackData();
        int offset = chunkIndex * PACK_CHUNK_SIZE;
        int length = Math.min(PACK_CHUNK_SIZE, data.length - offset);
        if (offset >= data.length || length <= 0) return;

        ByteBuf buf = Unpooled.buffer(length + 64);
        ByteBufUtils.writeString(buf, rpb.getPackUuid().toString());
        buf.writeIntLE(chunkIndex);
        buf.writeLongLE(offset);
        buf.writeIntLE(length);
        buf.writeBytes(data, offset, length);
        session.sendBedrockPacket(0x53, buf);

        plugin.getDebugLogger().logConnection(session.getAddress(),
                "Sent chunk " + chunkIndex + " (" + length + " bytes)");
    }

    // ── Packet senders ──────────────────────────────────────

    private void sendPlayStatus(int status) {
        ByteBuf buf = Unpooled.buffer(4);
        buf.writeInt(status);
        session.sendBedrockPacket(0x02, buf);
    }

    private void sendResourcePacksInfo() {
        boolean hasPack = hasConvertedPack();
        ResourcePackBridge rpb = getPackBridge();

        ByteBuf buf = Unpooled.buffer(64);
        buf.writeBoolean(hasPack);          // must accept
        buf.writeBoolean(false);            // has addon packs
        buf.writeBoolean(false);            // scripting enabled
        buf.writeBoolean(false);            // force server packs
        buf.writeShortLE(0);                // behavior pack count
        buf.writeShortLE(hasPack ? 1 : 0);  // resource pack count

        if (hasPack) {
            ByteBufUtils.writeString(buf, rpb.getPackUuid().toString());
            ByteBufUtils.writeString(buf, rpb.getPackVersion());
            buf.writeLongLE(rpb.getBedrockPackData().length);
            ByteBufUtils.writeString(buf, "");   // content key
            ByteBufUtils.writeString(buf, "");   // sub pack name
            ByteBufUtils.writeString(buf, "");   // content id
            buf.writeBoolean(false);             // has scripts
            buf.writeBoolean(false);             // raytracing capable
            buf.writeBoolean(false);             // is addon pack
        }

        session.sendBedrockPacket(0x06, buf);
    }

    private void sendResourcePackStack() {
        boolean hasPack = hasConvertedPack();
        ResourcePackBridge rpb = getPackBridge();

        ByteBuf buf = Unpooled.buffer(32);
        buf.writeBoolean(false);
        ByteBufUtils.writeVarInt(buf, 0); // behavior pack count
        ByteBufUtils.writeVarInt(buf, hasPack ? 1 : 0); // resource pack count

        if (hasPack) {
            ByteBufUtils.writeString(buf, rpb.getPackUuid().toString());
            ByteBufUtils.writeString(buf, rpb.getPackVersion());
            ByteBufUtils.writeString(buf, ""); // sub pack name
        }

        ByteBufUtils.writeString(buf, "*");
        buf.writeIntLE(0);                // experiments count
        buf.writeBoolean(false);          // experiments previously used
        session.sendBedrockPacket(0x07, buf);
    }

    private void sendStartGame() {
        ByteBuf buf = Unpooled.buffer(1024);

        // Entity
        ByteBufUtils.writeSignedVarLong(buf, 1L);
        ByteBufUtils.writeVarLong(buf, 1L);
        ByteBufUtils.writeSignedVarInt(buf, 0);

        // Position
        buf.writeFloatLE(0.0f);
        buf.writeFloatLE(64.0f);
        buf.writeFloatLE(0.0f);

        // Rotation
        buf.writeFloatLE(0.0f);
        buf.writeFloatLE(0.0f);

        // ─── Level settings ─────────────────────────────────────
        ByteBufUtils.writeSignedVarLong(buf, 0L);
        buf.writeShortLE(0);
        ByteBufUtils.writeString(buf, "");
        ByteBufUtils.writeSignedVarInt(buf, 0);       // dimension
        ByteBufUtils.writeSignedVarInt(buf, 1);       // generator (infinite)
        ByteBufUtils.writeSignedVarInt(buf, 0);       // world game type
        buf.writeBoolean(false);                      // hardcore
        ByteBufUtils.writeSignedVarInt(buf, 1);       // difficulty
        ByteBufUtils.writeSignedVarInt(buf, 0);       // spawn X
        ByteBufUtils.writeVarInt(buf, 64);            // spawn Y
        ByteBufUtils.writeSignedVarInt(buf, 0);       // spawn Z
        buf.writeBoolean(true);                       // achievements disabled
        ByteBufUtils.writeSignedVarInt(buf, 0);       // editor world type
        buf.writeBoolean(false);                      // created in editor
        buf.writeBoolean(false);                      // exported from editor
        ByteBufUtils.writeSignedVarInt(buf, -1);      // day cycle stop
        ByteBufUtils.writeSignedVarInt(buf, 0);       // edu offer
        buf.writeBoolean(false);                      // edu features
        ByteBufUtils.writeString(buf, "");            // edu product UUID
        buf.writeFloatLE(0.0f);                       // rain level
        buf.writeFloatLE(0.0f);                       // lightning level
        buf.writeBoolean(false);                      // platform locked
        buf.writeBoolean(true);                       // multiplayer
        buf.writeBoolean(true);                       // broadcast LAN
        ByteBufUtils.writeVarInt(buf, 4);             // XBL broadcast
        ByteBufUtils.writeVarInt(buf, 4);             // platform broadcast
        buf.writeBoolean(true);                       // commands enabled
        buf.writeBoolean(hasConvertedPack());         // texture packs required
        ByteBufUtils.writeVarInt(buf, 0);             // game rules count
        buf.writeIntLE(0);                            // experiments count
        buf.writeBoolean(false);                      // experiments previously used
        buf.writeBoolean(false);                      // bonus chest
        buf.writeBoolean(false);                      // map enabled
        ByteBufUtils.writeSignedVarInt(buf, 1);       // permission level
        buf.writeIntLE(4);                            // server chunk tick range
        buf.writeBoolean(false);                      // locked behavior pack
        buf.writeBoolean(false);                      // locked resource pack
        buf.writeBoolean(false);                      // from locked template
        buf.writeBoolean(false);                      // MSA gamertags only
        buf.writeBoolean(false);                      // from world template
        buf.writeBoolean(false);                      // template option locked
        buf.writeBoolean(false);                      // only V1 villagers
        buf.writeBoolean(false);                      // disabling personas
        buf.writeBoolean(false);                      // disabling custom skins
        buf.writeBoolean(false);                      // emote chat muted
        ByteBufUtils.writeString(buf, "*");           // vanilla version
        buf.writeIntLE(0);                            // limited world width
        buf.writeIntLE(0);                            // limited world length
        buf.writeBoolean(true);                       // new nether
        ByteBufUtils.writeString(buf, "");            // edu URI button
        ByteBufUtils.writeString(buf, "");            // edu URI link
        buf.writeBoolean(false);                      // experimental override (hasValue=false)
        buf.writeByte(0);                             // chat restriction level
        buf.writeBoolean(false);                      // disable player interactions
        // ─── End level settings ─────────────────────────────────

        ByteBufUtils.writeString(buf, "");            // level ID
        ByteBufUtils.writeString(buf, "Shockbridge"); // level name
        ByteBufUtils.writeString(buf, "");            // premium template ID
        buf.writeBoolean(false);                      // is trial

        // Player movement settings
        ByteBufUtils.writeSignedVarInt(buf, 0);       // authority: client
        ByteBufUtils.writeSignedVarInt(buf, 0);       // rewind history
        buf.writeBoolean(false);                      // server auth block breaking

        buf.writeLongLE(0L);                          // current tick
        ByteBufUtils.writeSignedVarInt(buf, 0);       // enchantment seed

        ByteBufUtils.writeVarInt(buf, 0);             // block properties count

        // Custom item states from the asset scan mapping registry
        ShockbridgeMappingRegistry registry = plugin.getMappingRegistry();
        List<CustomItemState> itemStates = (registry != null) ? registry.getCustomItemStates() : List.of();
        ByteBufUtils.writeVarInt(buf, itemStates.size());
        for (CustomItemState state : itemStates) {
            ByteBufUtils.writeString(buf, state.identifier());
            buf.writeShortLE((short) state.runtimeId());
            buf.writeBoolean(state.componentBased());
        }

        ByteBufUtils.writeString(buf, UUID.randomUUID().toString());
        buf.writeBoolean(false);                      // inventory server auth
        ByteBufUtils.writeString(buf, "Shockbridge"); // server engine

        writeEmptyCompoundTag(buf);                   // player property data

        buf.writeLongLE(0L);                          // block registry checksum
        ByteBufUtils.writeString(buf, "00000000-0000-0000-0000-000000000000");

        buf.writeBoolean(false);                      // client side generation
        buf.writeBoolean(false);                      // block IDs are hashes
        buf.writeBoolean(false);                      // server auth sounds

        session.sendBedrockPacket(0x0B, buf);
    }

    private void sendBiomeDefinitionList() {
        ByteBuf buf = Unpooled.buffer(8);
        writeEmptyCompoundTag(buf);
        session.sendBedrockPacket(0x7A, buf);
    }

    private void sendAvailableEntityIdentifiers() {
        ByteBuf buf = Unpooled.buffer(8);
        writeEmptyCompoundTag(buf);
        session.sendBedrockPacket(0x77, buf);
    }

    private void sendCreativeContent() {
        ByteBuf buf = Unpooled.buffer(4);
        ByteBufUtils.writeVarInt(buf, 0);
        session.sendBedrockPacket(0x91, buf);
    }

    // ── Bedrock Network-NBT helpers (VarInt string lengths) ─────

    private static void writeEmptyCompoundTag(ByteBuf buf) {
        buf.writeByte(0x0A); // TAG_Compound
        buf.writeByte(0x00); // name length (VarInt = 0)
        buf.writeByte(0x00); // TAG_End
    }
}