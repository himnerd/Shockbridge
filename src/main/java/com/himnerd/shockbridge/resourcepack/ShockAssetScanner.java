package com.himnerd.shockbridge.resourcepack;

import com.google.gson.*;
import com.himnerd.shockbridge.debug.AlphaDebugLogger;
import com.himnerd.shockbridge.translation.ShockbridgeMappingRegistry;
import com.himnerd.shockbridge.translation.ShockbridgeMappingRegistry.BedrockItemMapping;
import com.himnerd.shockbridge.translation.ShockbridgeMappingRegistry.JavaItemKey;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Non-blocking asynchronous pipeline that scans Java Edition resource pack entries,
 * resolves model parent chains, discovers {@code custom_model_data} predicate overrides
 * and modern {@code minecraft:item_model} component definitions, then procedurally
 * generates Bedrock-compatible assets and populates the bidirectional mapping registry.
 * <p>
 * Designed for Java 21 — uses virtual threads via {@link Executors#newVirtualThreadPerTaskExecutor()}
 * for parallel model resolution and geometry conversion. Records are used for all
 * intermediate data structures; pattern matching dispatches JSON element types.
 *
 * @author HimnerdMC
 */
public class ShockAssetScanner {

    // ─── Data records (Java 21 pattern) ───────────────────────────────────

    /** A parsed Java model JSON with metadata extracted during indexing. */
    public record IndexedModel(String path, String namespace, String modelName,
                               JsonObject json, String parentRef,
                               boolean hasElements, List<DiscoveredOverride> overrides) {}

    /** A single custom_model_data or item_model override from a Java model's overrides array. */
    public record DiscoveredOverride(int customModelData, String itemModelId,
                                     String targetModelRef) {}

    /** A fully resolved custom item ready for Bedrock conversion. */
    public record ResolvedCustomItem(String sourceModelPath, String targetModelRef,
                                     int customModelData, String itemModelId,
                                     String baseMaterial, JsonObject resolvedModel,
                                     List<String> textureReferences) {}

    /** Texture mapping from Java path to Bedrock path with atlas key. */
    public record TextureMapping(String javaPath, String bedrockPath, String atlasKey) {}

    /** Immutable result of a complete asset scan pass. */
    public record ScanResult(int customItemsRegistered, int texturesDiscovered,
                             int modelsResolved, int overridesFound,
                             long scanDurationNanos) {}

    // ─── Constants ────────────────────────────────────────────────────────

    private static final String SHOCKBRIDGE_NAMESPACE = "shockbridge";
    private static final int MAX_PARENT_DEPTH = 16;
    private static final int RUNTIME_ID_BASE = 10000;

    // ─── Fields ───────────────────────────────────────────────────────────

    private final AlphaDebugLogger debugLogger;
    private final VoxelToGeometryConverter geometryConverter;
    private final ExecutorService virtualThreadPool;

    /** Raw parsed model JSONs keyed by normalized resource location (e.g., "minecraft:item/diamond_sword"). */
    private final Map<String, JsonObject> modelJsonCache = new ConcurrentHashMap<>();

    /** Fully flattened models (parent chain resolved) keyed by resource location. */
    private final Map<String, JsonObject> flattenedModelCache = new ConcurrentHashMap<>();

    private final AtomicInteger runtimeIdCounter = new AtomicInteger(RUNTIME_ID_BASE);

    public ShockAssetScanner(AlphaDebugLogger debugLogger, VoxelToGeometryConverter geometryConverter) {
        this.debugLogger = debugLogger;
        this.geometryConverter = geometryConverter;
        this.virtualThreadPool = Executors.newVirtualThreadPerTaskExecutor();
    }

    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Asynchronous entry point — wraps {@link #scan} on a virtual thread.
     */
    public CompletableFuture<ScanResult> scanAsync(Map<String, byte[]> entries,
                                                   BedrockPackWriter writer,
                                                   ShockbridgeMappingRegistry registry) {
        return CompletableFuture.supplyAsync(() -> scan(entries, writer, registry), virtualThreadPool);
    }

    /**
     * Synchronous full-pipeline scan. Called from within {@link com.himnerd.shockbridge.resourcepack.ResourcePackBridge#initialize()}'s
     * async context.
     *
     * <p><b>Pipeline phases:</b></p>
     * <ol>
     *   <li>Index all model JSONs from pack entries</li>
     *   <li>Resolve parent chains (flatten inheritance)</li>
     *   <li>Discover custom_model_data overrides + item_model components</li>
     *   <li>Resolve texture references for each custom item</li>
     *   <li>Generate Bedrock geometry + write to pack</li>
     *   <li>Build/merge item_texture.json atlas</li>
     *   <li>Populate mapping registry</li>
     * </ol>
     */
    public ScanResult scan(Map<String, byte[]> entries, BedrockPackWriter writer,
                           ShockbridgeMappingRegistry registry) {
        long startNanos = System.nanoTime();

        // Phase 1: Index all model JSONs
        List<IndexedModel> indexed = indexAllModels(entries);
        debugLogger.log("AssetScanner Phase1: indexed " + indexed.size() + " models");

        // Phase 2: Resolve parent chains for all indexed models
        int resolved = 0;
        for (IndexedModel model : indexed) {
            String resLoc = model.namespace() + ":" + model.modelName();
            if (!flattenedModelCache.containsKey(resLoc)) {
                JsonObject flat = resolveFullModel(resLoc, 0);
                if (flat != null) {
                    flattenedModelCache.put(resLoc, flat);
                    resolved++;
                }
            }
        }
        debugLogger.log("AssetScanner Phase2: resolved " + resolved + " parent chains");

        // Phase 3: Discover all custom items (overrides + item_model definitions)
        List<ResolvedCustomItem> customItems = new ArrayList<>();
        int totalOverrides = 0;

        for (IndexedModel model : indexed) {
            for (DiscoveredOverride override : model.overrides()) {
                totalOverrides++;
                ResolvedCustomItem item = resolveCustomItem(model, override, entries);
                if (item != null) customItems.add(item);
            }
        }

        // Phase 3b: Scan for modern minecraft:item_model definitions (1.21.4+)
        List<ResolvedCustomItem> itemModelItems = scanItemModelDefinitions(entries);
        customItems.addAll(itemModelItems);
        totalOverrides += itemModelItems.size();

        debugLogger.log("AssetScanner Phase3: " + customItems.size() + " custom items from "
                + totalOverrides + " overrides");

        // Phase 4+5: Generate Bedrock assets and collect texture mappings
        List<TextureMapping> allTextures = new ArrayList<>();
        int itemsRegistered = 0;

        for (ResolvedCustomItem item : customItems) {
            try {
                String sanitizedName = sanitizeIdentifier(item.targetModelRef());
                String bedrockId = SHOCKBRIDGE_NAMESPACE + ":" + sanitizedName;
                String geoId = "geometry." + SHOCKBRIDGE_NAMESPACE + "." + sanitizedName;

                // Ensure the model has elements (synthesize for generated-type models)
                JsonObject modelForConversion = ensureElements(item.resolvedModel());

                // ── Geometry conversion ───────────────────────────────────
                VoxelToGeometryConverter.ConversionResult geoResult =
                        geometryConverter.convert(geoId, modelForConversion, 16, 16);
                writer.addJson("models/entity/" + sanitizedName + ".geo.json", geoResult.geoJson());

                // ── Texture extraction ────────────────────────────────────
                List<TextureMapping> textures = processTextures(item, entries, writer);
                allTextures.addAll(textures);

                String primaryTexture = textures.isEmpty() ? ""
                        : textures.getFirst().bedrockPath().replace(".png", "");

                // ── Registry population ───────────────────────────────────
                int runtimeId = runtimeIdCounter.getAndIncrement();
                JavaItemKey javaKey = new JavaItemKey(
                        item.baseMaterial(),
                        item.customModelData(),
                        item.itemModelId()
                );
                BedrockItemMapping bedrockMapping = new BedrockItemMapping(
                        runtimeId, bedrockId, geoId, primaryTexture, sanitizedName
                );
                registry.register(javaKey, bedrockMapping);
                itemsRegistered++;

                debugLogger.log("AssetScanner: registered " + bedrockId
                        + " (CMD=" + item.customModelData() + ", runtime=" + runtimeId + ")");

            } catch (Exception e) {
                debugLogger.logException("AssetScanner.generateAsset[" + item.targetModelRef() + "]", e);
            }
        }

        // Phase 6: Merge custom item textures into item_texture.json atlas
        mergeItemTextureAtlas(allTextures, writer);

        long elapsed = System.nanoTime() - startNanos;
        return new ScanResult(itemsRegistered, allTextures.size(), resolved, totalOverrides, elapsed);
    }

    /**
     * Shuts down the virtual thread executor. Safe to call multiple times.
     */
    public void shutdown() {
        virtualThreadPool.shutdownNow();
    }

    // ─── Phase 1: Model indexing ──────────────────────────────────────────

    private List<IndexedModel> indexAllModels(Map<String, byte[]> entries) {
        List<IndexedModel> result = new ArrayList<>();

        for (var entry : entries.entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(".json") || !path.contains("/models/")) continue;

            try {
                JsonObject json = JsonParser.parseString(
                        new String(entry.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();

                // Cache by normalized resource location
                String[] parsed = parseModelPath(path);
                if (parsed == null) continue;
                String namespace = parsed[0];
                String modelName = parsed[1];
                String resLoc = namespace + ":" + modelName;
                modelJsonCache.put(resLoc, json);

                // Extract parent reference
                String parentRef = json.has("parent") ? json.get("parent").getAsString() : null;
                boolean hasElements = json.has("elements") && json.get("elements").isJsonArray();

                // Extract overrides
                List<DiscoveredOverride> overrides = extractOverrides(json);

                result.add(new IndexedModel(path, namespace, modelName, json,
                        parentRef, hasElements, overrides));

            } catch (Exception e) {
                debugLogger.log("AssetScanner: failed to parse model " + path);
            }
        }
        return result;
    }

    private List<DiscoveredOverride> extractOverrides(JsonObject model) {
        List<DiscoveredOverride> overrides = new ArrayList<>();
        if (!model.has("overrides") || !model.get("overrides").isJsonArray()) return overrides;

        for (JsonElement elem : model.getAsJsonArray("overrides")) {
            if (!(elem instanceof JsonObject ov)) continue;
            if (!ov.has("predicate") || !ov.has("model")) continue;

            JsonObject predicate = ov.getAsJsonObject("predicate");
            String modelRef = ov.get("model").getAsString();
            int cmd = -1;
            String itemModelId = null;

            // Legacy custom_model_data predicate (integer or float)
            if (predicate.has("custom_model_data")) {
                JsonElement cmdElem = predicate.get("custom_model_data");
                cmd = switch (cmdElem) {
                    case JsonPrimitive p when p.isNumber() -> p.getAsInt();
                    default -> -1;
                };
            }

            // Modern item_model component reference
            if (predicate.has("item_model")) {
                itemModelId = predicate.get("item_model").getAsString();
            }

            if (cmd >= 0 || itemModelId != null) {
                overrides.add(new DiscoveredOverride(cmd, itemModelId, modelRef));
            }
        }
        return overrides;
    }

    // ─── Phase 2: Parent chain resolution ─────────────────────────────────

    /**
     * Recursively resolves a model's parent chain, flattening inherited
     * elements, textures, and display transforms into a single JsonObject.
     * Child properties override parent properties at each level.
     *
     * @param resLoc resource location (e.g., "mymod:item/laser_rifle")
     * @param depth  current recursion depth (capped at {@link #MAX_PARENT_DEPTH})
     * @return the fully flattened model, or null if resolution fails
     */
    private JsonObject resolveFullModel(String resLoc, int depth) {
        if (depth > MAX_PARENT_DEPTH) return null;

        // Check flatten cache first
        JsonObject cached = flattenedModelCache.get(resLoc);
        if (cached != null) return cached;

        // Find the raw model
        JsonObject raw = modelJsonCache.get(resLoc);
        if (raw == null) {
            // Try without namespace prefix for common patterns
            raw = modelJsonCache.get("minecraft:" + resLoc);
            if (raw == null) return null;
        }

        // If no parent, this model is already fully resolved
        if (!raw.has("parent") || raw.get("parent").isJsonNull()) {
            flattenedModelCache.put(resLoc, raw);
            return raw;
        }

        // Resolve parent recursively
        String parentRef = normalizeModelRef(raw.get("parent").getAsString());
        JsonObject parentResolved = resolveFullModel(parentRef, depth + 1);

        // Merge: child overrides parent
        JsonObject merged = new JsonObject();

        // Elements: child takes priority; if absent, inherit from parent
        if (raw.has("elements")) {
            merged.add("elements", raw.get("elements").deepCopy());
        } else if (parentResolved != null && parentResolved.has("elements")) {
            merged.add("elements", parentResolved.get("elements").deepCopy());
        }

        // Textures: merge parent + child (child overrides individual keys)
        JsonObject mergedTextures = new JsonObject();
        if (parentResolved != null && parentResolved.has("textures")) {
            for (var e : parentResolved.getAsJsonObject("textures").entrySet()) {
                mergedTextures.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        if (raw.has("textures")) {
            for (var e : raw.getAsJsonObject("textures").entrySet()) {
                mergedTextures.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        if (mergedTextures.size() > 0) merged.add("textures", mergedTextures);

        // Display: merge parent + child contexts (child overrides per-context)
        JsonObject mergedDisplay = new JsonObject();
        if (parentResolved != null && parentResolved.has("display")) {
            for (var e : parentResolved.getAsJsonObject("display").entrySet()) {
                mergedDisplay.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        if (raw.has("display")) {
            for (var e : raw.getAsJsonObject("display").entrySet()) {
                mergedDisplay.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        if (mergedDisplay.size() > 0) merged.add("display", mergedDisplay);

        // Carry over overrides from the original (not inherited)
        if (raw.has("overrides")) {
            merged.add("overrides", raw.get("overrides").deepCopy());
        }

        flattenedModelCache.put(resLoc, merged);
        return merged;
    }

    // ─── Phase 3: Custom item resolution ──────────────────────────────────

    private ResolvedCustomItem resolveCustomItem(IndexedModel sourceModel,
                                                 DiscoveredOverride override,
                                                 Map<String, byte[]> entries) {
        String targetRef = normalizeModelRef(override.targetModelRef());
        JsonObject resolved = resolveFullModel(targetRef, 0);
        if (resolved == null) {
            // Fall back to looking up the target in raw cache
            resolved = modelJsonCache.get(targetRef);
        }
        if (resolved == null) return null;

        String baseMaterial = inferMaterialFromPath(sourceModel.path());
        List<String> textureRefs = collectTextureReferences(resolved);

        return new ResolvedCustomItem(
                sourceModel.path(), override.targetModelRef(),
                override.customModelData(), override.itemModelId(),
                baseMaterial, resolved, textureRefs
        );
    }

    /**
     * Scans for modern {@code minecraft:item_model} component definitions
     * found in {@code assets/<namespace>/items/*.json} (1.21.4+ format).
     */
    private List<ResolvedCustomItem> scanItemModelDefinitions(Map<String, byte[]> entries) {
        List<ResolvedCustomItem> results = new ArrayList<>();

        for (var entry : entries.entrySet()) {
            String path = entry.getKey();
            // Modern item definitions live in assets/<ns>/items/<name>.json
            if (!path.endsWith(".json") || !path.contains("/items/")) continue;
            if (path.contains("/models/")) continue; // Skip model files

            try {
                JsonObject json = JsonParser.parseString(
                        new String(entry.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();

                if (!json.has("model")) continue;
                JsonObject modelDef = json.getAsJsonObject("model");

                processItemModelDefinition(path, modelDef, results);

            } catch (Exception e) {
                debugLogger.log("AssetScanner: failed to parse item definition " + path);
            }
        }
        return results;
    }

    private void processItemModelDefinition(String defPath, JsonObject modelDef,
                                            List<ResolvedCustomItem> results) {
        String type = modelDef.has("type") ? modelDef.get("type").getAsString() : "";

        switch (type) {
            case "minecraft:model" -> {
                if (!modelDef.has("model")) return;
                String modelRef = modelDef.get("model").getAsString();
                String normalizedRef = normalizeModelRef(modelRef);
                JsonObject resolved = resolveFullModel(normalizedRef, 0);
                if (resolved == null) return;

                String baseMaterial = inferMaterialFromItemDefPath(defPath);
                List<String> textures = collectTextureReferences(resolved);

                results.add(new ResolvedCustomItem(
                        defPath, modelRef, -1, modelRef,
                        baseMaterial, resolved, textures
                ));
            }
            case "minecraft:select" -> {
                // Conditional model selection based on properties
                if (!modelDef.has("cases") || !modelDef.get("cases").isJsonArray()) return;

                for (JsonElement caseElem : modelDef.getAsJsonArray("cases")) {
                    if (!(caseElem instanceof JsonObject caseObj)) continue;
                    if (!caseObj.has("model")) continue;

                    JsonObject innerModel = caseObj.getAsJsonObject("model");
                    processItemModelDefinition(defPath, innerModel, results);
                }
            }
            case "minecraft:composite" -> {
                // Composite models — process each sub-model
                if (!modelDef.has("models") || !modelDef.get("models").isJsonArray()) return;
                for (JsonElement subElem : modelDef.getAsJsonArray("models")) {
                    if (subElem instanceof JsonObject subModel) {
                        processItemModelDefinition(defPath, subModel, results);
                    }
                }
            }
            default -> {} // Unknown type — skip silently
        }
    }

    // ─── Phase 4: Texture processing ──────────────────────────────────────

    private List<TextureMapping> processTextures(ResolvedCustomItem item,
                                                 Map<String, byte[]> entries,
                                                 BedrockPackWriter writer) {
        List<TextureMapping> mappings = new ArrayList<>();

        for (String texRef : item.textureReferences()) {
            // Resolve texture reference to actual file path
            String javaTexPath = resolveTexturePath(texRef);
            byte[] texData = entries.get(javaTexPath);

            // Try with .png extension variants
            if (texData == null) texData = entries.get(javaTexPath + ".png");
            if (texData == null && !javaTexPath.endsWith(".png")) {
                texData = entries.get(javaTexPath.replace(".png", "") + ".png");
            }
            if (texData == null) continue;

            // Map to Bedrock path
            String bedrockPath = remapTexturePath(javaTexPath);
            String atlasKey = extractAtlasKey(bedrockPath);

            writer.addEntry(bedrockPath, texData);
            mappings.add(new TextureMapping(javaTexPath, bedrockPath, atlasKey));
        }
        return mappings;
    }

    // ─── Phase 6: Atlas merging ───────────────────────────────────────────

    /**
     * Merges custom item texture entries into the Bedrock {@code item_texture.json} atlas.
     * Reads any existing atlas from the writer (produced by {@link TextureRemapper}),
     * appends custom entries, and writes the merged result back.
     */
    private void mergeItemTextureAtlas(List<TextureMapping> textures, BedrockPackWriter writer) {
        if (textures.isEmpty()) return;

        JsonObject atlas;
        byte[] existing = writer.getEntry("textures/item_texture.json");
        if (existing != null) {
            try {
                atlas = JsonParser.parseString(new String(existing, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                atlas = createEmptyAtlas();
            }
        } else {
            atlas = createEmptyAtlas();
        }

        JsonObject textureData = atlas.has("texture_data")
                ? atlas.getAsJsonObject("texture_data")
                : new JsonObject();

        for (TextureMapping mapping : textures) {
            JsonObject entry = new JsonObject();
            entry.addProperty("textures", mapping.bedrockPath().replace(".png", ""));
            textureData.add(mapping.atlasKey(), entry);
        }

        atlas.add("texture_data", textureData);
        writer.addJson("textures/item_texture.json", atlas);

        debugLogger.log("AssetScanner: merged " + textures.size() + " custom textures into item atlas");
    }

    private static JsonObject createEmptyAtlas() {
        JsonObject atlas = new JsonObject();
        atlas.addProperty("resource_pack_name", "Shockbridge");
        atlas.addProperty("texture_name", "atlas.items");
        atlas.add("texture_data", new JsonObject());
        return atlas;
    }

    // ─── Model utilities ──────────────────────────────────────────────────

    private JsonObject ensureElements(JsonObject model) {
        if (model.has("elements") && model.getAsJsonArray("elements").size() > 0) {
            return model;
        }

        // Synthesize flat quad for generated-type models with layer textures
        if (model.has("textures")) {
            JsonObject textures = model.getAsJsonObject("textures");
            if (textures.has("layer0") || textures.has("particle")) {
                JsonObject augmented = model.deepCopy();
                JsonArray elements = new JsonArray();
                elements.add(geometryConverter.synthesizeGeneratedItemElement());
                augmented.add("elements", elements);
                return augmented;
            }
        }
        return model;
    }

    private List<String> collectTextureReferences(JsonObject model) {
        List<String> refs = new ArrayList<>();
        if (!model.has("textures") || !model.get("textures").isJsonObject()) return refs;

        JsonObject textures = model.getAsJsonObject("textures");
        for (var entry : textures.entrySet()) {
            String value = entry.getValue().getAsString();
            // Skip self-references (e.g., "#layer0")
            if (!value.startsWith("#")) {
                refs.add(value);
            }
        }
        return refs;
    }

    private String resolveTexturePath(String textureRef) {
        // textureRef format: "namespace:path/to/texture" or "path/to/texture"
        String namespace = "minecraft";
        String path = textureRef;

        if (textureRef.contains(":")) {
            String[] parts = textureRef.split(":", 2);
            namespace = parts[0];
            path = parts[1];
        }

        return "assets/" + namespace + "/textures/" + path + ".png";
    }

    private String remapTexturePath(String javaPath) {
        // assets/minecraft/textures/item/sword.png → textures/items/sword.png
        if (javaPath.contains("/textures/item/") || javaPath.contains("/textures/items/")) {
            String fileName = javaPath.substring(javaPath.lastIndexOf('/') + 1);
            String namespace = extractNamespace(javaPath);
            return "minecraft".equals(namespace)
                    ? "textures/items/" + fileName
                    : "textures/items/" + namespace + "/" + fileName;
        }
        if (javaPath.contains("/textures/block/") || javaPath.contains("/textures/blocks/")) {
            String fileName = javaPath.substring(javaPath.lastIndexOf('/') + 1);
            String namespace = extractNamespace(javaPath);
            return "minecraft".equals(namespace)
                    ? "textures/blocks/" + fileName
                    : "textures/blocks/" + namespace + "/" + fileName;
        }
        // Fallback: preserve relative structure under textures/
        String afterTextures = javaPath.contains("/textures/")
                ? javaPath.substring(javaPath.indexOf("/textures/") + 1)
                : "textures/" + javaPath.substring(javaPath.lastIndexOf('/') + 1);
        return afterTextures;
    }

    private static String extractAtlasKey(String bedrockPath) {
        String key = bedrockPath.replace(".png", "");
        int lastSlash = key.lastIndexOf('/');
        return (lastSlash >= 0) ? key.substring(lastSlash + 1) : key;
    }

    private static String extractNamespace(String path) {
        // assets/<namespace>/textures/...
        if (path.startsWith("assets/")) {
            String afterAssets = path.substring("assets/".length());
            int slash = afterAssets.indexOf('/');
            if (slash > 0) return afterAssets.substring(0, slash);
        }
        return "minecraft";
    }

    // ─── Path normalization ───────────────────────────────────────────────

    /**
     * Parses a zip entry path like "assets/mymod/models/item/laser.json"
     * into [namespace, modelName] = ["mymod", "item/laser"].
     */
    private String[] parseModelPath(String zipPath) {
        if (!zipPath.startsWith("assets/")) return null;
        String afterAssets = zipPath.substring("assets/".length());
        int nsEnd = afterAssets.indexOf('/');
        if (nsEnd < 0) return null;
        String namespace = afterAssets.substring(0, nsEnd);

        // Find "/models/" segment
        int modelsIdx = afterAssets.indexOf("/models/");
        if (modelsIdx < 0) return null;
        String modelName = afterAssets.substring(modelsIdx + "/models/".length());
        if (modelName.endsWith(".json")) modelName = modelName.substring(0, modelName.length() - 5);

        return new String[]{namespace, modelName};
    }

    /**
     * Normalizes a model reference by ensuring namespace prefix.
     * "item/diamond_sword" → "minecraft:item/diamond_sword"
     * "mymod:item/laser" → "mymod:item/laser"
     */
    private static String normalizeModelRef(String ref) {
        if (ref == null) return "minecraft:unknown";
        return ref.contains(":") ? ref : "minecraft:" + ref;
    }

    /**
     * Infers the base Java material name from a model file path.
     * "assets/minecraft/models/item/diamond_sword.json" → "diamond_sword"
     */
    private static String inferMaterialFromPath(String path) {
        String name = path;
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        if (name.endsWith(".json")) name = name.substring(0, name.length() - 5);
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Infers material from an item definition path.
     * "assets/mymod/items/laser_rifle.json" → "laser_rifle"
     */
    private static String inferMaterialFromItemDefPath(String path) {
        return inferMaterialFromPath(path);
    }

    /**
     * Sanitizes a model reference into a valid Bedrock identifier component.
     * "mymod:item/laser_rifle" → "mymod_item_laser_rifle"
     */
    private static String sanitizeIdentifier(String ref) {
        return ref.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT);
    }
}