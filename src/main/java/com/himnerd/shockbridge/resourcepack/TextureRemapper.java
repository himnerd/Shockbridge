package com.himnerd.shockbridge.resourcepack;

import com.google.gson.JsonObject;
import com.himnerd.shockbridge.debug.AlphaDebugLogger;

import java.util.Map;

public class TextureRemapper {

    private static final String[][] PATH_MAPPINGS = {
            {"assets/minecraft/textures/item/",        "textures/items/"},
            {"assets/minecraft/textures/items/",       "textures/items/"},
            {"assets/minecraft/textures/block/",       "textures/blocks/"},
            {"assets/minecraft/textures/blocks/",      "textures/blocks/"},
            {"assets/minecraft/textures/entity/",      "textures/entity/"},
            {"assets/minecraft/textures/gui/",         "textures/gui/"},
            {"assets/minecraft/textures/particle/",    "textures/particle/"},
            {"assets/minecraft/textures/painting/",    "textures/painting/"},
            {"assets/minecraft/textures/environment/", "textures/environment/"},
            {"assets/minecraft/textures/map/",         "textures/map/"},
            {"assets/minecraft/textures/misc/",        "textures/misc/"},
    };

    private final AlphaDebugLogger debugLogger;
    private final JsonObject terrainTexture = new JsonObject();
    private final JsonObject itemTexture = new JsonObject();
    private final JsonObject terrainData = new JsonObject();
    private final JsonObject itemData = new JsonObject();
    private int remappedCount;

    public TextureRemapper(AlphaDebugLogger debugLogger) {
        this.debugLogger = debugLogger;
        terrainTexture.addProperty("resource_pack_name", "Shockbridge");
        terrainTexture.addProperty("texture_name", "atlas.terrain");
        terrainTexture.add("texture_data", terrainData);
        itemTexture.addProperty("resource_pack_name", "Shockbridge");
        itemTexture.addProperty("texture_name", "atlas.items");
        itemTexture.add("texture_data", itemData);
    }

    public void remap(Map<String, byte[]> javaEntries, BedrockPackWriter writer) {
        for (var entry : javaEntries.entrySet()) {
            String javaPath = entry.getKey();
            if (!javaPath.endsWith(".png")) continue;

            String bedrockPath = remapPath(javaPath);
            if (bedrockPath == null) continue;

            writer.addEntry(bedrockPath, entry.getValue());
            registerInAtlas(javaPath, bedrockPath);
            remappedCount++;
        }

        if (terrainData.size() > 0) writer.addJson("textures/terrain_texture.json", terrainTexture);
        if (itemData.size() > 0) writer.addJson("textures/item_texture.json", itemTexture);

        debugLogger.log("TextureRemapper: " + remappedCount + " textures converted");
    }

    private String remapPath(String javaPath) {
        for (String[] mapping : PATH_MAPPINGS) {
            if (javaPath.startsWith(mapping[0])) {
                return mapping[1] + javaPath.substring(mapping[0].length());
            }
        }
        if (javaPath.startsWith("assets/") && javaPath.contains("/textures/")) {
            String afterAssets = javaPath.substring("assets/".length());
            int nsEnd = afterAssets.indexOf('/');
            if (nsEnd < 0) return null;
            String namespace = afterAssets.substring(0, nsEnd);
            if (namespace.equals("minecraft")) return null;
            String afterTextures = afterAssets.substring(nsEnd + 1);
            if (!afterTextures.startsWith("textures/")) return null;
            String rest = afterTextures.substring("textures/".length());
            if (rest.startsWith("item/") || rest.startsWith("items/"))
                return "textures/items/" + namespace + "/" + rest.replaceFirst("items?/", "");
            if (rest.startsWith("block/") || rest.startsWith("blocks/"))
                return "textures/blocks/" + namespace + "/" + rest.replaceFirst("blocks?/", "");
            return "textures/" + namespace + "/" + rest;
        }
        return null;
    }

    private void registerInAtlas(String javaPath, String bedrockPath) {
        String texRef = bedrockPath.replace(".png", "");
        String shortName = texRef;
        int lastSlash = shortName.lastIndexOf('/');
        if (lastSlash >= 0) shortName = shortName.substring(lastSlash + 1);

        JsonObject entry = new JsonObject();
        entry.addProperty("textures", texRef);

        if (bedrockPath.startsWith("textures/blocks/")) {
            terrainData.add(shortName, entry);
        } else if (bedrockPath.startsWith("textures/items/")) {
            itemData.add(shortName, entry);
        }
    }
}