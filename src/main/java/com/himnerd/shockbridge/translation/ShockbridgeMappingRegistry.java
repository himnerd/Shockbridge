package com.himnerd.shockbridge.translation;

import com.himnerd.shockbridge.debug.AlphaDebugLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe bidirectional mapping registry that stores pairings between
 * Java Edition item identifiers and Bedrock Edition custom item runtime
 * data. Designed for zero-lock hot-path reads using {@link ConcurrentHashMap}
 * with atomic runtime ID generation.
 * <p>
 * <b>Double-keyed layout:</b>
 * <ul>
 *   <li><b>Java → Bedrock</b> (by custom_model_data): material → CMD → BedrockItemMapping</li>
 *   <li><b>Java → Bedrock</b> (by item_model): itemModelId → BedrockItemMapping</li>
 *   <li><b>Bedrock → Java</b> (by identifier): bedrockIdentifier → JavaItemKey</li>
 *   <li><b>Bedrock → Java</b> (by runtimeId): runtimeId → JavaItemKey</li>
 * </ul>
 * <p>
 * Hook methods {@link #translateToBedrock} and {@link #translateToJava} are intended
 * for direct invocation from Shockbridge's internal packet decoder loop in
 * {@link com.himnerd.shockbridge.injection.OutboundPacketInterceptor}.
 *
 * @author HimnerdMC
 */
public class ShockbridgeMappingRegistry {

    // ─── Records (Java 21 data carriers) ──────────────────────────────────

    /**
     * Composite key identifying a Java item variant.
     *
     * @param material        base material name (e.g., "diamond_sword"), lowercase
     * @param customModelData the CMD integer predicate value, or -1 if using item_model
     * @param itemModelId     the minecraft:item_model component value, or null if using CMD
     */
    public record JavaItemKey(String material, int customModelData, String itemModelId) {
        /** Convenience constructor for legacy CMD-based lookups. */
        public JavaItemKey(String material, int customModelData) {
            this(material, customModelData, null);
        }
        /** Convenience constructor for modern item_model-based lookups. */
        public JavaItemKey(String material, String itemModelId) {
            this(material, -1, itemModelId);
        }
    }

    /**
     * Bedrock-side mapping data for a single custom item.
     *
     * @param runtimeId         numeric runtime ID sent in StartGame itemStates
     * @param bedrockIdentifier namespaced identifier (e.g., "shockbridge:laser_rifle")
     * @param geometryId        Bedrock geometry reference (e.g., "geometry.shockbridge.laser_rifle")
     * @param texturePath       Bedrock texture path without extension
     * @param displayName       human-readable display name
     */
    public record BedrockItemMapping(int runtimeId, String bedrockIdentifier,
                                     String geometryId, String texturePath,
                                     String displayName) {}

    /**
     * A paired mapping entry for bulk registration operations.
     */
    public record MappingEntry(JavaItemKey javaKey, BedrockItemMapping bedrockMapping) {}

    /**
     * Item state record for the StartGame packet's itemStates array.
     *
     * @param identifier     namespaced string identifier
     * @param runtimeId      numeric shorthand sent to the client
     * @param componentBased whether this item uses data-driven components
     */
    public record CustomItemState(String identifier, int runtimeId, boolean componentBased) {}

    /**
     * Immutable snapshot of the registry state at a point in time.
     */
    public record MappingSnapshot(Map<JavaItemKey, BedrockItemMapping> entries,
                                  int totalCount, long snapshotTimeMillis) {}

    // ─── Constants ────────────────────────────────────────────────────────

    /** First runtime ID assigned to custom items (above vanilla range). */
    private static final int RUNTIME_ID_FLOOR = 10000;

    // ─── Mapping tables ───────────────────────────────────────────────────

    /**
     * Forward lookup: (material, customModelData) → BedrockItemMapping.
     * Outer map keyed by lowercase material name; inner map keyed by CMD integer.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, BedrockItemMapping>>
            cmdForwardMap = new ConcurrentHashMap<>();

    /** Forward lookup: itemModelId → BedrockItemMapping (modern 1.21.4+ path). */
    private final ConcurrentHashMap<String, BedrockItemMapping> modelForwardMap = new ConcurrentHashMap<>();

    /** Reverse lookup: bedrockIdentifier → JavaItemKey. */
    private final ConcurrentHashMap<String, JavaItemKey> identifierReverseMap = new ConcurrentHashMap<>();

    /** Reverse lookup: runtimeId → JavaItemKey. */
    private final ConcurrentHashMap<Integer, JavaItemKey> runtimeReverseMap = new ConcurrentHashMap<>();

    /** Master list of all Bedrock mappings for iteration (e.g., StartGame packet). */
    private final ConcurrentHashMap<String, BedrockItemMapping> allMappings = new ConcurrentHashMap<>();

    /** Monotonically increasing runtime ID generator. */
    private final AtomicInteger runtimeIdGenerator = new AtomicInteger(RUNTIME_ID_FLOOR);

    private final AlphaDebugLogger debugLogger;

    // ─── Constructor ──────────────────────────────────────────────────────

    public ShockbridgeMappingRegistry(AlphaDebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    // ─── Registration ─────────────────────────────────────────────────────

    /**
     * Registers a single Java↔Bedrock item mapping. Thread-safe; overwrites any
     * existing mapping for the same keys with a warning.
     */
    public void register(JavaItemKey javaKey, BedrockItemMapping bedrockMapping) {
        Objects.requireNonNull(javaKey, "javaKey must not be null");
        Objects.requireNonNull(bedrockMapping, "bedrockMapping must not be null");

        // Forward: CMD path
        if (javaKey.customModelData() >= 0 && javaKey.material() != null) {
            String matLower = javaKey.material().toLowerCase(Locale.ROOT);
            ConcurrentHashMap<Integer, BedrockItemMapping> cmdMap =
                    cmdForwardMap.computeIfAbsent(matLower, k -> new ConcurrentHashMap<>());
            BedrockItemMapping prev = cmdMap.put(javaKey.customModelData(), bedrockMapping);
            if (prev != null) {
                debugLogger.log("MappingRegistry: overwriting CMD mapping for "
                        + matLower + ":" + javaKey.customModelData());
            }
        }

        // Forward: item_model path
        if (javaKey.itemModelId() != null && !javaKey.itemModelId().isEmpty()) {
            BedrockItemMapping prev = modelForwardMap.put(javaKey.itemModelId(), bedrockMapping);
            if (prev != null) {
                debugLogger.log("MappingRegistry: overwriting item_model mapping for "
                        + javaKey.itemModelId());
            }
        }

        // Reverse: identifier → Java
        identifierReverseMap.put(bedrockMapping.bedrockIdentifier(), javaKey);

        // Reverse: runtimeId → Java
        runtimeReverseMap.put(bedrockMapping.runtimeId(), javaKey);

        // Master list
        allMappings.put(bedrockMapping.bedrockIdentifier(), bedrockMapping);
    }

    /**
     * Registers multiple mappings atomically. Returns the count successfully registered.
     */
    public int registerBulk(Collection<MappingEntry> entries) {
        int count = 0;
        for (MappingEntry entry : entries) {
            try {
                register(entry.javaKey(), entry.bedrockMapping());
                count++;
            } catch (Exception e) {
                debugLogger.logException("MappingRegistry.registerBulk", e);
            }
        }
        debugLogger.log("MappingRegistry: bulk registered " + count + "/" + entries.size() + " mappings");
        return count;
    }

    // ─── Translation hooks (hot path — zero-lock reads) ───────────────────

    /**
     * Translates a Java custom item (by base material + custom_model_data integer)
     * to its Bedrock mapping. Called from the packet decoder loop when intercepting
     * Java → Bedrock item packets.
     *
     * @param material        the Java material name (case-insensitive)
     * @param customModelData the CMD integer from the item's NBT or components
     * @return the Bedrock mapping, or empty if no mapping exists for this combination
     */
    public Optional<BedrockItemMapping> translateToBedrock(String material, int customModelData) {
        if (material == null) return Optional.empty();
        ConcurrentHashMap<Integer, BedrockItemMapping> cmdMap =
                cmdForwardMap.get(material.toLowerCase(Locale.ROOT));
        if (cmdMap == null) return Optional.empty();
        return Optional.ofNullable(cmdMap.get(customModelData));
    }

    /**
     * Translates a Java custom item (by item_model component string) to its
     * Bedrock mapping. Used for modern 1.21.4+ item_model component resolution.
     *
     * @param itemModelId the minecraft:item_model component value
     * @return the Bedrock mapping, or empty if unmapped
     */
    public Optional<BedrockItemMapping> translateToBedrockByModel(String itemModelId) {
        if (itemModelId == null) return Optional.empty();
        return Optional.ofNullable(modelForwardMap.get(itemModelId));
    }

    /**
     * Reverse-translates a Bedrock custom item identifier back to its Java key.
     * Called when processing Bedrock → Java packets (e.g., inventory interactions).
     *
     * @param bedrockIdentifier the Bedrock identifier (e.g., "shockbridge:laser_rifle")
     * @return the Java item key, or empty if unmapped
     */
    public Optional<JavaItemKey> translateToJava(String bedrockIdentifier) {
        if (bedrockIdentifier == null) return Optional.empty();
        return Optional.ofNullable(identifierReverseMap.get(bedrockIdentifier));
    }

    /**
     * Reverse-translates a Bedrock runtime ID back to its Java key.
     *
     * @param runtimeId the numeric runtime ID from the Bedrock packet
     * @return the Java item key, or empty if unmapped
     */
    public Optional<JavaItemKey> translateToJava(int runtimeId) {
        return Optional.ofNullable(runtimeReverseMap.get(runtimeId));
    }

    // ─── Query methods ────────────────────────────────────────────────────

    /** Checks if a mapping exists for the given material + CMD combination. */
    public boolean containsMapping(String material, int customModelData) {
        if (material == null) return false;
        ConcurrentHashMap<Integer, BedrockItemMapping> cmdMap =
                cmdForwardMap.get(material.toLowerCase(Locale.ROOT));
        return cmdMap != null && cmdMap.containsKey(customModelData);
    }

    /** Checks if a mapping exists for the given Bedrock identifier. */
    public boolean containsMapping(String bedrockIdentifier) {
        return bedrockIdentifier != null && identifierReverseMap.containsKey(bedrockIdentifier);
    }

    /**
     * Retrieves a Bedrock mapping by its identifier string.
     */
    public Optional<BedrockItemMapping> getByIdentifier(String bedrockIdentifier) {
        return Optional.ofNullable(allMappings.get(bedrockIdentifier));
    }

    /**
     * Retrieves a Bedrock mapping by its runtime ID.
     */
    public Optional<BedrockItemMapping> getByRuntimeId(int runtimeId) {
        return translateToJava(runtimeId)
                .flatMap(javaKey -> {
                    if (javaKey.customModelData() >= 0 && javaKey.material() != null) {
                        return translateToBedrock(javaKey.material(), javaKey.customModelData());
                    }
                    if (javaKey.itemModelId() != null) {
                        return translateToBedrockByModel(javaKey.itemModelId());
                    }
                    return Optional.empty();
                });
    }

    /** Total number of registered custom item mappings. */
    public int getMappingCount() {
        return allMappings.size();
    }

    /** Returns all registered Bedrock identifiers. */
    public Set<String> getAllBedrockIdentifiers() {
        return Collections.unmodifiableSet(allMappings.keySet());
    }

    /** Generates the next available runtime ID (thread-safe, monotonic). */
    public int generateNextRuntimeId() {
        return runtimeIdGenerator.getAndIncrement();
    }

    // ─── StartGame integration ────────────────────────────────────────────

    /**
     * Builds the custom item states list for the Bedrock StartGame packet.
     * Each entry tells the client about a custom item's identifier and runtime ID.
     *
     * @return immutable list of custom item states, ordered by runtime ID
     */
    public List<CustomItemState> getCustomItemStates() {
        return allMappings.values().stream()
                .sorted(Comparator.comparingInt(BedrockItemMapping::runtimeId))
                .map(mapping -> new CustomItemState(
                        mapping.bedrockIdentifier(),
                        mapping.runtimeId(),
                        true // component-based for modern Bedrock clients
                ))
                .toList();
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Creates an immutable snapshot of all current mappings. Useful for debug
     * dumps and API consumers that need a consistent view.
     */
    public MappingSnapshot snapshot() {
        Map<JavaItemKey, BedrockItemMapping> entries = new LinkedHashMap<>();
        for (var mapping : allMappings.entrySet()) {
            JavaItemKey javaKey = identifierReverseMap.get(mapping.getKey());
            if (javaKey != null) {
                entries.put(javaKey, mapping.getValue());
            }
        }
        return new MappingSnapshot(
                Collections.unmodifiableMap(entries),
                entries.size(),
                System.currentTimeMillis()
        );
    }

    /**
     * Clears all mappings. Called during hot-swap deactivation or resource pack reload.
     */
    public void clear() {
        cmdForwardMap.clear();
        modelForwardMap.clear();
        identifierReverseMap.clear();
        runtimeReverseMap.clear();
        allMappings.clear();
        runtimeIdGenerator.set(RUNTIME_ID_FLOOR);
        debugLogger.log("MappingRegistry: cleared all mappings");
    }
}