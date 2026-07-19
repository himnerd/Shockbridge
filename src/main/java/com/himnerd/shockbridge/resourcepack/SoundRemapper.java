package com.himnerd.shockbridge.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.himnerd.shockbridge.debug.AlphaDebugLogger;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class SoundRemapper {

    private final AlphaDebugLogger debugLogger;

    public SoundRemapper(AlphaDebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    public void remap(Map<String, byte[]> javaEntries, BedrockPackWriter writer) {
        int soundFileCount = 0;

        for (var entry : javaEntries.entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(".ogg") && !path.endsWith(".wav")) continue;
            String bedrockPath = remapSoundFilePath(path);
            if (bedrockPath != null) {
                writer.addEntry(bedrockPath, entry.getValue());
                soundFileCount++;
            }
        }

        byte[] soundsJson = javaEntries.get("assets/minecraft/sounds.json");
        if (soundsJson != null) {
            JsonObject bedrockDefs = convertSoundsJson(new String(soundsJson, StandardCharsets.UTF_8));
            writer.addJson("sounds/sound_definitions.json", bedrockDefs);
        }

        for (var entry : javaEntries.entrySet()) {
            if (entry.getKey().matches("assets/[^/]+/sounds\\.json")
                    && !entry.getKey().equals("assets/minecraft/sounds.json")) {
                JsonObject defs = convertSoundsJson(new String(entry.getValue(), StandardCharsets.UTF_8));
                writer.addJson("sounds/sound_definitions.json", defs);
            }
        }

        debugLogger.log("SoundRemapper: " + soundFileCount + " sound files converted");
    }

    private String remapSoundFilePath(String javaPath) {
        if (javaPath.startsWith("assets/minecraft/sounds/"))
            return "sounds/" + javaPath.substring("assets/minecraft/sounds/".length());
        if (javaPath.startsWith("assets/") && javaPath.contains("/sounds/")) {
            String after = javaPath.substring("assets/".length());
            int nsEnd = after.indexOf('/');
            if (nsEnd < 0) return null;
            String ns = after.substring(0, nsEnd);
            String rest = after.substring(nsEnd + "/sounds/".length());
            return "sounds/" + ns + "/" + rest;
        }
        return null;
    }

    private JsonObject convertSoundsJson(String javaSoundsJson) {
        JsonObject javaSounds;
        try {
            javaSounds = JsonParser.parseString(javaSoundsJson).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.14.0");
        JsonObject definitions = new JsonObject();

        for (var entry : javaSounds.entrySet()) {
            String soundId = entry.getKey();
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject javaDef = entry.getValue().getAsJsonObject();
            JsonObject bedrockDef = new JsonObject();

            String category = javaDef.has("category")
                    ? javaDef.get("category").getAsString()
                    : inferCategory(soundId);
            bedrockDef.addProperty("category", category);

            JsonArray bedrockSounds = new JsonArray();
            if (javaDef.has("sounds")) {
                for (JsonElement elem : javaDef.getAsJsonArray("sounds")) {
                    JsonObject bSound = new JsonObject();
                    if (elem.isJsonPrimitive()) {
                        bSound.addProperty("name", "sounds/" + elem.getAsString());
                    } else if (elem.isJsonObject()) {
                        JsonObject jSound = elem.getAsJsonObject();
                        bSound.addProperty("name", "sounds/" + jSound.get("name").getAsString());
                        if (jSound.has("volume")) bSound.add("volume", jSound.get("volume"));
                        if (jSound.has("pitch")) bSound.add("pitch", jSound.get("pitch"));
                        if (jSound.has("weight")) bSound.add("weight", jSound.get("weight"));
                    }
                    bedrockSounds.add(bSound);
                }
            }
            bedrockDef.add("sounds", bedrockSounds);
            definitions.add(soundId, bedrockDef);
        }

        root.add("sound_definitions", definitions);
        return root;
    }

    private static String inferCategory(String id) {
        if (id.startsWith("music")) return "music";
        if (id.startsWith("weather")) return "weather";
        if (id.startsWith("block")) return "block";
        if (id.startsWith("entity.hostile") || id.contains("zombie") || id.contains("skeleton"))
            return "hostile";
        if (id.startsWith("entity")) return "neutral";
        return "master";
    }
}