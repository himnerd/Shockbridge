package com.himnerd.shockbridge.injection;

import com.himnerd.shockbridge.ShockbridgePlugin;
import com.himnerd.shockbridge.network.VirtualSession;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Injects Bedrock sessions into Paper's network pipeline by connecting as a
 * real Java client over localhost TCP. Paper, ViaVersion, and all anticheats
 * see a normal Java player. Requires either {@code online-mode=false} or
 * BungeeCord IP-forwarding enabled in spigot.yml.
 */
public class JavaSessionInjector {

    private final ShockbridgePlugin plugin;
    private final EventLoopGroup workerGroup;
    private final Map<UUID, InjectedSession> injectedSessions = new ConcurrentHashMap<>();
    private final Map<String, VirtualSession> pendingByName = new ConcurrentHashMap<>();

    public JavaSessionInjector(ShockbridgePlugin plugin) {
        this.plugin = plugin;
        this.workerGroup = new NioEventLoopGroup(1, r -> {
            Thread t = new Thread(r, "Shockbridge-Inject");
            t.setDaemon(true);
            return t;
        });
    }

    public void inject(VirtualSession bedrockSession, String playerName, UUID playerUuid) {
        int serverPort = plugin.getServer().getPort();
        int javaProtocol = plugin.getViaBridge() != null
                ? plugin.getViaBridge().mapToJavaProtocol(bedrockSession.getBedrockProtocol())
                : 769;

        pendingByName.put(playerName, bedrockSession);
        String hostname = buildHandshakeHostname(bedrockSession, playerUuid);

        Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("frame-decoder", new McFrameDecoder());
                        ch.pipeline().addLast("frame-encoder", new McFrameEncoder());
                        ch.pipeline().addLast("handler", new LoginHandler(
                                bedrockSession, playerName, playerUuid,
                                javaProtocol, hostname, serverPort));
                    }
                });

        bootstrap.connect("127.0.0.1", serverPort).addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                plugin.getLogger().warning("[Injection] Failed to connect to localhost:" + serverPort +
                        " — " + (f.cause() != null ? f.cause().getMessage() : "unknown"));
                pendingByName.remove(playerName);
            }
        });
    }

    private String buildHandshakeHostname(VirtualSession session, UUID playerUuid) {
        if (plugin.getShockbridgeConfig().isUseBungeeForwarding()) {
            String ip = session.getAddress().getAddress().getHostAddress();
            String uuid = playerUuid.toString().replace("-", "");
            return "127.0.0.1\0" + ip + "\0" + uuid + "\0[]";
        }
        return "127.0.0.1";
    }

    public InjectedSession getInjectedSession(UUID sessionId) {
        return injectedSessions.get(sessionId);
    }

    public int getInjectedCount() {
        return injectedSessions.size();
    }

    public void removeSession(UUID sessionId) {
        InjectedSession removed = injectedSessions.remove(sessionId);
        if (removed != null && removed.javaChannel != null) removed.javaChannel.close();
    }

    public void shutdown() {
        for (InjectedSession s : injectedSessions.values()) {
            if (s.javaChannel != null) s.javaChannel.close();
        }
        injectedSessions.clear();
        pendingByName.clear();
        workerGroup.shutdownGracefully();
    }

    // ── Inner data ─────────────────────────────────────────

    @Getter
    public static class InjectedSession {
        private final VirtualSession bedrockSession;
        private final Channel javaChannel;
        private volatile boolean inPlayState;

        InjectedSession(VirtualSession bedrockSession, Channel javaChannel) {
            this.bedrockSession = bedrockSession;
            this.javaChannel = javaChannel;
        }
    }

    // ── Minecraft frame codec ──────────────────────────────

    static class McFrameDecoder extends ByteToMessageDecoder {
        private int compressionThreshold = -1;
        private final Inflater inflater = new Inflater();

        void enableCompression(int threshold) {
            this.compressionThreshold = threshold;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            in.markReaderIndex();
            int length;
            try {
                length = McCodec.readVarInt(in);
            } catch (Exception e) {
                in.resetReaderIndex();
                return;
            }
            if (length < 0 || in.readableBytes() < length) {
                in.resetReaderIndex();
                return;
            }

            ByteBuf payload = in.readRetainedSlice(length);
            if (compressionThreshold >= 0) {
                try {
                    int dataLength = McCodec.readVarInt(payload);
                    if (dataLength > 0) {
                        byte[] compressed = new byte[payload.readableBytes()];
                        payload.readBytes(compressed);
                        payload.release();
                        inflater.reset();
                        inflater.setInput(compressed);
                        byte[] decompressed = new byte[dataLength];
                        inflater.inflate(decompressed);
                        payload = Unpooled.wrappedBuffer(decompressed);
                    }
                } catch (DataFormatException e) {
                    payload.release();
                    return;
                }
            }
            out.add(payload);
        }
    }

    static class McFrameEncoder extends MessageToByteEncoder<ByteBuf> {
        private int compressionThreshold = -1;
        private final Deflater deflater = new Deflater();

        void enableCompression(int threshold) {
            this.compressionThreshold = threshold;
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
            if (compressionThreshold >= 0) {
                int size = msg.readableBytes();
                if (size >= compressionThreshold) {
                    byte[] data = new byte[size];
                    msg.readBytes(data);
                    deflater.reset();
                    deflater.setInput(data);
                    deflater.finish();
                    byte[] compressed = new byte[data.length + 64];
                    int compSize = deflater.deflate(compressed);

                    ByteBuf temp = Unpooled.buffer();
                    McCodec.writeVarInt(temp, size);
                    temp.writeBytes(compressed, 0, compSize);
                    McCodec.writeVarInt(out, temp.readableBytes());
                    out.writeBytes(temp);
                    temp.release();
                } else {
                    ByteBuf temp = Unpooled.buffer();
                    McCodec.writeVarInt(temp, 0);
                    temp.writeBytes(msg);
                    McCodec.writeVarInt(out, temp.readableBytes());
                    out.writeBytes(temp);
                    temp.release();
                }
            } else {
                McCodec.writeVarInt(out, msg.readableBytes());
                out.writeBytes(msg);
            }
        }
    }

    static final class McCodec {
        static int readVarInt(ByteBuf buf) {
            int value = 0, shift = 0;
            byte b;
            do {
                if (!buf.isReadable()) throw new IndexOutOfBoundsException();
                b = buf.readByte();
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) throw new ArithmeticException();
            } while ((b & 0x80) != 0);
            return value;
        }

        static void writeVarInt(ByteBuf buf, int value) {
            while ((value & ~0x7F) != 0) {
                buf.writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            buf.writeByte(value & 0x7F);
        }

        static void writeString(ByteBuf buf, String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeVarInt(buf, bytes.length);
            buf.writeBytes(bytes);
        }

        static String readString(ByteBuf buf) {
            int len = readVarInt(buf);
            if (len < 0 || len > buf.readableBytes()) return "";
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    // ── Login / Configuration / Play handler ───────────────

    private class LoginHandler extends ChannelInboundHandlerAdapter {

        private final VirtualSession bedrockSession;
        private final String playerName;
        private final UUID playerUuid;
        private final int protocolVersion;
        private final String hostname;
        private final int serverPort;

        private enum Phase { LOGIN, CONFIGURATION, PLAY }
        private Phase phase = Phase.LOGIN;

        LoginHandler(VirtualSession bedrockSession, String playerName, UUID playerUuid,
                     int protocolVersion, String hostname, int serverPort) {
            this.bedrockSession = bedrockSession;
            this.playerName = playerName;
            this.playerUuid = playerUuid;
            this.protocolVersion = protocolVersion;
            this.hostname = hostname;
            this.serverPort = serverPort;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            // Handshake (state 2 = login)
            ByteBuf handshake = Unpooled.buffer();
            McCodec.writeVarInt(handshake, 0x00);
            McCodec.writeVarInt(handshake, protocolVersion);
            McCodec.writeString(handshake, hostname);
            handshake.writeShort(serverPort);
            McCodec.writeVarInt(handshake, 2);
            ctx.writeAndFlush(handshake);

            // Login Start
            ByteBuf loginStart = Unpooled.buffer();
            McCodec.writeVarInt(loginStart, 0x00);
            McCodec.writeString(loginStart, playerName);
            loginStart.writeLong(playerUuid.getMostSignificantBits());
            loginStart.writeLong(playerUuid.getLeastSignificantBits());
            ctx.writeAndFlush(loginStart);

            plugin.getDebugLogger().logConnection(bedrockSession.getAddress(),
                    "Injection: handshake+login sent for " + playerName + " (proto " + protocolVersion + ")");
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                int packetId = McCodec.readVarInt(buf);
                switch (phase) {
                    case LOGIN -> handleLoginPacket(ctx, packetId, buf);
                    case CONFIGURATION -> handleConfigPacket(ctx, packetId, buf);
                    case PLAY -> handlePlayPacket(ctx, packetId, buf);
                }
            } catch (Exception e) {
                plugin.getDebugLogger().logException("Injection[" + playerName + "]", e);
            } finally {
                if (buf.refCnt() > 0) buf.release();
            }
        }

        private void handleLoginPacket(ChannelHandlerContext ctx, int packetId, ByteBuf buf) {
            switch (packetId) {
                case 0x00 -> { // Disconnect
                    String reason = McCodec.readString(buf);
                    plugin.getLogger().warning("[Injection] Login rejected: " + reason);
                    pendingByName.remove(playerName);
                    ctx.close();
                }
                case 0x01 -> { // Encryption Request
                    plugin.getLogger().warning("[Injection] Server requires encryption for " + playerName +
                            ". Enable bungeecord forwarding or set online-mode=false.");
                    pendingByName.remove(playerName);
                    ctx.close();
                }
                case 0x02 -> { // Login Success
                    ByteBuf ack = Unpooled.buffer();
                    McCodec.writeVarInt(ack, 0x03);
                    ctx.writeAndFlush(ack);
                    phase = Phase.CONFIGURATION;

                    // Send Client Information
                    ByteBuf clientInfo = Unpooled.buffer();
                    McCodec.writeVarInt(clientInfo, 0x00);
                    McCodec.writeString(clientInfo, "en_us");
                    clientInfo.writeByte(12); // view distance
                    McCodec.writeVarInt(clientInfo, 0); // chat enabled
                    clientInfo.writeBoolean(true); // chat colors
                    clientInfo.writeByte(0x7F); // skin parts
                    McCodec.writeVarInt(clientInfo, 1); // main hand right
                    clientInfo.writeBoolean(false); // text filtering
                    clientInfo.writeBoolean(true); // server listing
                    McCodec.writeVarInt(clientInfo, 0); // particle status
                    ctx.writeAndFlush(clientInfo);

                    plugin.getDebugLogger().logConnection(bedrockSession.getAddress(),
                            "Injection: login success → configuration for " + playerName);
                }
                case 0x03 -> { // Set Compression
                    int threshold = McCodec.readVarInt(buf);
                    McFrameDecoder decoder = ctx.pipeline().get(McFrameDecoder.class);
                    McFrameEncoder encoder = ctx.pipeline().get(McFrameEncoder.class);
                    if (decoder != null) decoder.enableCompression(threshold);
                    if (encoder != null) encoder.enableCompression(threshold);
                }
                case 0x04 -> { // Login Plugin Request
                    int messageId = McCodec.readVarInt(buf);
                    ByteBuf response = Unpooled.buffer();
                    McCodec.writeVarInt(response, 0x02);
                    McCodec.writeVarInt(response, messageId);
                    response.writeBoolean(false);
                    ctx.writeAndFlush(response);
                }
            }
        }

        private void handleConfigPacket(ChannelHandlerContext ctx, int packetId, ByteBuf buf) {
            switch (packetId) {
                case 0x02 -> { // Disconnect
                    pendingByName.remove(playerName);
                    ctx.close();
                }
                case 0x03 -> { // Finish Configuration
                    ByteBuf finish = Unpooled.buffer();
                    McCodec.writeVarInt(finish, 0x03);
                    ctx.writeAndFlush(finish);

                    phase = Phase.PLAY;

                    InjectedSession injected = new InjectedSession(bedrockSession, ctx.channel());
                    injected.inPlayState = true;
                    injectedSessions.put(bedrockSession.getSessionId(), injected);
                    bedrockSession.setInjectionChannel(ctx.channel());
                    pendingByName.remove(playerName);

                    // Replace this handler with the play-state bridge
                    ctx.pipeline().replace("handler", "play-bridge",
                            new OutboundPacketInterceptor(plugin, bedrockSession));

                    plugin.getLogger().info("[Injection] " + playerName +
                            " fully injected — Paper sees a Java player");
                }
                case 0x04 -> { // Keep Alive
                    long id = buf.readLong();
                    ByteBuf response = Unpooled.buffer();
                    McCodec.writeVarInt(response, 0x04);
                    response.writeLong(id);
                    ctx.writeAndFlush(response);
                }
                case 0x0E -> { // Known Packs
                    ByteBuf response = Unpooled.buffer();
                    McCodec.writeVarInt(response, 0x07);
                    McCodec.writeVarInt(response, 0);
                    ctx.writeAndFlush(response);
                }
                default -> {
                    // Registry data, tags, feature flags etc. — acknowledged implicitly
                }
            }
        }

        private void handlePlayPacket(ChannelHandlerContext ctx, int packetId, ByteBuf buf) {
            // Shouldn't reach here after handler replacement, but safety fallback
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            plugin.getDebugLogger().logException("Injection[" + playerName + "]", cause);
            pendingByName.remove(playerName);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            pendingByName.remove(playerName);
            injectedSessions.remove(bedrockSession.getSessionId());
            bedrockSession.setInjectionChannel(null);
            plugin.getDebugLogger().logConnection(bedrockSession.getAddress(),
                    "Injection channel closed for " + playerName);
        }
    }
}