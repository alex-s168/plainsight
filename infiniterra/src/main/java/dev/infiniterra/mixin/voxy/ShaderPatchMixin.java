package dev.infiniterra.mixin.voxy;

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
 * {@link #patchTerrain} for the camera pass's layer cut.
 */
@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader$ShaderLoadingParser", remap = false)
public abstract class ShaderPatchMixin {
	@Inject(method = "loadShaderAsset", at = @At("RETURN"), cancellable = true)
	private static void infiniterra$patch(Identifier id, CallbackInfoReturnable<String> cir) {
		if (!"voxy".equals(id.getNamespace())) return;
		if (id.getPath().endsWith("gl46/cull/raster.vert")) {
			patchRaster(id, cir);
			return;
		}
		if (id.getPath().endsWith("gl46/quads3.vert")) {
			patchTerrain(id, cir);
			return;
		}
		if (id.getPath().endsWith("gl46/quads.frag")) {
			patchGuessMask(id, cir);
			return;
		}
		if (id.getPath().endsWith("lod/quad_util.glsl")) {
			patchLighting(id, cir);
			return;
		}
		if (!id.getPath().endsWith("hierarchical/screenspace.glsl")) return;
		String src = cir.getReturnValue();
		String patched = src
				.replace("bool _frustumCulled = false;", "bool _frustumCulled = false;\nbool _infiniterraNear = false;")
				.replace("vec4 P111 = Axis[1] + P101;", "vec4 P111 = Axis[1] + P101;\n    _infiniterraNear = min(min(min(P000.w, P100.w), min(P001.w, P101.w)),"
						+ " min(min(P010.w, P110.w), min(P011.w, P111.w))) < 1.0;")
				.replace("_screenSize = ssize;", "_screenSize = _infiniterraNear ? 1e30 : ssize;")
				.replace("bool isCulledByHiz() {", "bool isCulledByHiz() {\n    if (_infiniterraNear) return false;");
		if (patched.equals(src) || !patched.contains("if (_infiniterraNear) return false;") || !patched.contains("_infiniterraNear = min(")) {
			dev.infiniterra.InfiniTerraMod.LOG.warn("Could not patch Voxy's {} (changed?): near-camera LOD may be culled", id);
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
			dev.infiniterra.InfiniTerraMod.LOG.warn("Could not patch Voxy's {} (changed?): LOD right at the camera may vanish", id);
			return;
		}
		int lineEnd = src.indexOf('\n', at);
		String patch = "\n    {\n"
				+ "        vec3 infiniterraMin = vec3(pos) + (vec3(aabbOffset) - EXPANSION) * float(1 << detail);\n"
				+ "        vec3 infiniterraMax = infiniterraMin + (vec3(size) + 2.0 * EXPANSION) * float(1 << detail);\n"
				+ "        if (all(greaterThanEqual(cameraSubPos, infiniterraMin - 4.0)) && all(lessThanEqual(cameraSubPos, infiniterraMax + 4.0))) {\n"
				+ "            visibilityData[sid] = value;\n"
				+ "        }\n"
				+ "    }\n";
		cir.setReturnValue(src.substring(0, lineEnd) + patch + src.substring(lineEnd));
	}

	/**
	 * The camera pass shows the layer the player is on: LOD terrain above a height is clipped off. Each corner's
	 * distance below the cut goes into a clip distance, which only the camera pass enables (and sets the
	 * cut for, {@link dev.infiniterra.mixin.voxy.PassTerrainCutMixin}); elsewhere it is ignored.
	 */
	private static final String CUT_UNIFORM = "#ifndef INFINITERRA_CUT\n#define INFINITERRA_CUT\nuniform float infiniterraCutY = 1e30;\n#endif\n";

	/** Fluid opacity of {@link dev.infiniterra.api.TerrainStyle}, set before every LOD draw ({@link PassTerrainCutMixin}). */
	private static final String FLUID_UNIFORM = "#ifndef INFINITERRA_FLUID\n#define INFINITERRA_FLUID\nuniform float infiniterraFluidAlpha = 1.0;\n#endif\n";

	private static boolean vertexMaskPatched;

	private static void patchTerrain(Identifier id, CallbackInfoReturnable<String> cir) {
		String src = cir.getReturnValue();
		String main = "\nvoid main() {", anchor = "interData = quad.attributeData;";
		if (!src.contains(main) || !src.contains(anchor)) {
			dev.infiniterra.InfiniTerraMod.LOG.warn("Could not patch Voxy's {} (changed?): the camera pass shows all layers", id);
			return;
		}
		vertexMaskPatched = true;
		cir.setReturnValue(src
				// NVIDIA's half precision path redeclares the vertex outputs
				.replace("f16vec4 gl_Position;", "f16vec4 gl_Position;\n    float gl_ClipDistance[1];")
				.replace(main, "\n" + CUT_UNIFORM + FLUID_UNIFORM + main)
				.replace(main, "\nlayout(location = 6) out vec2 infiniterraMaskXZ;\nuniform ivec2 infiniterraMaskOrigin;\n" + main)
				.replace(anchor, anchor + "\n    {\n"
						+ "        vec2 infiniterraCorner = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;\n"
						+ "        vec3 infiniterraP = quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin*infiniterraCorner, 0));\n"
						+ "        infiniterraMaskXZ = vec2((baseSectionPos.xz<<5) - infiniterraMaskOrigin) + infiniterraP.xz;\n"
						+ "    }")
				// fluid opacity 0: fluid quads collapse to a point (nothing is drawn, not even depth)
				.replace(anchor, anchor + "\n    if (infiniterraFluidAlpha <= 0.0 && (modelData[extractStateId(quadData[uint(gl_VertexID)>>2])].flagsA & "
						+ dev.infiniterra.impl.style.VoxyStyle.FLUID_FLAG + "u) != 0u) gl_Position = vec4(0.0);")
				.replace(anchor, anchor + "\n    {\n"
						+ "        vec2 infiniterraMask = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;\n"
						+ "        vec3 infiniterraPoint = quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin*infiniterraMask, 0));\n"
						+ "        gl_ClipDistance[0] = infiniterraCutY - (infiniterraPoint.y + float(baseSectionPos.y<<5));\n"
						+ "    }"));
	}

	/**
	 * Voxy renders the guessed LOD world in game too (it holds a copy of the real LOD), so switching to the
	 * editor needs no new renderer; outside the editor the guessed chunks are hidden here, by a mask of
	 * them ({@link dev.infiniterra.impl.guess.VoxyGuessMask}, one texel per chunk) and the position the vertex
	 * shader passes on.
	 */
	private static void patchGuessMask(Identifier id, CallbackInfoReturnable<String> cir) {
		String src = cir.getReturnValue(), main = "\nvoid main() {", anchor = "    //Check the minimum bounding texture";
		// the vertex shader must pass the position on (it is loaded first), or the program would not link
		if (!vertexMaskPatched || !src.contains(main) || !src.contains(anchor)) {
			dev.infiniterra.InfiniTerraMod.LOG.warn("Could not patch Voxy's {} (changed?): switching to guessed terrain rebuilds Voxy's view", id);
			return;
		}
		dev.infiniterra.impl.guess.VoxyGuessMask.patched = true;
		cir.setReturnValue(src
				.replace(main, "\nlayout(location = 6) in vec2 infiniterraMaskXZ;\n"
						+ "layout(binding = " + dev.infiniterra.impl.guess.VoxyGuessMask.UNIT + ") uniform sampler2D infiniterraGuessMask;\n"
						+ "uniform int infiniterraHideGuessed = 0;\nuniform int infiniterraMaskSize = 0;\n" + main)
				.replace(anchor, "    if (infiniterraHideGuessed != 0) {\n"
						+ "        ivec2 infiniterraCell = ivec2(floor(infiniterraMaskXZ / 16.0));\n"
						+ "        if (any(lessThan(infiniterraCell, ivec2(0))) || any(greaterThanEqual(infiniterraCell, ivec2(infiniterraMaskSize)))\n"
						+ "                || texelFetch(infiniterraGuessMask, infiniterraCell, 0).r > 0.5) {\n"
						+ "            discard;\n"
						+ "            return;\n"
						+ "        }\n"
						+ "    }\n" + anchor));
	}

	/**
	 * Voxy shades the LOD by its stored light: under a roof and in caves the sky light is 0, so the layers
	 * the camera pass shows there would be black. In the camera pass (cut height set) everything gets full sky light.
	 */
	private static void patchLighting(Identifier id, CallbackInfoReturnable<String> cir) {
		String src = cir.getReturnValue(), anchor = "uint lighting = extractLightId(quad);";
		if (!src.contains(anchor)) {
			dev.infiniterra.InfiniTerraMod.LOG.warn("Could not patch Voxy's {} (changed?): caves are dark on the camera pass", id);
			return;
		}
		src = src.replace(anchor, anchor + "\n    if (infiniterraCutY < 1e29) lighting |= 0xFu;");
		// translucent fluids (water): their alpha times the fluid opacity (the lighting colour's alpha is the
		// quad's alpha in the translucent pass; opaque quads use it for other data and are left alone)
		String alpha = "tinting.rgb *= computeDirectionalFaceTint(isShaded, face);";
		dev.infiniterra.impl.style.VoxyStyle.fluidAlphaPatched = src.contains(alpha);
		if (src.contains(alpha))
			src = src.replace(alpha, alpha + "\n    if (isTranslucent && (model.flagsA & " + dev.infiniterra.impl.style.VoxyStyle.FLUID_FLAG + "u) != 0u) tinting.w *= infiniterraFluidAlpha;");
		else
			dev.infiniterra.InfiniTerraMod.LOG.warn("Could not patch Voxy's {} (changed?): water in the LOD keeps its opacity", id);
		cir.setReturnValue(CUT_UNIFORM + FLUID_UNIFORM + src);
	}
}
