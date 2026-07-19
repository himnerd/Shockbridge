package com.himnerd.shockbridge.resourcepack;

import com.google.gson.*;
import com.himnerd.shockbridge.debug.AlphaDebugLogger;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class ModelRemapper {

    private final AlphaDebugLogger debugLogger;

    public ModelRemapper(AlphaDebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    public void remap(Map<String, byte[]> javaEntries, BedrockPackWriter writer) {
        Map<String, JsonObject> models = new LinkedHashMap<>();
        for (var entry : javaEntries.entrySet()) {
            if (!entry.getKey().endsWith(".json") || !entry.getKey().contains("/models/")) continue;
            try {
                models.put(entry.getKey(), JsonParser.parseString(
                        new String(entry.getValue(), StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (Exception ignored) {
            }
        }

        int converted = 0;
        for (var entry : models.entrySet()) {
            JsonObject model = entry.getValue();

            if (model.has("overrides")) {
                for (JsonElement override : model.getAsJsonArray("overrides")) {
                    if (!override.isJsonObject()) continue;
                    JsonObject ov = override.getAsJsonObject();
                    if (!ov.has("predicate") || !ov.has("model")) continue;
                    JsonObject predicate = ov.getAsJsonObject("predicate");
                    if (!predicate.has("custom_model_data")) continue;

                    String overrideRef = ov.get("model").getAsString();
                    JsonObject overrideModel = findModel(models, overrideRef);
                    if (overrideModel == null || !overrideModel.has("elements")) continue;

                    String geoId = "geometry.shockbridge." + sanitize(overrideRef);
                    JsonObject geo = convertToGeometry(geoId, overrideModel);
                    writer.addJson("models/entity/" + sanitize(overrideRef) + ".geo.json", geo);
                    converted++;
                }
            }

            if (model.has("elements") && !model.has("overrides")) {
                String geoId = "geometry.shockbridge." + sanitize(entry.getKey());
                JsonObject geo = convertToGeometry(geoId, model);
                writer.addJson("models/entity/" + sanitize(entry.getKey()) + ".geo.json", geo);
                converted++;
            }
        }

        debugLogger.log("ModelRemapper: " + converted + " models converted");
    }

    private JsonObject findModel(Map<String, JsonObject> models, String ref) {
        String normalized = ref.replace(":", "/");
        if (!normalized.contains("/")) normalized = "minecraft/" + normalized;
        for (var entry : models.entrySet()) {
            String stripped = entry.getKey()
                    .replace("assets/", "")
                    .replace("/models/", "/")
                    .replace(".json", "");
            if (stripped.equals(normalized) || stripped.endsWith(normalized)) return entry.getValue();
        }
        return null;
    }

    private JsonObject convertToGeometry(String geoId, JsonObject javaModel) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.16.0");

        JsonArray geometries = new JsonArray();
        JsonObject geo = new JsonObject();

        JsonObject desc = new JsonObject();
        desc.addProperty("identifier", geoId);
        desc.addProperty("texture_width", 16);
        desc.addProperty("texture_height", 16);
        geo.add("description", desc);

        JsonArray bones = new JsonArray();
        JsonObject rootBone = new JsonObject();
        rootBone.addProperty("name", "root");
        JsonArray pivot = new JsonArray();
        pivot.add(0);
        pivot.add(0);
        pivot.add(0);
        rootBone.add("pivot", pivot);

        JsonArray cubes = new JsonArray();
        if (javaModel.has("elements")) {
            for (JsonElement elem : javaModel.getAsJsonArray("elements")) {
                if (!elem.isJsonObject()) continue;
                JsonObject cube = convertElement(elem.getAsJsonObject());
                if (cube != null) cubes.add(cube);
            }
        }
        rootBone.add("cubes", cubes);
        bones.add(rootBone);
        geo.add("bones", bones);
        geometries.add(geo);
        root.add("minecraft:geometry", geometries);
        return root;
    }

    private JsonObject convertElement(JsonObject elem) {
        if (!elem.has("from") || !elem.has("to")) return null;

        JsonArray from = elem.getAsJsonArray("from");
        JsonArray to = elem.getAsJsonArray("to");
        double x1 = from.get(0).getAsDouble(), y1 = from.get(1).getAsDouble(), z1 = from.get(2).getAsDouble();
        double x2 = to.get(0).getAsDouble(), y2 = to.get(1).getAsDouble(), z2 = to.get(2).getAsDouble();

        JsonObject cube = new JsonObject();

        JsonArray origin = new JsonArray();
        origin.add(x1 - 8);
        origin.add(y1);
        origin.add(z1 - 8);
        cube.add("origin", origin);

        JsonArray size = new JsonArray();
        size.add(x2 - x1);
        size.add(y2 - y1);
        size.add(z2 - z1);
        cube.add("size", size);

        if (elem.has("rotation")) {
            JsonObject rot = elem.getAsJsonObject("rotation");
            if (rot.has("angle") && rot.has("axis") && rot.has("origin")) {
                JsonArray ro = rot.getAsJsonArray("origin");
                JsonArray cubePivot = new JsonArray();
                cubePivot.add(ro.get(0).getAsDouble() - 8);
                cubePivot.add(ro.get(1).getAsDouble());
                cubePivot.add(ro.get(2).getAsDouble() - 8);
                cube.add("pivot", cubePivot);

                double angle = rot.get("angle").getAsDouble();
                String axis = rot.get("axis").getAsString();
                JsonArray rotation = new JsonArray();
                rotation.add("x".equals(axis) ? angle : 0);
                rotation.add("y".equals(axis) ? angle : 0);
                rotation.add("z".equals(axis) ? angle : 0);
                cube.add("rotation", rotation);
            }
        }

        if (elem.has("faces")) {
            JsonObject faces = elem.getAsJsonObject("faces");
            JsonObject bedrockUV = new JsonObject();
            for (var face : faces.entrySet()) {
                if (!face.getValue().isJsonObject()) continue;
                JsonObject jf = face.getValue().getAsJsonObject();
                if (!jf.has("uv")) continue;
                JsonArray uv = jf.getAsJsonArray("uv");
                if (uv.size() < 4) continue;

                JsonObject faceUV = new JsonObject();
                JsonArray uvOrigin = new JsonArray();
                uvOrigin.add(uv.get(0).getAsDouble());
                uvOrigin.add(uv.get(1).getAsDouble());
                faceUV.add("uv", uvOrigin);
                JsonArray uvSize = new JsonArray();
                uvSize.add(uv.get(2).getAsDouble() - uv.get(0).getAsDouble());
                uvSize.add(uv.get(3).getAsDouble() - uv.get(1).getAsDouble());
                faceUV.add("uv_size", uvSize);
                bedrockUV.add(face.getKey(), faceUV);
            }
            cube.add("uv", bedrockUV);
        }

        return cube;
    }

    private static String sanitize(String path) {
        return path.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }
}