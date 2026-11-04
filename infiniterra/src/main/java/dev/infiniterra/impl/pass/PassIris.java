package dev.infiniterra.impl.pass;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.infiniterra.InfiniTerraMod;
import dev.infiniterra.mixin.GameRendererAccessor;
import dev.infiniterra.mixin.iris.IrisRenderingPipelineAccessor;
import dev.infiniterra.mixin.iris.PipelineManagerAccessor;
import dev.infiniterra.mixin.voxy.RenderPipelineAccessor;
import dev.infiniterra.mixin.voxy.SectionRendererAccessor;
import dev.infiniterra.mixin.voxy.VoxyRenderSystemAccessor;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.helpers.MatrixUtils;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

/**
 * The camera pass through the shader pack (Iris): a second Iris pipeline of the same pack, with its own
 * buffers and history, renders a whole frame of the pack (gbuffers, deferred, composite, final) from the
 * pass camera into the camera pass's target, which stands in for the game's main target meanwhile. Voxy
 * draws the LOD into it through a second Voxy pipeline bound to that Iris pipeline's buffers (sharing the
 * main one's LOD data and geometry). There is no vanilla terrain and no shadow pass: the pack sees
 * a cleared shadow map. Render thread, before the main level frame.
 */
public final class PassIris {
	/** During the pass: the pack's camera uniforms follow the pass camera. */
	public static boolean active;
	/** The camera pass's pipelines, and the main Iris pipeline and render system they were made for. */
	private static IrisRenderingPipeline iris;
	private static IrisVoxyRenderPipeline voxy;
	private static WorldRenderingPipeline madeForIris;
	private static VoxyRenderSystem madeForVoxy;
	private static boolean unsupported;

	private PassIris() {
	}

	/** Whether Voxy renders through a shader pack (then the camera pass does too). */
	static boolean usesPack(AbstractRenderPipeline pipeline) {
		return pipeline instanceof IrisVoxyRenderPipeline;
	}

	public static Vector3d cameraPosition() {
		return new Vector3d(Passes.cameraPos);
	}

	/** Renders the camera pass through the pack; false if this pack cannot (no Voxy support, or no Iris pipeline). */
	static boolean render(VoxyRenderSystem rs) {
		Minecraft mc = Minecraft.getInstance();
		var rsa = (VoxyRenderSystemAccessor) rs;
		AbstractRenderPipeline mainVoxy = rsa.infiniterra$pipeline();
		WorldRenderingPipeline mainIris = Iris.getPipelineManager().getPipelineNullable();
		if (!(mainIris instanceof IrisRenderingPipeline)) return false;
		// a reloaded pack, another dimension or a new render system: new pipelines
		if (mainIris != madeForIris || rs != madeForVoxy) {
			release();
			madeForIris = mainIris;
			madeForVoxy = rs;
			unsupported = false;
		}
		if (unsupported) return false;

		RenderTarget mainTarget = mc.gameRenderer.mainRenderTarget();
		var gr = (GameRendererAccessor) mc.gameRenderer;
		var pm = (PipelineManagerAccessor) Iris.getPipelineManager();
		CapturedRenderingState state = CapturedRenderingState.INSTANCE;
		// (none before the level's first frame; it sets its own in every frame anyway)
		Matrix4f savedModelView = state.getGbufferModelView() == null ? null : new Matrix4f(state.getGbufferModelView());
		Matrix4f savedProjection = state.getGbufferProjection() == null ? null : new Matrix4f(state.getGbufferProjection());
		RenderProperties props = mainVoxy.properties;
		gr.infiniterra$setMainRenderTarget(Passes.target);
		try {
			if (iris == null && !create(rsa, mainVoxy)) return false;
			int colour = ((GlTexture) Passes.colour).glId(), depth = ((GlTexture) Passes.depth).glId();
			// no vanilla terrain: its depth is empty, the LOD fills everything
			GL44.glClearTexImage(colour, 0, GL11.GL_RGBA, GL11.GL_FLOAT, new float[]{0, 0, 0, 1});
			GL44.glClearTexImage(depth, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, new float[]{props.clearDepth()});
			var proj = Passes.passProjection(props.isReverseZ(), props.isZero2One());
			state.setGbufferModelView(Passes.modelView);
			state.setGbufferProjection(MatrixUtils.undoRevZ(new Matrix4f(proj)));
			pm.infiniterra$setPipeline(iris);
			active = true;
			// for the whole frame: the pack's Voxy uniforms (vxModelView, vxProj, ...) follow Voxy's camera pass viewport
			PassVoxy.inPass = true;

			iris.beginLevelRendering();
			iris.setPhase(WorldRenderingPhase.NONE);
			((IrisRenderingPipelineAccessor) iris).infiniterra$prepareRenderer().renderAll();
			rsa.infiniterra$setPipeline(voxy);
			// Voxy clears its stencil before it opens the stencil mask (in the level frame it is open)
			int stencilMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
			GL11.glStencilMask(0xFF);
			try {
				var p = Passes.cameraPos;
				var viewport = rs.setupViewport(proj, Passes.modelView, PassVoxy.NO_FOG, Passes.width, Passes.height, p.x, p.y, p.z);
				rs.renderOpaque(viewport, depth, colour);
			} finally {
				GL11.glStencilMask(stencilMask);
				rsa.infiniterra$setPipeline(mainVoxy);
			}
			iris.beginTranslucents();
			iris.finalizeLevelRendering();
			iris.finalizeGameRendering();
			darkenHoles(props, colour);
			return true;
		} finally {
			active = false;
			PassVoxy.inPass = false;
			pm.infiniterra$setPipeline(mainIris);
			gr.infiniterra$setMainRenderTarget(mainTarget);
			if (savedModelView != null) state.setGbufferModelView(savedModelView);
			if (savedProjection != null) state.setGbufferProjection(savedProjection);
		}
	}

