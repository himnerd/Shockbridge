package com.himnerd.shockbridge.safety;

import com.himnerd.shockbridge.debug.AlphaDebugLogger;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live off-heap ByteBuf references. On session teardown or plugin disable,
 * detects and releases leaked buffers, preventing classloader memory leaks.
 */
public class MemoryTracker {

    private final AlphaDebugLogger debugLogger;
    private final Set<ByteBuf> trackedBuffers = ConcurrentHashMap.newKeySet();

    public MemoryTracker(AlphaDebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    public ByteBuf track(ByteBuf buf) {
        if (buf != null) {
            trackedBuffers.add(buf);
        }
        return buf;
    }

    public void release(ByteBuf buf) {
        if (buf != null) {
            trackedBuffers.remove(buf);
            if (buf.refCnt() > 0) {
                ReferenceCountUtil.safeRelease(buf);
            }
        }
    }

    public void releaseAll() {
        int leaked = 0;
        for (ByteBuf buf : trackedBuffers) {
            if (buf.refCnt() > 0) {
                leaked++;
                ReferenceCountUtil.safeRelease(buf);
            }
        }
        if (leaked > 0) {
            debugLogger.logBufferMetrics(leaked, trackedBuffers.size(), 0);
        }
        trackedBuffers.clear();
    }

    public int getTrackedCount() {
        return trackedBuffers.size();
    }
}