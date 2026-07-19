package com.himnerd.shockbridge.network;

import com.himnerd.shockbridge.ShockbridgePlugin;
import com.himnerd.shockbridge.network.VirtualSession;
import com.himnerd.shockbridge.util.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class SessionManager {

    private final ShockbridgePlugin plugin;
    @Getter private final long serverId;
    private final Map<InetSocketAddress, VirtualSession> sessionsByAddress = new ConcurrentHashMap<>();
    private final Map<UUID, VirtualSession> sessionsById = new ConcurrentHashMap<>();

    public SessionManager(ShockbridgePlugin plugin) {
        this.plugin = plugin;
        this.serverId = ThreadLocalRandom.current().nextLong();
    }

    public VirtualSession getSession(InetSocketAddress address) {
        return sessionsByAddress.get(address);
    }

    public VirtualSession getSessionById(UUID sessionId) {
        return sessionsById.get(sessionId);
    }

    public VirtualSession createSession(InetSocketAddress address, int mtuSize, ChannelHandlerContext ctx) {
        UUID sessionId = UUID.randomUUID();
        VirtualSession session = new VirtualSession(plugin, sessionId, address, mtuSize, ctx);
        sessionsByAddress.put(address, session);
        sessionsById.put(sessionId, session);
        return session;
    }

    public void removeSession(InetSocketAddress address) {
        VirtualSession session = sessionsByAddress.remove(address);
        if (session != null) {
            sessionsById.remove(session.getSessionId());
        }
    }

    public int getSessionCount() {
        return sessionsByAddress.size();
    }

    public int getSessionCountForAddress(InetAddress addr) {
        int count = 0;
        for (InetSocketAddress key : sessionsByAddress.keySet()) {
            if (key.getAddress().equals(addr)) count++;
        }
        return count;
    }

    public Map<UUID, VirtualSession> getAllSessions() {
        return java.util.Collections.unmodifiableMap(sessionsById);
    }

    public void disconnectAll(String reason) {
        for (VirtualSession session : sessionsByAddress.values()) {
            try {
                session.disconnect(reason);
            } catch (Exception e) {
                plugin.getLogger().warning("[SessionManager] Error disconnecting " + session.getSessionId() + ": " + e.getMessage());
            }
        }
        sessionsByAddress.clear();
        sessionsById.clear();
    }

    /**
     * Called on the main thread from PacketQueue drain.
     * In production, injects translated Java packets into Paper's NetworkManager
     * via the virtual Netty channel bound to this session.
     */
    public void processTranslatedPacket(TranslatedPacket packet) {
        VirtualSession session = sessionsById.get(packet.sessionId());
        if (session == null || !session.isConnected()) return;
        if (packet.javaPacketId() < 0) return;

        Channel injectionChannel = session.getInjectionChannel();
        if (injectionChannel != null && injectionChannel.isActive()) {
            ByteBuf buf = Unpooled.buffer();
            ByteBufUtils.writeVarInt(buf, packet.javaPacketId());
            buf.writeBytes(packet.payload());
            injectionChannel.writeAndFlush(buf);
            return;
        }

        plugin.getDebugLogger().logTranslation(packet.sessionId(), packet.javaPacketId(), packet.translationNanos());
    }
}