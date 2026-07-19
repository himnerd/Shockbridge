package com.himnerd.shockbridge.translation;

import com.himnerd.shockbridge.debug.AlphaDebugLogger;

/**
 * Flat primitive-array palette for Bedrock↔Java block state translation.
 * Guarantees O(1) lookup with L1/L2 cache-friendly sequential access patterns.
 * In production, populated asynchronously from Paper's BuiltInRegistries.BLOCK.
 */
public class BlockPalette {

    private final AlphaDebugLogger debugLogger;
    private int[] bedrockToJava;
    private int[] javaToBedrock;
    private int bedrockCapacity;
    private int javaCapacity;

    public BlockPalette(AlphaDebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    /**
     * Reads Paper's internal block registry and Bedrock's runtime palette data
     * to build bidirectional mapping tables. For alpha: identity mapping.
     */
    public void compile() {
        this.bedrockCapacity = 16384;
        this.javaCapacity = 16384;
        this.bedrockToJava = new int[bedrockCapacity];
        this.javaToBedrock = new int[javaCapacity];

        for (int i = 0; i < Math.min(bedrockCapacity, javaCapacity); i++) {
            bedrockToJava[i] = i;
            javaToBedrock[i] = i;
        }

        debugLogger.log("BlockPalette compiled: " + bedrockCapacity + " bedrock slots, " + javaCapacity + " java slots");
    }

    public int toJava(int bedrockRuntimeId) {
        if (bedrockRuntimeId < 0 || bedrockRuntimeId >= bedrockCapacity) return 0;
        return bedrockToJava[bedrockRuntimeId];
    }

    public int toBedrock(int javaStateId) {
        if (javaStateId < 0 || javaStateId >= javaCapacity) return 0;
        return javaToBedrock[javaStateId];
    }

    public int getCapacity() {
        return bedrockCapacity;
    }
}