package com.himnerd.shockbridge.network;

import com.himnerd.shockbridge.ShockbridgePlugin;
import com.himnerd.shockbridge.safety.CrashIsolator;
import com.himnerd.shockbridge.safety.TokenBucketLimiter;
import com.himnerd.shockbridge.util.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Front-of-pipeline Netty handler for all inbound UDP datagrams.
 * Handles RakNet handshake (unconnected ping/pong, open connection),
 * applies per-address token-bucket rate limiting, and routes connected
 * traffic to the appropriate VirtualSession inside a crash-isolated boundary.
 */
public class RakNetHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private static final byte ID_UNCONNECTED_PING = 0x01;
    private static final byte ID_UNCONNECTED_PONG = 0x1C;
    private static final byte ID_OPEN_CONNECTION_REQUEST_1 = 0x05;
    private static final byte ID_OPEN_CONNECTION_REPLY_1 = 0x06;
    private static final byte ID_OPEN_CONNECTION_REQUEST_2 = 0x07;
    private static final byte ID_OPEN_CONNECTION_REPLY_2 = 0x08;

    private static final byte[] RAKNET_MAGIC = {
            0x00, (byte) 0xFF, (byte) 0xFF, 0x00,
            (byte) 0xFE, (byte) 0xFE, (byte) 0xFE, (byte) 0xFE,
            (byte) 0xFD, (byte) 0xFD, (byte) 0xFD, (byte) 0xFD,
            0x12, 0x34, 0x56, 0x78
    };

    private final ShockbridgePlugin plugin;
    private final Map<InetSocketAddress, TokenBucketLimiter> rateLimiters = new ConcurrentHashMap<>();

    public RakNetHandler(ShockbridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        InetSocketAddress sender = packet.sender();
        ByteBuf buf = packet.content();

        if (buf.readableBytes() < 1) return;

        TokenBucketLimiter limiter = rateLimiters.computeIfAbsent(sender,
                addr -> new TokenBucketLimiter(
                        plugin.getShockbridgeConfig().getRateLimitTokens(),
                        plugin.getShockbridgeConfig().getRateLimitRefillPerSecond()));

        if (!limiter.tryConsume()) {
            plugin.getDebugLogger().logPacketDrop(sender, "rate-limited");
            return;
        }

        CrashIsolator.execute(plugin, sender, () -> {
            buf.markReaderIndex();
            byte messageId = buf.readByte();

            switch (messageId) {
                case ID_UNCONNECTED_PING -> handleUnconnectedPing(ctx, sender, buf);
                case ID_OPEN_CONNECTION_REQUEST_1 -> handleOpenConnectionRequest1(ctx, sender, buf);
                case ID_OPEN_CONNECTION_REQUEST_2 -> handleOpenConnectionRequest2(ctx, sender, buf);
                default -> {
                    if ((messageId & 0x80) != 0) {
                        buf.resetReaderIndex();
                        routeToSession(sender, buf);
                    } else {
                        plugin.getDebugLogger().logUnknownPacket(sender, messageId);
                    }
                }
            }
        });
    }

    private void handleUnconnectedPing(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf buf) {
        if (buf.readableBytes() < 24) return;
        long pingTime = buf.readLong();
        buf.skipBytes(16); // MAGIC

        ByteBuf pong = ctx.alloc().buffer();
        try {
            pong.writeByte(ID_UNCONNECTED_PONG);
            pong.writeLong(pingTime);
            pong.writeLong(plugin.getSessionManager().getServerId());
            pong.writeBytes(RAKNET_MAGIC);

            String motd = buildMotdString();
            byte[] motdBytes = motd.getBytes(StandardCharsets.UTF_8);
            pong.writeShort(motdBytes.length);
            pong.writeBytes(motdBytes);

            ctx.writeAndFlush(new DatagramPacket(pong, sender));
            pong = null;
        } finally {
            if (pong != null) pong.release();
        }
    }

    private void handleOpenConnectionRequest1(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf buf) {
        if (buf.readableBytes() < 17) return;
        buf.skipBytes(16); // MAGIC
        byte protocolVersion = buf.readByte();
        int mtuSize = Math.min(1400, 18 + buf.readableBytes());

        plugin.getDebugLogger().logConnection(sender,
                "OpenConnectionRequest1 proto=" + protocolVersion + " mtu=" + mtuSize);

        ByteBuf reply = ctx.alloc().buffer();
        try {
            reply.writeByte(ID_OPEN_CONNECTION_REPLY_1);
            reply.writeBytes(RAKNET_MAGIC);
            reply.writeLong(plugin.getSessionManager().getServerId());
            reply.writeBoolean(false); // security disabled
            reply.writeShort(mtuSize);

            ctx.writeAndFlush(new DatagramPacket(reply, sender));
            reply = null;
        } finally {
            if (reply != null) reply.release();
        }
    }

    private void handleOpenConnectionRequest2(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf buf) {
        if (buf.readableBytes() < 16) return;
        buf.skipBytes(16); // MAGIC

        InetSocketAddress serverAddress = ByteBufUtils.readAddress(buf);
        if (serverAddress == null || buf.readableBytes() < 10) return;

        int mtuSize = buf.readUnsignedShort();
        long clientGuid = buf.readLong();

        plugin.getDebugLogger().logConnection(sender,
                "OpenConnectionRequest2 mtu=" + mtuSize + " guid=" + clientGuid);

        SessionManager sm = plugin.getSessionManager();
        if (sm.getSessionCountForAddress(sender.getAddress()) >= plugin.getShockbridgeConfig().getMaxSessionsPerIp()) {
            plugin.getDebugLogger().logPacketDrop(sender, "max-sessions-per-ip");
            return;
        }

        VirtualSession session = sm.createSession(sender, mtuSize, ctx);

        ByteBuf reply = ctx.alloc().buffer();
        try {
            reply.writeByte(ID_OPEN_CONNECTION_REPLY_2);
            reply.writeBytes(RAKNET_MAGIC);
            reply.writeLong(sm.getServerId());
            ByteBufUtils.writeAddress(reply, sender);
            reply.writeShort(mtuSize);
            reply.writeBoolean(false); // encryption disabled

            ctx.writeAndFlush(new DatagramPacket(reply, sender));
            reply = null;
        } finally {
            if (reply != null) reply.release();
        }

        plugin.getDebugLogger().logConnection(sender, "Session " + session.getSessionId() + " created");
    }

    private void routeToSession(InetSocketAddress sender, ByteBuf buf) {
        VirtualSession session = plugin.getSessionManager().getSession(sender);
        if (session == null) {
            plugin.getDebugLogger().logPacketDrop(sender, "no-session");
            return;
        }
        // retainedSlice transfers lifecycle ownership to VirtualSession.handleDatagram
        session.handleDatagram(buf.retainedSlice());
    }

    private String buildMotdString() {
        int port = plugin.getShockbridgeConfig().getBindPort();
        return "MCPE;Shockbridge Server;685;1.21.50;" +
                plugin.getServer().getOnlinePlayers().size() + ";" +
                plugin.getServer().getMaxPlayers() + ";" +
                plugin.getSessionManager().getServerId() + ";" +
                "Shockbridge;Survival;0;" + port + ";" + port + ";";
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        plugin.getLogger().warning("[RakNet] Pipeline exception: " + cause.getMessage());
        plugin.getDebugLogger().logException("RakNetHandler", cause);
    }

    public void cleanupRateLimiter(InetSocketAddress address) {
        rateLimiters.remove(address);
    }
}