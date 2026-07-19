package com.himnerd.shockbridge.chunk;

import com.himnerd.shockbridge.pool.PooledVector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calculates player trajectory vectors from recent position history to determine
 * which chunks to pre-stream. Uses a rolling window of the last 8 chunk loads
 * per session to compute a velocity vector, then projects forward to find chunks
 * the player is likely to enter within the next 2-4 seconds.
 */
public class TrajectoryPredictor {

    private static final int HISTORY_SIZE = 8;

    private final Map<UUID, int[][]> history = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> historyIndex = new ConcurrentHashMap<>();

    public void recordChunkLoad(UUID sessionId, int chunkX, int chunkZ) {
        int[][] h = history.computeIfAbsent(sessionId, k -> new int[HISTORY_SIZE][2]);
        int[] idx = historyIndex.computeIfAbsent(sessionId, k -> new int[]{0});

        h[idx[0]][0] = chunkX;
        h[idx[0]][1] = chunkZ;
        idx[0] = (idx[0] + 1) % HISTORY_SIZE;
    }

    /**
     * Returns predicted chunk coordinates the player is moving toward.
     * Result array: [chunkX0, chunkZ0, chunkX1, chunkZ1, ...]
     * Returns null if insufficient history.
     */
    public int[] predictNextChunks(UUID sessionId, int count) {
        int[][] h = history.get(sessionId);
        int[] idx = historyIndex.get(sessionId);
        if (h == null || idx == null) return null;

        // Calculate velocity vector from oldest to newest in ring buffer
        int newest = (idx[0] - 1 + HISTORY_SIZE) % HISTORY_SIZE;
        int oldest = idx[0]; // next write position = oldest entry

        // Need at least 3 entries for a meaningful trajectory
        int filled = 0;
        for (int[] entry : h) {
            if (entry[0] != 0 || entry[1] != 0) filled++;
        }
        if (filled < 3) return null;

        PooledVector3f velocity = PooledVector3f.borrow();
        try {
            // Average velocity from consecutive pairs
            int pairs = 0;
            for (int i = 0; i < HISTORY_SIZE - 1; i++) {
                int curr = (oldest + i) % HISTORY_SIZE;
                int next = (oldest + i + 1) % HISTORY_SIZE;
                if (h[curr][0] == 0 && h[curr][1] == 0) continue;
                if (h[next][0] == 0 && h[next][1] == 0) continue;

                velocity.x += h[next][0] - h[curr][0];
                velocity.z += h[next][1] - h[curr][1];
                pairs++;
            }

            if (pairs == 0) return null;

            velocity.x /= pairs;
            velocity.z /= pairs;

            // Project forward from current position
            int[] result = new int[count * 2];
            float curX = h[newest][0];
            float curZ = h[newest][1];

            for (int i = 0; i < count; i++) {
                curX += velocity.x;
                curZ += velocity.z;
                result[i * 2] = Math.round(curX);
                result[i * 2 + 1] = Math.round(curZ);
            }

            return result;
        } finally {
            PooledVector3f.release(velocity);
        }
    }

    public void removeSession(UUID sessionId) {
        history.remove(sessionId);
        historyIndex.remove(sessionId);
    }
}