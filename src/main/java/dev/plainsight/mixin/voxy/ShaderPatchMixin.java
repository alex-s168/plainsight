package dev.plainsight.mixin.voxy;

import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Patches Voxy's LOD culling shaders for a camera right at LOD terrain (the editor camera can be at
 * distant or guessed terrain; normally vanilla chunks are drawn there). A node with a corner at or behind
 * the camera plane projects to a meaningless screen box: it is never occlusion-culled and always counts as
 * large on screen (so it is refined). See {@link #patchRaster} for the per-section visibility pass, and
 * {@link #patchTerrain} for the minimap's layer cut.
 */
@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader$ShaderLoadingParser", remap = false)
public abstract class ShaderPatchMixin {
	@Inject(method = "loadShaderAsset", at = @At("RETURN"), cancellable = true)
	private static void plainsight$patch(Identifier id, CallbackInfoReturnable<String> cir) {
		if (!"voxy".equals(id.getNamespace())) return;
		if (id.getPath().endsWith("gl46/cull/raster.vert")) {
			patchRaster(id, cir);
			return;
		}
		if (id.getPath().endsWith("gl46/quads3.vert")) {
			patchTerrain(id, cir);
			return;
		}
		if (id.getPath().endsWith("lod/quad_util.glsl")) {
			patchLighting(id, cir);
			return;
		}
		if (!id.getPath().endsWith("hierarchical/screenspace.glsl")) return;
		String src = cir.getReturnValue();
		String patched = src
				.replace("bool _frustumCulled = false;", "bool _frustumCulled = false;\nbool _plainsightNear = false;")
				.replace("vec4 P111 = Axis[1] + P101;", "vec4 P111 = Axis[1] + P101;\n    _plainsightNear = min(min(min(P000.w, P100.w), min(P001.w, P101.w)),"
						+ " min(min(P010.w, P110.w), min(P011.w, P111.w))) < 1.0;")
				.replace("_screenSize = ssize;", "_screenSize = _plainsightNear ? 1e30 : ssize;")
				.replace("bool isCulledByHiz() {", "bool isCulledByHiz() {\n    if (_plainsightNear) return false;");
		if (patched.equals(src) || !patched.contains("if (_plainsightNear) return false;") || !patched.contains("_plainsightNear = min(")) {
			dev.plainsight.Plainsight.LOG.warn("Could not patch Voxy's {} (changed?): near-camera LOD may be culled", id);
			return;
		}
		cir.setReturnValue(patched);
	}

	/**
	 * Sections count as visible when their (slightly grown) bounding box rasterizes; from inside the box
	 * only its back faces face the camera (and close to it the near plane cuts the box), so sections around
	 * a camera that is right at the terrain were dropped. Sections within 4 blocks of the camera are marked
	 * visible directly.
	 */
	private static void patchRaster(Identifier id, CallbackInfoReturnable<String> cir) {
		String src = cir.getReturnValue();
		String anchor = "value = (frameId&0x7fffffffu)|(uint(wasVisibleLastFrame)<<31);";
		int at = src.indexOf(anchor);
		if (at < 0) {
			dev.plainsight.Plainsight.LOG.warn("Could not patch Voxy's {} (changed?): LOD right at the camera may vanish", id);
			return;
		}
		int lineEnd = src.indexOf('\n', at);
		String patch = "\n    {\n"
				+ "        vec3 plainsightMin = vec3(pos) + (vec3(aabbOffset) - EXPANSION) * float(1 << detail);\n"
				+ "        vec3 plainsightMax = plainsightMin + (vec3(size) + 2.0 * EXPANSION) * float(1 << detail);\n"
				+ "        if (all(greaterThanEqual(cameraSubPos, plainsightMin - 4.0)) && all(lessThanEqual(cameraSubPos, plainsightMax + 4.0))) {\n"
				+ "            visibilityData[sid] = value;\n"
				+ "        }\n"
				+ "    }\n";
		cir.setReturnValue(src.substring(0, lineEnd) + patch + src.substring(lineEnd));
	}

	/**
	 * The minimap shows the layer the player is on: LOD terrain above a height is clipped off. Each corner's
	 * distance below the cut goes into a clip distance, which only the minimap pass enables (and sets the
	 * cut for, {@link dev.plainsight.mixin.voxy.MinimapTerrainCutMixin}); elsewhere it is ignored.
	 */
	private static final String CUT_UNIFORM = "#ifndef PLAINSIGHT_CUT\n#define PLAINSIGHT_CUT\nuniform float plainsightCutY = 1e30;\n#endif\n";

	private static void patchTerrain(Identifier id, CallbackInfoReturnable<String> cir) {
		String src = cir.getReturnValue();
		String main = "\nvoid main() {", anchor = "interData = quad.attributeData;";
		if (!src.contains(main) || !src.contains(anchor)) {
			dev.plainsight.Plainsight.LOG.warn("Could not patch Voxy's {} (changed?): the minimap shows all layers", id);
			return;
		}
		cir.setReturnValue(src
				// NVIDIA's half precision path redeclares the vertex outputs
				.replace("f16vec4 gl_Position;", "f16vec4 gl_Position;\n    float gl_ClipDistance[1];")
				.replace(main, "\n" + CUT_UNIFORM + main)
				.replace(anchor, anchor + "\n    {\n"
						+ "        vec2 plainsightMask = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;\n"
						+ "        vec3 plainsightPoint = quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin*plainsightMask, 0));\n"
						+ "        gl_ClipDistance[0] = plainsightCutY - (plainsightPoint.y + float(baseSectionPos.y<<5));\n"
						+ "    }"));
	}

	/**
	 * Voxy shades the LOD by its stored light: under a roof and in caves the sky light is 0, so the layers
	 * the minimap shows there would be black. In the minimap pass (cut height set) everything gets full sky light.
	 */
	private static void patchLighting(Identifier id, CallbackInfoReturnable<String> cir) {
		String src = cir.getReturnValue(), anchor = "uint lighting = extractLightId(quad);";
		if (!src.contains(anchor)) {
			dev.plainsight.Plainsight.LOG.warn("Could not patch Voxy's {} (changed?): caves are dark on the minimap", id);
			return;
		}
		cir.setReturnValue(CUT_UNIFORM + src.replace(anchor, anchor + "\n    if (plainsightCutY < 1e29) lighting |= 0xFu;"));
	}
}
