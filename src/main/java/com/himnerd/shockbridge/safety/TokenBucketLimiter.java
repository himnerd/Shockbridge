package com.himnerd.shockbridge.safety;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free token-bucket rate limiter using a single CAS-updated packed long.
 * Upper 32 bits = available tokens, lower 32 bits = last refill time in centiseconds.
 */
public class TokenBucketLimiter {

    private final int maxTokens;
    private final int refillPerSecond;
    private final AtomicLong state;

    public TokenBucketLimiter(int maxTokens, int refillPerSecond) {
        this.maxTokens = maxTokens;
        this.refillPerSecond = refillPerSecond;
        int now = (int) ((System.currentTimeMillis() / 10) & 0xFFFFFFFFL);
        this.state = new AtomicLong(pack(maxTokens, now));
    }

    public boolean tryConsume() {
        int now = (int) ((System.currentTimeMillis() / 10) & 0xFFFFFFFFL);
        while (true) {
            long current = state.get();
            int tokens = unpackTokens(current);
            int lastTime = unpackTime(current);

            int elapsed = now - lastTime;
            if (elapsed > 0) {
                tokens = (int) Math.min(maxTokens, tokens + ((long) elapsed * refillPerSecond / 100));
                lastTime = now;
            }

            if (tokens <= 0) return false;

            long next = pack(tokens - 1, lastTime);
            if (state.compareAndSet(current, next)) return true;
        }
    }

    private static long pack(int tokens, int time) {
        return ((long) tokens << 32) | (time & 0xFFFFFFFFL);
    }

    private static int unpackTokens(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackTime(long packed) {
        return (int) (packed & 0xFFFFFFFFL);
    }
}