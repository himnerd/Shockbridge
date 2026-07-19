package com.himnerd.shockbridge.resourcepack;

import com.himnerd.shockbridge.ShockbridgePlugin;
import com.himnerd.shockbridge.debug.AlphaDebugLogger;
import com.himnerd.shockbridge.translation.ShockbridgeMappingRegistry;
import lombok.Getter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ResourcePackBridge {

    private final ShockbridgePlugin plugin;
    private final AlphaDebugLogger debugLogger;
    private final TextureRemapper textureRemapper;
    private final SoundRemapper soundRemapper;
    private final ModelRemapper modelRemapper;
    private final ShockAssetScanner assetScanner;
    private final VoxelToGeometryConverter geometryConverter;

    @Getter private byte[] bedrockPackData;
    @Getter private byte[] packHash;
    @Getter private UUID packUuid;
    @Getter private final String packVersion = "1.0.0";
    @Getter private volatile boolean packReady;

    public ResourcePackBridge(ShockbridgePlugin plugin) {
        this.plugin = plugin;
        this.debugLogger = plugin.getDebugLogger();
        this.textureRemapper = new TextureRemapper(debugLogger);
        this.soundRemapper = new SoundRemapper(debugLogger);
        this.modelRemapper = new ModelRemapper(debugLogger);
        this.geometryConverter = new VoxelToGeometryConverter();
        this.assetScanner = new ShockAssetScanner(debugLogger, geometryConverter);
        this.packUuid = UUID.randomUUID();
    }

    public CompletableFuture<Boolean> initialize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String source = plugin.getShockbridgeConfig().getResourcePackSource();
                byte[] javaPackBytes = loadJavaResourcePack(source);
                if (javaPackBytes == null) {
                    debugLogger.log("ResourcePackBridge: no Java resource pack found");
                    return false;
                }

                debugLogger.log("ResourcePackBridge: loaded Java pack (" + javaPackBytes.length + " bytes)");
                Map<String, byte[]> entries = extractZip(javaPackBytes);
                debugLogger.log("ResourcePackBridge: extracted " + entries.size() + " entries");

                BedrockPackWriter writer = new BedrockPackWriter(packUuid, packVersion, "Shockbridge Pack");
                textureRemapper.remap(entries, writer);
                soundRemapper.remap(entries, writer);
                modelRemapper.remap(entries, writer);

                // Phase 2: Deep asset scan — resolves parent chains, discovers custom_model_data
                // and minecraft:item_model overrides, generates Bedrock geometry, and populates
                // the bidirectional mapping registry for runtime packet translation.
                ShockbridgeMappingRegistry registry = plugin.getMappingRegistry();
                if (registry != null) {
                    ShockAssetScanner.ScanResult scanResult = assetScanner.scan(entries, writer, registry);
                    debugLogger.log("AssetScan: " + scanResult.customItemsRegistered() + " custom items, "
                            + scanResult.texturesDiscovered() + " textures, "
                            + scanResult.modelsResolved() + " models resolved in "
                            + (scanResult.scanDurationNanos() / 1_000_000L) + "ms");
                }

                bedrockPackData = writer.build();
                packHash = sha256(bedrockPackData);
                packReady = true;

                plugin.getLogger().info("[ResourcePack] Bedrock pack ready (" + bedrockPackData.length + " bytes)");
                return true;
            } catch (Exception e) {
                plugin.getLogger().warning("[ResourcePack] Conversion failed: " + e.getMessage());
                debugLogger.logException("ResourcePackBridge", e);
                return false;
            }
        });
    }

    private byte[] loadJavaResourcePack(String source) throws Exception {
        if (source == null || source.isEmpty() || source.equalsIgnoreCase("auto")) {
            return loadFromServerProperties();
        }
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return downloadPack(source);
        }
        Path path = plugin.getDataFolder().toPath().resolve(source);
        if (Files.exists(path)) return Files.readAllBytes(path);
        return null;
    }

    private byte[] loadFromServerProperties() throws Exception {
        Path propsPath = Path.of("server.properties");
        if (!Files.exists(propsPath)) return null;
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(propsPath)) {
            props.load(is);
        }
        String packUrl = props.getProperty("resource-pack", "").trim();
        if (packUrl.isEmpty()) return null;
        return downloadPack(packUrl);
    }

    private byte[] downloadPack(String url) throws Exception {
        debugLogger.log("ResourcePackBridge: downloading from " + url);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpResponse<byte[]> response = client.send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200)
            throw new IOException("HTTP " + response.statusCode() + " downloading resource pack");
        return response.body();
    }

    private static Map<String, byte[]> extractZip(byte[] zipData) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            return new byte[32];
        }
    }
}