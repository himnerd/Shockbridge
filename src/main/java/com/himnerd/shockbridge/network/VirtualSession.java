package com.himnerd.shockbridge.network;

import com.himnerd.shockbridge.ShockbridgePlugin;
import com.himnerd.shockbridge.translation.PacketTranslator;
import com.himnerd.shockbridge.util.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import lombok.Getter;
import lombok.Setter;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Represents a single connected Bedrock client. Owns the full inbound pipeline:
 * RakNet reliability → frame reassembly → batch decompression → packet translation → MPSC queue.
 * All inbound processing runs on the Netty IO thread; translated results cross to the main thread
 * exclusively through the PacketQueue.
 */
public class VirtualSession {

    @Getter private final UUID sessionId;
    @Getter private final InetSocketAddress address;
    private final int mtuSize;
    private final ShockbridgePlugin plugin;
    private final ChannelHandlerContext networkCtx;
    private final PacketTranslator translator;
    private final Inflater inflater = new Inflater();
    @Getter private final LoginSequencer loginSequencer;

    // RakNet reliability sequence counters
    private int expectedSequenceNumber;
    private int sendSequenceNumber;
    private int sendReliableIndex;
    private int sendOrderIndex;

    // Split packet reassembly buffers
    private final Map<Integer, Map<Integer, byte[]>> splitPackets = new HashMap<>();
    private final Map<Integer, Integer> splitCounts = new HashMap<>();

    // Sequence deduplication — prevents reprocessing retransmitted frames
    private final Set<Integer> processedSequenceNumbers = new HashSet<>();

    @Getter private volatile boolean connected = true;
    @Getter @Setter private String xboxGamertag;
    @Getter @Setter private String xuid;
    @Getter @Setter private Channel injectionChannel;
    @Getter @Setter private int bedrockProtocol;
    @Getter @Setter private com.himnerd.shockbridge.identity.IdentityChainResult authResult;
    @Getter @Setter private com.himnerd.shockbridge.linking.LinkedAccount linkedAccount;
    @Getter @Setter private com.himnerd.shockbridge.render.ClientPerformanceProfile performanceProfile;
    @Getter @Setter private volatile boolean compressionEnabled;
    private boolean rakNetHandshakeComplete;

    public VirtualSession(ShockbridgePlugin plugin, UUID sessionId, InetSocketAddress address,
                          int mtuSize, ChannelHandlerContext networkCtx) {
        this.plugin = plugin;
        this.sessionId = sessionId;
        this.address = address;
        this.mtuSize = mtuSize;
        this.networkCtx = networkCtx;
        this.translator = new PacketTranslator(plugin);
        this.loginSequencer = new LoginSequencer(plugin, this);
    }

    /**
     * Entry point from RakNetHandler. Ownership of {@code buf} transfers here;
     * this method guarantees release via try-finally.
     */
    public void handleDatagram(ByteBuf buf) {
        if (!connected) {
            ReferenceCountUtil.safeRelease(buf);
            return;
        }
        try {
            byte flags = buf.readByte();

            // Bit 6 set → ACK
            if ((flags & 0x40) != 0) {
                handleAck(buf);
                return;
            }
            // Bit 5 set (and not ACK) → NACK
            if ((flags & 0x20) != 0) {
                handleNack(buf);
                return;
            }

            // Data frame
            int sequenceNumber = buf.readUnsignedMediumLE();
            sendAck(sequenceNumber);

            if (!processedSequenceNumbers.add(sequenceNumber)) {
                return;
            }
            if (processedSequenceNumbers.size() > 2048) {
                int threshold = sequenceNumber - 1024;
                processedSequenceNumbers.removeIf(seq -> seq < threshold);
            }

            while (buf.isReadable()) {
                decodeEncapsulated(buf);
            }
        } catch (Exception e) {
            plugin.getDebugLogger().logException("VirtualSession[" + sessionId + "]", e);
        } finally {
            ReferenceCountUtil.safeRelease(buf);
        }
    }

    private void decodeEncapsulated(ByteBuf buf) {
        if (buf.readableBytes() < 3) return;

        byte flags = buf.readByte();
        int reliability = (flags & 0xE0) >> 5;
        boolean hasSplit = (flags & 0x10) != 0;

        int lengthBits = buf.readUnsignedShort();
        int lengthBytes = (int) Math.ceil(lengthBits / 8.0);

        // Skip reliability-specific headers
        boolean isReliable = reliability >= 2 && reliability <= 7 && reliability != 5;
        boolean isSequenced = reliability == 1 || reliability == 4;
        boolean isOrdered = reliability == 1 || reliability == 3 || reliability == 4 || reliability == 7;

        if (isReliable) buf.skipBytes(3);   // 24-bit reliable message number
        if (isSequenced) buf.skipBytes(3);  // 24-bit sequencing index
        if (isOrdered) buf.skipBytes(4);    // 24-bit ordering index + 8-bit channel

        int splitCount = 0, splitId = 0, splitIndex = 0;
        if (hasSplit) {
            splitCount = buf.readInt();
            splitId = buf.readUnsignedShort();
            splitIndex = buf.readInt();
        }

        if (lengthBytes <= 0 || buf.readableBytes() < lengthBytes) return;

        ByteBuf payload;
        if (hasSplit) {
            byte[] partData = new byte[lengthBytes];
            buf.readBytes(partData);
            payload = handleSplitPacket(splitId, splitIndex, splitCount, partData);
            if (payload == null) return;
        } else {
            payload = buf.readRetainedSlice(lengthBytes);
        }

        try {
            handleEncapsulatedPayload(payload);
        } finally {
            ReferenceCountUtil.safeRelease(payload);
        }
    }

