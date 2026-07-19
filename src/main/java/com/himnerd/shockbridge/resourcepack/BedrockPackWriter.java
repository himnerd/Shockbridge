package com.himnerd.shockbridge.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BedrockPackWriter {

    private final UUID packUuid;
    private final String packVersion;
    private final String packName;
    private final Map<String, byte[]> entries = new LinkedHashMap<>();

    public BedrockPackWriter(UUID packUuid, String packVersion, String packName) {
        this.packUuid = packUuid;
        this.packVersion = packVersion;
        this.packName = packName;
    }

    public void addEntry(String path, byte[] data) {
        entries.put(path, data);
    }

    public void addJson(String path, JsonObject json) {
        entries.put(path, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Retrieves raw bytes for an existing entry, or null if not present.
     * Used by {@link ShockAssetScanner} to merge into existing atlas files.
     */
    public byte[] getEntry(String path) {
        return entries.get(path);
    }

    public byte[] build() throws IOException {
        entries.putIfAbsent("manifest.json", createManifest());
        entries.putIfAbsent("pack_icon.png", new byte[0]);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(
                entries.values().stream().mapToInt(b -> b.length).sum());
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (var entry : entries.entrySet()) {
                if (entry.getValue().length == 0 && entry.getKey().equals("pack_icon.png")) continue;
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private byte[] createManifest() {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format_version", 2);

        JsonObject header = new JsonObject();
        header.addProperty("name", packName);
        header.addProperty("description", "Converted by Shockbridge");
        header.addProperty("uuid", packUuid.toString());
        header.add("version", versionArray(packVersion));
        header.add("min_engine_version", versionArray("1.20.0"));
        manifest.add("header", header);

        JsonArray modules = new JsonArray();
        JsonObject mod = new JsonObject();
        mod.addProperty("type", "resources");
        mod.addProperty("uuid", UUID.randomUUID().toString());
        mod.add("version", versionArray("1.0.0"));
        modules.add(mod);
        manifest.add("modules", modules);

        return manifest.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonArray versionArray(String ver) {
        JsonArray arr = new JsonArray();
        for (String p : ver.split("\\.")) {
            try {
                arr.add(Integer.parseInt(p));
            } catch (NumberFormatException e) {
                arr.add(0);
            }
        }
        while (arr.size() < 3) arr.add(0);
        return arr;
    }
}