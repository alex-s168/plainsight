package dev.infiniterra.mixin.voxy;

import dev.infiniterra.InfiniTerraMod;
import dev.infiniterra.impl.CameraState;
import dev.infiniterra.impl.pass.PassVoxy;
import dev.infiniterra.impl.pass.Passes;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostics for #16: Voxy's "Tried processing request for pos ... but its type was a request" (a node
 * requested again while its first request is pending). When it happens, what InfiniTerra was doing goes
 * into the log (at most once a second): a camera pass running (a second traversal on the same node
 * manager), which kind, the detached camera and its distance to the player, guessed terrain shown.
 */
@Mixin(value = NodeManager.class, remap = false)
public abstract class NodeRequestDiagnosticsMixin {
	@Shadow
	@Final
	private Long2IntOpenHashMap activeSectionMap;

	private static long infiniterra$lastReport;
	private static int infiniterra$since;

	@Inject(method = "processRequest", at = @At("HEAD"))
	private void infiniterra$diagnose(long pos, CallbackInfo ci) {
		int nodeId = activeSectionMap.get(pos);
		if (nodeId == -1 || (nodeId & -1073741824) != Integer.MIN_VALUE) return;
		infiniterra$since++;
		long now = System.currentTimeMillis();
		if (now - infiniterra$lastReport < 1000) return;
		infiniterra$lastReport = now;
		InfiniTerraMod.LOG.warn("Voxy node requested again while pending ({} since the last report): in camera pass {}, pass wanted {}"
						+ " (frame projection {}), detached camera {} at {} blocks from the player, guessed terrain shown {}",
				infiniterra$since, PassVoxy.inPass, Passes.wanted(), Passes.frameProjection, CameraState.active,
				Math.round(CameraState.toPlayer), dev.infiniterra.impl.guess.VoxyGuess.showing());
		infiniterra$since = 0;
	}
}
