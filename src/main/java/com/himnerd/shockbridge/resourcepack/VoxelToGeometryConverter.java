package com.himnerd.shockbridge.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Mathematical conversion engine that transforms Java Edition item/block model
 * element cubes into fully-formed Bedrock Edition {@code .geo.json} geometry schemas.
 * <p>
 * <b>Coordinate Transformation Pipeline:</b>
 * <ol>
 *   <li>Extract raw Java pixel coordinates ({@code from}/{@code to}, 0–16 range)</li>
 *   <li>Multiply all spatial dimensions by {@link #PIXEL_SCALE} (0.0625) to convert
 *       from pixel units into Bedrock's coordinate engine</li>
 *   <li>Apply center offset ({@link #CENTER_OFFSET_X}, {@link #CENTER_OFFSET_Z}) to
 *       re-anchor the model at the bone origin (0,0,0)</li>
 *   <li>Recompute rotation pivots as the delta between Java's absolute element pivot
 *       and Bedrock's bone-relative parent offset — prevents floating / distortion
 *       in first-person, third-person, and GUI rendering contexts</li>
 *   <li>Convert per-face UV from Java's {@code [u1,v1,u2,v2]} to Bedrock's
 *       {@code {uv:[u,v], uv_size:[w,h]}} format with mirroring support</li>
 * </ol>
 *
 * @author HimnerdMC
 */
public class VoxelToGeometryConverter {

    /**
     * Pixel-to-unit scaling factor. Java model coordinates are specified in pixel units
     * (0–16 range for a standard block). Bedrock's geometry coordinate engine requires
     * division by 16 to translate pixel dimensions into spatial units correctly.
     */
    public static final double PIXEL_SCALE = 0.0625;

    /**
     * Half the standard Java model width in scaled units.
     * Centers the model symmetrically around the bone origin on the X axis.
     * Derivation: 8 pixels × 0.0625 = 0.5 units.
     */
    public static final double CENTER_OFFSET_X = 0.5;

    /**
     * Half the standard Java model depth in scaled units.
     * Centers the model symmetrically around the bone origin on the Z axis.
     */
    public static final double CENTER_OFFSET_Z = 0.5;

    /** Maximum parent/element recursion depth to prevent infinite loops. */
    private static final int MAX_DISPLAY_CONTEXTS = 8;

    // ─── Immutable vector record for 3D math ──────────────────────────────

    public record Vector3(double x, double y, double z) {
        public Vector3 scale(double s) { return new Vector3(x * s, y * s, z * s); }
        public Vector3 subtract(Vector3 o) { return new Vector3(x - o.x, y - o.y, z - o.z); }
        public Vector3 add(Vector3 o) { return new Vector3(x + o.x, y + o.y, z + o.z); }
        public double length() { return Math.sqrt(x * x + y * y + z * z); }
    }

    /** Immutable result of a single geometry conversion pass. */
    public record ConversionResult(JsonObject geoJson, String geometryId, double[] boundingBox) {}

    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Converts a parsed Java model JSON into a complete Bedrock {@code .geo.json}.
     *
     * @param geometryId    unique Bedrock geometry identifier (e.g., "geometry.shockbridge.laser_rifle")
     * @param javaModel     the parsed Java model JSON with {@code "elements"} array
     * @param textureWidth  texture atlas width in pixels (default: 16)
     * @param textureHeight texture atlas height in pixels (default: 16)
     * @return conversion result with the full Bedrock geometry JSON and bounding box
     */
    public ConversionResult convert(String geometryId, JsonObject javaModel,
                                    int textureWidth, int textureHeight) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.16.0");

        JsonArray geometries = new JsonArray();
        JsonObject geo = new JsonObject();

        // ── Description block ─────────────────────────────────────────────
        JsonObject desc = new JsonObject();
        desc.addProperty("identifier", geometryId);
        desc.addProperty("texture_width", textureWidth);
        desc.addProperty("texture_height", textureHeight);

        double[] bbox = computeBoundingBox(javaModel);
        desc.addProperty("visible_bounds_width",
                roundPrecision(Math.max(bbox[3] - bbox[0], PIXEL_SCALE)));
        desc.addProperty("visible_bounds_height",
                roundPrecision(Math.max(bbox[4] - bbox[1], PIXEL_SCALE)));

        JsonArray visOffset = new JsonArray();
        visOffset.add(roundPrecision((bbox[0] + bbox[3]) / 2.0));
        visOffset.add(roundPrecision((bbox[1] + bbox[4]) / 2.0));
        visOffset.add(roundPrecision((bbox[2] + bbox[5]) / 2.0));
        desc.add("visible_bounds_offset", visOffset);

        geo.add("description", desc);

        // ── Bone assembly ─────────────────────────────────────────────────
        JsonArray bones = new JsonArray();
        bones.add(buildRootBone(javaModel));

        if (javaModel.has("display") && javaModel.get("display").isJsonObject()) {
            buildDisplayBones(javaModel.getAsJsonObject("display"), bones);
        }

        geo.add("bones", bones);
        geometries.add(geo);
        root.add("minecraft:geometry", geometries);

        return new ConversionResult(root, geometryId, bbox);
    }

    /**
     * Synthesizes a flat-quad element for Java "generated" item models (e.g.,
     * {@code minecraft:item/generated}). These models have no explicit {@code elements}
     * array — Java auto-generates a 16×16×1 pixel quad from layer textures.
     *
     * @return a synthetic Java element JSON suitable for {@link #transformElement}
     */
    public JsonObject synthesizeGeneratedItemElement() {
        JsonObject element = new JsonObject();

        JsonArray from = new JsonArray();
        from.add(0.0); from.add(0.0); from.add(7.5);
        element.add("from", from);

        JsonArray to = new JsonArray();
        to.add(16.0); to.add(16.0); to.add(8.5);
        element.add("to", to);

        JsonObject faces = new JsonObject();
        for (String face : new String[]{"north", "south"}) {
            JsonObject faceObj = new JsonObject();
            JsonArray uv = new JsonArray();
            uv.add(0); uv.add(0); uv.add(16); uv.add(16);
            faceObj.add("uv", uv);
            faceObj.addProperty("texture", "#layer0");
            faces.add(face, faceObj);
        }
        element.add("faces", faces);
        return element;
    }

    // ─── Bone construction ────────────────────────────────────────────────

    private JsonObject buildRootBone(JsonObject javaModel) {
        JsonObject bone = new JsonObject();
        bone.addProperty("name", "root");
        bone.add("pivot", vec3ToArray(new Vector3(0, 0, 0)));

        JsonArray cubes = new JsonArray();
        if (javaModel.has("elements") && javaModel.get("elements").isJsonArray()) {
            for (JsonElement elem : javaModel.getAsJsonArray("elements")) {
                if (elem instanceof JsonObject elemObj) {
                    JsonObject cube = transformElement(elemObj);
                    if (cube != null) cubes.add(cube);
                }
            }
        }
        bone.add("cubes", cubes);
        return bone;
    }

    /**
     * Transforms a single Java model element into a Bedrock geometry cube.
     * <p>
     * <b>Matrix Transformation Pipeline:</b>
     * <pre>
     *   1. javaFrom/To    → raw pixel coords (e.g., [2,0,7] to [14,12,9])
     *   2. × PIXEL_SCALE  → scaled coords     (e.g., [0.125,0,0.4375] to [0.875,0.75,0.5625])
     *   3. − centerOffset  → centered origin    (e.g., [-0.375,0,-0.0625])
     *   4. size = Δ(to−from)                    (e.g., [0.75,0.75,0.125])
     *   5. pivot: (javaOrigin × scale) − center (parent-relative delta)
     *   6. rotation: axis→euler vector           (e.g., "y":45 → [0,45,0])
     * </pre>
     */
    public JsonObject transformElement(JsonObject element) {
        if (!element.has("from") || !element.has("to")) return null;
        JsonArray fromArr = element.getAsJsonArray("from");
        JsonArray toArr = element.getAsJsonArray("to");
        if (fromArr.size() < 3 || toArr.size() < 3) return null;

        // Step 1: Extract raw Java pixel coordinates
        Vector3 javaFrom = extractVec3(fromArr);
        Vector3 javaTo = extractVec3(toArr);

        // Step 2: Scale from pixel units to Bedrock spatial units
        Vector3 scaledFrom = javaFrom.scale(PIXEL_SCALE);
        Vector3 scaledTo = javaTo.scale(PIXEL_SCALE);

        // Step 3: Apply center offset to anchor model at bone origin
        Vector3 centerOffset = new Vector3(CENTER_OFFSET_X, 0.0, CENTER_OFFSET_Z);
        Vector3 origin = scaledFrom.subtract(centerOffset);
        Vector3 size = scaledTo.subtract(scaledFrom);

        JsonObject cube = new JsonObject();
        cube.add("origin", vec3ToArray(origin));
        cube.add("size", vec3ToArray(size));

        // Steps 4–5: Rotation pivot translation (Java absolute → Bedrock bone-relative)
        if (element.has("rotation") && element.get("rotation").isJsonObject()) {
            applyRotationTransform(cube, element.getAsJsonObject("rotation"), centerOffset);
        }

        // Step 6: Per-face UV remapping
        if (element.has("faces") && element.get("faces").isJsonObject()) {
            cube.add("uv", convertPerFaceUV(element.getAsJsonObject("faces")));
        }

        return cube;
    }

    // ─── Rotation & pivot math ────────────────────────────────────────────

    /**
     * Computes the Bedrock rotation vector and parent-relative pivot offset from
     * a Java rotation block. The critical operation is the <b>pivot delta</b> —
     * Java specifies rotation origins in absolute model space, but Bedrock expects
     * them relative to the parent bone's pivot. The difference prevents models
     * from floating or distorting when rendered in first-person and third-person.
     */
    private void applyRotationTransform(JsonObject cube, JsonObject javaRotation,
                                        Vector3 centerOffset) {
        if (!javaRotation.has("angle") || !javaRotation.has("axis")) return;
        double angle = javaRotation.get("angle").getAsDouble();
        String axis = javaRotation.get("axis").getAsString();

        // Map single-axis Java rotation to Bedrock euler vector
        double[] euler = switch (axis) {
            case "x" -> new double[]{angle, 0, 0};
            case "y" -> new double[]{0, angle, 0};
            case "z" -> new double[]{0, 0, angle};
            default  -> new double[]{0, 0, 0};
        };
        JsonArray rotArr = new JsonArray();
        rotArr.add(euler[0]); rotArr.add(euler[1]); rotArr.add(euler[2]);
        cube.add("rotation", rotArr);

        // Pivot translation: Java absolute origin → Bedrock bone-relative offset
        if (javaRotation.has("origin") && javaRotation.get("origin").isJsonArray()) {
            Vector3 javaPivot = extractVec3(javaRotation.getAsJsonArray("origin"));
            Vector3 scaledPivot = javaPivot.scale(PIXEL_SCALE);
            Vector3 bedrockPivot = scaledPivot.subtract(centerOffset);
            cube.add("pivot", vec3ToArray(bedrockPivot));
        }

        // Rescale compensation (Java-only flag; approximated via inflation in Bedrock)
        if (javaRotation.has("rescale") && javaRotation.get("rescale").getAsBoolean()) {
            cube.addProperty("inflate", 0.01);
        }
    }

    // ─── UV conversion ────────────────────────────────────────────────────

    /**
     * Converts Java per-face UV definitions {@code [u1,v1,u2,v2]} into Bedrock's
     * per-face format {@code {uv:[u,v], uv_size:[w,h]}}. Handles UV mirroring
     * (negative size) and rotation compensation.
     */
    private JsonObject convertPerFaceUV(JsonObject javaFaces) {
        JsonObject bedrockUV = new JsonObject();
        String[] faceNames = {"north", "south", "east", "west", "up", "down"};

        for (String face : faceNames) {
            if (!javaFaces.has(face)) continue;
            JsonElement faceElem = javaFaces.get(face);
            if (!(faceElem instanceof JsonObject faceObj)) continue;
            if (!faceObj.has("uv")) continue;

            JsonArray javaUV = faceObj.getAsJsonArray("uv");
            if (javaUV.size() < 4) continue;

            double u1 = javaUV.get(0).getAsDouble();
            double v1 = javaUV.get(1).getAsDouble();
            double u2 = javaUV.get(2).getAsDouble();
            double v2 = javaUV.get(3).getAsDouble();

            JsonObject bedrockFace = new JsonObject();
            JsonArray uvOrigin = new JsonArray();
            uvOrigin.add(Math.min(u1, u2));
            uvOrigin.add(Math.min(v1, v2));
            bedrockFace.add("uv", uvOrigin);

            // Size preserves sign for mirrored UVs
            JsonArray uvSize = new JsonArray();
            uvSize.add(u2 - u1);
            uvSize.add(v2 - v1);
            bedrockFace.add("uv_size", uvSize);

            // Face rotation compensation (Java supports 0/90/180/270)
            if (faceObj.has("rotation")) {
                int rot = faceObj.get("rotation").getAsInt();
                if (rot == 90 || rot == 270) {
                    JsonArray swapped = new JsonArray();
                    swapped.add(v2 - v1);
                    swapped.add(u2 - u1);
                    bedrockFace.add("uv_size", swapped);
                }
            }

            bedrockUV.add(face, bedrockFace);
        }
        return bedrockUV;
    }

    // ─── Display transform bones ──────────────────────────────────────────

    /**
     * Builds child bones for each Java display transform context (thirdperson,
     * firstperson, gui, ground, fixed, head). Each bone inherits from the root
     * and carries the translation, rotation, and scale as Bedrock bone properties.
     */
    private void buildDisplayBones(JsonObject display, JsonArray bones) {
        String[] contexts = {
            "thirdperson_righthand", "thirdperson_lefthand",
            "firstperson_righthand", "firstperson_lefthand",
            "gui", "ground", "fixed", "head"
        };

        int built = 0;
        for (String ctx : contexts) {
            if (!display.has(ctx) || built >= MAX_DISPLAY_CONTEXTS) continue;
            if (!(display.get(ctx) instanceof JsonObject transform)) continue;

            JsonObject bone = new JsonObject();
            bone.addProperty("name", "display_" + ctx);
            bone.addProperty("parent", "root");

            // Translation → bone pivot offset (scaled from pixel to unit space)
            if (transform.has("translation") && transform.get("translation").isJsonArray()) {
                JsonArray t = transform.getAsJsonArray("translation");
                JsonArray pivot = new JsonArray();
                pivot.add(roundPrecision(safeDouble(t, 0) * PIXEL_SCALE));
                pivot.add(roundPrecision(safeDouble(t, 1) * PIXEL_SCALE));
                pivot.add(roundPrecision(safeDouble(t, 2) * PIXEL_SCALE));
                bone.add("pivot", pivot);
            } else {
                bone.add("pivot", vec3ToArray(new Vector3(0, 0, 0)));
            }

            // Rotation → bone rotation (direct mapping)
            if (transform.has("rotation") && transform.get("rotation").isJsonArray()) {
                JsonArray r = transform.getAsJsonArray("rotation");
                JsonArray rot = new JsonArray();
                rot.add(safeDouble(r, 0));
                rot.add(safeDouble(r, 1));
                rot.add(safeDouble(r, 2));
                bone.add("rotation", rot);
            }

            // Scale → bone scale (Bedrock 1.16+ supports per-bone scaling)
            if (transform.has("scale") && transform.get("scale").isJsonArray()) {
                JsonArray s = transform.getAsJsonArray("scale");
                JsonArray scale = new JsonArray();
                scale.add(safeDouble(s, 0));
                scale.add(safeDouble(s, 1));
                scale.add(safeDouble(s, 2));
                bone.add("scale", scale);
            }

            bones.add(bone);
            built++;
        }
    }

    // ─── Bounding box computation ─────────────────────────────────────────

    /**
     * Computes the axis-aligned bounding box of all elements in scaled Bedrock units.
     *
     * @return {@code [minX, minY, minZ, maxX, maxY, maxZ]}
     */
    private double[] computeBoundingBox(JsonObject model) {
        double[] bbox = {
            Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
            -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE
        };
        boolean hasElements = false;

        if (model.has("elements") && model.get("elements").isJsonArray()) {
            for (JsonElement elem : model.getAsJsonArray("elements")) {
                if (!(elem instanceof JsonObject obj)) continue;
                if (!obj.has("from") || !obj.has("to")) continue;

                JsonArray from = obj.getAsJsonArray("from");
                JsonArray to = obj.getAsJsonArray("to");
                if (from.size() < 3 || to.size() < 3) continue;

                hasElements = true;
                for (int i = 0; i < 3; i++) {
                    double f = from.get(i).getAsDouble() * PIXEL_SCALE;
                    double t = to.get(i).getAsDouble() * PIXEL_SCALE;
                    double offset = (i == 0) ? CENTER_OFFSET_X : (i == 2) ? CENTER_OFFSET_Z : 0;
                    bbox[i] = Math.min(bbox[i], Math.min(f, t) - offset);
                    bbox[i + 3] = Math.max(bbox[i + 3], Math.max(f, t) - offset);
                }
            }
        }

        return hasElements ? bbox : new double[]{-0.5, 0, -0.5, 0.5, 1.0, 0.5};
    }

    // ─── Utility ──────────────────────────────────────────────────────────

    private static Vector3 extractVec3(JsonArray arr) {
        return new Vector3(
                arr.get(0).getAsDouble(),
                arr.get(1).getAsDouble(),
                arr.get(2).getAsDouble()
        );
    }

    private static JsonArray vec3ToArray(Vector3 v) {
        JsonArray arr = new JsonArray();
        arr.add(roundPrecision(v.x()));
        arr.add(roundPrecision(v.y()));
        arr.add(roundPrecision(v.z()));
        return arr;
    }

    private static double safeDouble(JsonArray arr, int idx) {
        return (arr.size() > idx) ? arr.get(idx).getAsDouble() : 0.0;
    }

    /**
     * Rounds to 6 decimal places to prevent floating-point noise
     * in the generated geometry JSON output.
     */
    private static double roundPrecision(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}