	/** Builds the camera pass's Iris and Voxy pipelines (main target already swapped: Iris sizes its buffers by it). */
	private static boolean create(VoxyRenderSystemAccessor rs, AbstractRenderPipeline mainVoxy) {
		var pack = Iris.getCurrentPack().orElse(null);
		if (pack == null) return false;
		boolean reload = WorldRenderingSettings.INSTANCE.isReloadRequired();
		iris = new IrisRenderingPipeline(pack.getProgramSet(Iris.getCurrentDimension()));
		// the same pack sets the same settings; they need no chunk rebuild
		if (!reload) WorldRenderingSettings.INSTANCE.clearReloadRequired();
		((IrisRenderingPipelineAccessor) iris).infiniterra$setInitializedBlockIds(true);
		IrisVoxyRenderPipelineData data = ((IGetIrisVoxyPipelineData) iris).voxy$getPipelineData();
		if (data == null) {
			InfiniTerraMod.LOG.info("The shader pack has no Voxy support: no camera passes");
			unsupported = true;
			release();
			return false;
		}
		voxy = new IrisVoxyRenderPipeline(rs.infiniterra$properties(), data, rs.infiniterra$nodeManager(), rs.infiniterra$nodeCleaner(),
				rs.infiniterra$traversal(), () -> false);
		// its own section renderer: the pack's terrain shaders are compiled against this pipeline's uniform and
		// sampler layout (which Voxy orders differently for each Iris pipeline); LOD geometry and models are shared
		var main = (SectionRendererAccessor) ((RenderPipelineAccessor) mainVoxy).infiniterra$sectionRenderer();
		voxy.setSectionRenderer(new MDICSectionRenderer(voxy, main.infiniterra$modelStore(), (BasicSectionGeometryData) main.infiniterra$geometry()));
		InfiniTerraMod.LOG.info("Camera passes render through the shader pack");
		return true;
	}

	/** Painting where Voxy drew nothing: program, sampler, framebuffer and (empty) vertex array. */
	private static int holeProgram, holeSampler, holeFramebuffer, holeVertexArray;

