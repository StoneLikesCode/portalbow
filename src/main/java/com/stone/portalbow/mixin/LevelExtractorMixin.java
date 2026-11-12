package com.stone.portalbow.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.stone.portalbow.client.TrueViewCamera;
import com.stone.portalbow.client.TrueViews;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adjusts how the game works out which chunks are visible while a portal view is being drawn.
 *
 * <p>The plain game keeps a single, slowly updated map of "what can be seen from where the
 * player is" and a grid of chunk meshes centred on the player. Moving the camera to the far
 * side of a portal and back every frame would make it throw both away over and over. So during
 * a portal view we leave both alone and simply use every chunk inside the portal's field of
 * view. (None of this applies with Sodium, which replaces that part of the game; hence
 * "require = 0": it is fine if these hooks find nothing to attach to.)
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
	@Shadow
	private double prevCamRotX;
	@Shadow
	@Final
	private LevelRenderer levelRenderer;
	@Unique
	private boolean portalbow$lastWasPortalView;

	@Inject(method = "extract", at = @At("HEAD"))
	private void portalbow$alwaysRecheckVisibleChunks(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo info) {
		boolean portalView = TrueViewCamera.active != null;
		if (portalView || this.portalbow$lastWasPortalView) {
			// The game only rechecks when the camera has turned. Make it think it always has, both
			// for the portal view and for the normal view that follows it.
			this.prevCamRotX = Double.NaN;
		}
		this.portalbow$lastWasPortalView = portalView;
	}

	@WrapWithCondition(method = "extract", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/SectionUpdateTracker;repositionCamera(Lnet/minecraft/core/SectionPos;)V"))
	private boolean portalbow$keepChunkGridCentredOnPlayer(SectionUpdateTracker tracker, SectionPos cameraSection) {
		return TrueViewCamera.active == null;
	}

	@Inject(method = "applyFrustum", require = 0, at = @At("HEAD"), cancellable = true)
	private void portalbow$useEveryChunkInPortalView(Frustum frustum, CallbackInfo info) {
		if (TrueViewCamera.active != null) {
			this.levelRenderer.clearVisibleSections();
			TrueViews.addChunksInView(this.levelRenderer, frustum, TrueViewCamera.active.eye());
			info.cancel();
		}
	}
}
