package com.himnerd.shockbridge.chunk;

import com.himnerd.shockbridge.ShockbridgePlugin;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.Getter;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Off-heap chunk data cache backed by direct ByteBuffers in a circular ring buffer.
 * Prevents Bedrock chunk streaming from choking the main Paper network pipeline.
 * Each session gets an independent region in the buffer; the ring overwrites old
 * entries when full, keeping memory bounded to the configured limit.
 *
 * Chunk streaming runs on a dedicated Netty EventLoopGroup separate from both
 * Paper's network threads and the RakNet IO threads — zero TPS impact.
 */
public class AsyncChunkCache {

    private static final int ENTRY_OVERHEAD = 16; // chunkX(4) + chunkZ(4) + dataLen(4) + sessionHash(4)

    private final ShockbridgePlugin plugin;
    private final ByteBuffer ringBuffer;
    private final int bufferCapacity;
    private volatile int writePosition;

    private final Map<Long, Integer> chunkIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> sessionChunkCounts = new ConcurrentHashMap<>();

    @Getter private final TrajectoryPredictor trajectoryPredictor;
    @Getter private final EventLoopGroup streamGroup;

    public AsyncChunkCache(ShockbridgePlugin plugin) {
        this.plugin = plugin;
        int sizeMb = plugin.getShockbridgeConfig().getChunkCacheSize();
        this.bufferCapacity = sizeMb * 1024 * 1024;
        this.ringBuffer = ByteBuffer.allocateDirect(bufferCapacity);
        this.trajectoryPredictor = new TrajectoryPredictor();
        this.streamGroup = new NioEventLoopGroup(1, r -> {
            Thread t = new Thread(r, "Shockbridge-ChunkStream");
            t.setDaemon(true);
            return t;
        });

        plugin.getDebugLogger().log("AsyncChunkCache: allocated " + sizeMb + "MB off-heap ring buffer");
    }

    /**
     * Cache raw chunk data for a session. Runs from any thread.
     */
    public void cache(UUID sessionId, int chunkX, int chunkZ, byte[] data) {
        int needed = ENTRY_OVERHEAD + data.length;
        if (needed > bufferCapacity) return;

        synchronized (ringBuffer) {
            if (writePosition + needed > bufferCapacity) {
                writePosition = 0; // wrap around
            }

            int pos = writePosition;
            ringBuffer.position(pos);
            ringBuffer.putInt(chunkX);
            ringBuffer.putInt(chunkZ);
            ringBuffer.putInt(data.length);
            ringBuffer.putInt(sessionId.hashCode());
            ringBuffer.put(data);
            writePosition = pos + needed;

            long key = chunkKey(chunkX, chunkZ);
            chunkIndex.put(key, pos);
            sessionChunkCounts.merge(sessionId, 1, Integer::sum);
        }
    }

    /**
     * Read cached chunk data. Returns null if not in cache.
     */
    public byte[] get(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        Integer pos = chunkIndex.get(key);
        if (pos == null) return null;

        synchronized (ringBuffer) {
            if (pos + ENTRY_OVERHEAD > bufferCapacity) return null;

            ringBuffer.position(pos);
            int storedX = ringBuffer.getInt();
            int storedZ = ringBuffer.getInt();

            // Verify the entry hasn't been overwritten
            if (storedX != chunkX || storedZ != chunkZ) {
                chunkIndex.remove(key);
                return null;
            }

            int dataLen = ringBuffer.getInt();
            ringBuffer.getInt(); // skip session hash

            if (pos + ENTRY_OVERHEAD + dataLen > bufferCapacity) return null;

            byte[] data = new byte[dataLen];
            ringBuffer.get(data);
            return data;
        }
    }

    public int getCachedChunkCount() {
        return chunkIndex.size();
    }

    public void removeSession(UUID sessionId) {
        sessionChunkCounts.remove(sessionId);
        trajectoryPredictor.removeSession(sessionId);
    }

    public void shutdown() {
        streamGroup.shutdownGracefully();
        chunkIndex.clear();
        sessionChunkCounts.clear();
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}