	/**
	 * Where the layer cut leaves no terrain the pack draws its sky; the camera pass shows it dark, as without a
	 * shader pack (pixels where Voxy's depth is still clear).
	 */
	private static void darkenHoles(RenderProperties props, int colour) {
		if (holeProgram == 0) {
			holeProgram = GL20.glCreateProgram();
			int vs = shader(GL20.GL_VERTEX_SHADER, """
					#version 330 core
					void main() {
					    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
					    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
					}
					""");
			int fs = shader(GL20.GL_FRAGMENT_SHADER, """
					#version 330 core
					uniform sampler2D voxyDepth;
					uniform float clearDepth;
					uniform vec2 size;
					out vec4 colour;
					void main() {
					    if (texture(voxyDepth, gl_FragCoord.xy / size).r != clearDepth) discard;
					    colour = vec4(0.07, 0.07, 0.08, 1.0);
					}
					""");
			GL20.glAttachShader(holeProgram, vs);
			GL20.glAttachShader(holeProgram, fs);
			GL20.glLinkProgram(holeProgram);
			GL20.glDeleteShader(vs);
			GL20.glDeleteShader(fs);
			if (GL20.glGetProgrami(holeProgram, GL20.GL_LINK_STATUS) == 0)
				throw new IllegalStateException("camera pass hole shader: " + GL20.glGetProgramInfoLog(holeProgram));
			holeSampler = GL33.glGenSamplers();
			GL33.glSamplerParameteri(holeSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL33.glSamplerParameteri(holeSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			holeFramebuffer = GL45.glCreateFramebuffers();
			holeVertexArray = GL30.glGenVertexArrays();
		}
		GL45.glNamedFramebufferTexture(holeFramebuffer, GL30.GL_COLOR_ATTACHMENT0, colour, 0);
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, holeFramebuffer);
		GlStateManager._viewport(0, 0, Passes.width, Passes.height);
		GlStateManager._disableDepthTest();
		GlStateManager._disableBlend(0);
		GlStateManager._disableScissorTest();
		GlStateManager._disableCull();
		GlStateManager._colorMask(15);
		GL11.glDisable(GL11.GL_STENCIL_TEST);
		GlStateManager._glUseProgram(holeProgram);
		GL20.glUniform1i(GL20.glGetUniformLocation(holeProgram, "voxyDepth"), 0);
		GL20.glUniform1f(GL20.glGetUniformLocation(holeProgram, "clearDepth"), props.clearDepth());
		GL20.glUniform2f(GL20.glGetUniformLocation(holeProgram, "size"), Passes.width, Passes.height);
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		GlStateManager._bindTexture(voxy.fb.getDepthTex().id);
		GL33.glBindSampler(0, holeSampler);
		GlStateManager._glBindVertexArray(holeVertexArray);
		GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, 3);
		GlStateManager._glBindVertexArray(0);
		GL33.glBindSampler(0, 0);
		GlStateManager._bindTexture(0);
		GlStateManager._glUseProgram(0);
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
	}

	private static int shader(int type, String source) {
		int id = GL20.glCreateShader(type);
		GL20.glShaderSource(id, source);
		GL20.glCompileShader(id);
		if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException("camera pass hole shader: " + GL20.glGetShaderInfoLog(id));
		return id;
	}

	public static void release() {
		if (voxy != null) {
			try {
				voxy.free();
			} catch (RuntimeException e) {
				InfiniTerraMod.LOG.warn("Releasing the camera pass's Voxy pipeline", e);
			}
		}
		if (iris != null) iris.destroy();
		voxy = null;
		iris = null;
		if (holeProgram != 0) {
			GL20.glDeleteProgram(holeProgram);
			GL33.glDeleteSamplers(holeSampler);
			GL30.glDeleteFramebuffers(holeFramebuffer);
			GL30.glDeleteVertexArrays(holeVertexArray);
			holeProgram = holeSampler = holeFramebuffer = holeVertexArray = 0;
		}
	}
}