    private ByteBuf handleSplitPacket(int splitId, int splitIndex, int splitCount, byte[] partData) {
        Map<Integer, byte[]> parts = splitPackets.computeIfAbsent(splitId, k -> new HashMap<>());
        parts.put(splitIndex, partData);
        splitCounts.put(splitId, splitCount);

        if (parts.size() < splitCount) return null;

        // Reassemble in order
        int totalLength = 0;
        for (int i = 0; i < splitCount; i++) {
            byte[] part = parts.get(i);
            if (part == null) {
                splitPackets.remove(splitId);
                splitCounts.remove(splitId);
                return null;
            }
            totalLength += part.length;
        }

        byte[] assembled = new byte[totalLength];
        int offset = 0;
        for (int i = 0; i < splitCount; i++) {
            byte[] part = parts.get(i);
            System.arraycopy(part, 0, assembled, offset, part.length);
            offset += part.length;
        }

        splitPackets.remove(splitId);
        splitCounts.remove(splitId);

        return Unpooled.wrappedBuffer(assembled);
    }

    private void handleEncapsulatedPayload(ByteBuf payload) {
        if (payload.readableBytes() < 1) return;

        int firstByte = payload.getUnsignedByte(payload.readerIndex());

        if (firstByte == 0xFE) {
            payload.readByte(); // consume 0xFE marker
            decompressAndProcess(payload);
        } else {
            byte packetId = payload.readByte();
            handleRakNetInternal(packetId, payload);
        }
    }

    // ── RakNet-level internal packets ─────────────────────────────────

    private void handleRakNetInternal(byte packetId, ByteBuf payload) {
        switch (packetId & 0xFF) {
            case 0x09 -> handleConnectionRequest(payload);
            case 0x13 -> {
                rakNetHandshakeComplete = true;
                plugin.getDebugLogger().logConnection(address, "RakNet handshake complete");
            }
            case 0x00 -> handleConnectedPing(payload);
            case 0x15 -> disconnect("Client disconnected");
            default -> plugin.getDebugLogger().logUnknownPacket(address, packetId);
        }
    }

    private void handleConnectionRequest(ByteBuf payload) {
        if (payload.readableBytes() < 16) return;
        long clientGuid = payload.readLong();
        long timestamp = payload.readLong();

        byte[] response = buildConnectionRequestAccepted(timestamp);
        sendReliablePacket(response);

        plugin.getDebugLogger().logConnection(address, "Connection request accepted — guid=" + clientGuid);
    }

