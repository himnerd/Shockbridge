package com.himnerd.shockbridge.network;

import com.himnerd.shockbridge.ShockbridgePlugin;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * Bootstraps the Netty NioDatagramChannel that binds the RakNet UDP listener.
 * Uses a dedicated 2-thread IO group with named threads for profiling.
 */
public class RakNetListener {

    private final ShockbridgePlugin plugin;
    private EventLoopGroup group;
    private Channel channel;

    public RakNetListener(ShockbridgePlugin plugin) {
        this.plugin = plugin;
    }

    public void bind(String address, int port) {
        group = new NioEventLoopGroup(2, new DefaultThreadFactory("Shockbridge-IO"));

        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_RCVBUF, 1024 * 1024)
                .option(ChannelOption.SO_SNDBUF, 1024 * 1024)
                .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(4096))
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) {
                        ch.pipeline().addLast("raknet-handler", new RakNetHandler(plugin));
                    }
                });

        try {
            channel = bootstrap.bind(address, port).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("RakNet bind interrupted", e);
        }
    }

    public void shutdown() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
        plugin.getLogger().info("[RakNet] UDP listener closed.");
    }
}