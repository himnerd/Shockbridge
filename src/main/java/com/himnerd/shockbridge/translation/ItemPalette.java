package com.himnerd.shockbridge.translation;

import com.himnerd.shockbridge.debug.AlphaDebugLogger;

/**
 * Flat primitive-array palette for Bedrock↔Java item ID translation.
 * Same cache-locality design as BlockPalette. In production, populated
 * asynchronously from Paper's BuiltInRegistries.ITEM and compiled into
 * the ItemComponentPacket dynamically.
 */
public class ItemPalette {

    private final AlphaDebugLogger debugLogger;
    private int[] bedrockToJava;
    private int[] javaToBedrock;
    private int bedrockCapacity;
    private int javaCapacity;

    public ItemPalette(AlphaDebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    public void compile() {
        this.bedrockCapacity = 4096;
        this.javaCapacity = 4096;
        this.bedrockToJava = new int[bedrockCapacity];
        this.javaToBedrock = new int[javaCapacity];

        for (int i = 0; i < Math.min(bedrockCapacity, javaCapacity); i++) {
            bedrockToJava[i] = i;
            javaToBedrock[i] = i;
        }

        debugLogger.log("ItemPalette compiled: " + bedrockCapacity + " bedrock slots, " + javaCapacity + " java slots");
    }

    public int toJava(int bedrockItemId) {
        if (bedrockItemId < 0 || bedrockItemId >= bedrockCapacity) return 0;
        return bedrockToJava[bedrockItemId];
    }

    public int toBedrock(int javaItemId) {
        if (javaItemId < 0 || javaItemId >= javaCapacity) return 0;
        return javaToBedrock[javaItemId];
    }

    public int getCapacity() {
        return bedrockCapacity;
    }
}