    private byte[] buildConnectionRequestAccepted(long clientTimestamp) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
        try {
            buf.writeByte(0x10);
            ByteBufUtils.writeAddress(buf, address);
            buf.writeShort(0); // system index

            // 20 internal addresses (RakNet spec placeholder)
            for (int i = 0; i < 20; i++) {
                buf.writeByte(4);
                buf.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
                buf.writeShort(0);
            }

            buf.writeLong(clientTimestamp);
            buf.writeLong(System.currentTimeMillis());

            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } finally {
            buf.release();
        }
    }

    private void handleConnectedPing(ByteBuf payload) {
        if (payload.readableBytes() < 8) return;
        long pingTime = payload.readLong();

        byte[] pong = new byte[17];
        java.nio.ByteBuffer wrapper = java.nio.ByteBuffer.wrap(pong);
        wrapper.put((byte) 0x03);
        wrapper.putLong(pingTime);
        wrapper.putLong(System.currentTimeMillis());

        sendReliablePacket(pong);
    }

    // ── Bedrock batch decompression ──────────────────────────────────

    private void decompressAndProcess(ByteBuf compressed) {
        if (compressed.readableBytes() < 1) return;

        if (!compressionEnabled) {
            processGamePackets(compressed);
            return;
        }

        int compressionType = compressed.readUnsignedByte();

        if (compressionType == 0xFF) {
            processGamePackets(compressed);
            return;
        }

        if (compressionType != 0x00) {
            plugin.getDebugLogger().logPacketDrop(address, "unsupported-compression:" + compressionType);
            return;
        }

        // Zlib inflate into pooled heap buffer
        byte[] input = new byte[compressed.readableBytes()];
        compressed.readBytes(input);

        inflater.reset();
        inflater.setInput(input);

        ByteBuf output = PooledByteBufAllocator.DEFAULT.heapBuffer(input.length * 4);
        try {
            byte[] temp = new byte[4096];
            while (!inflater.finished()) {
                int count = inflater.inflate(temp);
                if (count == 0 && inflater.needsInput()) break;
                output.writeBytes(temp, 0, count);
            }
            processGamePackets(output);
        } catch (DataFormatException e) {
            plugin.getDebugLogger().logException("Decompression[" + sessionId + "]", e);
        } finally {
            output.release();
        }
    }

    private void processGamePackets(ByteBuf buf) {
        while (buf.isReadable()) {
            int length;
            try {
                length = ByteBufUtils.readVarInt(buf);
            } catch (Exception e) {
                break;
            }

            if (length <= 0 || length > buf.readableBytes()) break;

            ByteBuf packetBuf = buf.readRetainedSlice(length);
            try {
                int header = ByteBufUtils.readVarInt(packetBuf);
                int packetId = header & 0x3FF;

                TranslatedPacket result = translator.translate(this, packetId, packetBuf);
                if (result != null && plugin.getPacketQueue() != null) {
                    if (!plugin.getPacketQueue().offer(result)) {
                        plugin.getDebugLogger().logPacketDrop(address, "mpsc-queue-full");
                    }
                }
            } catch (Exception e) {
                plugin.getDebugLogger().logException("PacketTranslate[" + sessionId + "]", e);
            } finally {
                ReferenceCountUtil.safeRelease(packetBuf);
            }
        }
    }

    // ── Outbound Bedrock game packets ────────────────────────────────

    public void sendBedrockPacket(int packetId, ByteBuf payload) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer();
        try {
            buf.writeByte(0xFE);
            if (compressionEnabled) {
                buf.writeByte(0xFF);
            }

            ByteBuf pkt = Unpooled.buffer();
            try {
                ByteBufUtils.writeVarInt(pkt, packetId & 0x3FF);
                pkt.writeBytes(payload);
                ByteBufUtils.writeVarInt(buf, pkt.readableBytes());
                buf.writeBytes(pkt);
            } finally {
                pkt.release();
            }

            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            sendReliablePacket(data);
        } catch (Exception e) {
            plugin.getDebugLogger().logException("sendBedrockPacket[" + sessionId + "]", e);
        } finally {
            buf.release();
            ReferenceCountUtil.safeRelease(payload);
        }
    }

    // ── Outbound reliability framing ─────────────────────────────────

    public void sendReliablePacket(byte[] data) {
        ByteBuf frame = networkCtx.alloc().buffer();
        try {
            frame.writeByte(0x84); // data frame flags
            frame.writeMediumLE(sendSequenceNumber++);

            // Encapsulated: reliability=3 (reliable ordered)
            frame.writeByte(3 << 5);
            frame.writeShort(data.length * 8); // length in bits
            frame.writeMediumLE(sendReliableIndex++);
            frame.writeMediumLE(sendOrderIndex++);
            frame.writeByte(0); // order channel

            frame.writeBytes(data);

            networkCtx.writeAndFlush(new DatagramPacket(frame, address));
            frame = null; // ownership transferred to Netty
        } finally {
            if (frame != null) frame.release();
        }
    }

    private void sendAck(int sequenceNumber) {
        ByteBuf ack = networkCtx.alloc().buffer(7);
        try {
            ack.writeByte(0xC0);
            ack.writeShortLE(1);           // 1 record (LE for Bedrock RakNet)
            ack.writeBoolean(true);        // single (not range)
            ack.writeMediumLE(sequenceNumber);

            networkCtx.writeAndFlush(new DatagramPacket(ack, address));
            ack = null;
        } finally {
            if (ack != null) ack.release();
        }
    }

    public void executeOnNetworkThread(Runnable task) {
        networkCtx.channel().eventLoop().execute(task);
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    public void disconnect(String reason) {
        if (!connected) return;
        connected = false;

        try {
            sendReliablePacket(new byte[]{0x15});
        } catch (Exception ignored) {}

        inflater.end();
        splitPackets.clear();
        splitCounts.clear();
        processedSequenceNumbers.clear();
        translator.getMovementTranslator().removeSession(sessionId);

        if (plugin.getAsyncChunkCache() != null) {
            plugin.getAsyncChunkCache().removeSession(sessionId);
        }
        if (plugin.getDynamicRenderController() != null) {
            plugin.getDynamicRenderController().removeProfile(sessionId);
        }
        if (plugin.getStateSynchronizer() != null) {
            plugin.getStateSynchronizer().endSync(sessionId);
        }
        if (plugin.getConcurrentLoginGuard() != null) {
            plugin.getConcurrentLoginGuard().release(sessionId);
        }

        plugin.getDebugLogger().logConnection(address, "Session disconnected: " + reason);

        if (plugin.getSessionManager() != null) {
            plugin.getSessionManager().removeSession(address);
        }
    }

    private void handleAck(ByteBuf buf) {
        // Alpha: ACK acknowledged but retransmission not yet implemented
    }

    private void handleNack(ByteBuf buf) {
        plugin.getDebugLogger().logPacketDrop(address, "NACK-received");
    }
}