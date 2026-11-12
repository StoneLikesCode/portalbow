package com.stone.portalbow.mixin;

import com.stone.portalbow.client.TrueViewCamera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** See LevelExtractorMixin: keeps the grid of chunk meshes centred on the player during a portal view. */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Inject(method = "repositionCamera", require = 0, at = @At("HEAD"), cancellable = true)
	private void portalbow$keepChunkGridCentredOnPlayer(CameraRenderState camera, CallbackInfo info) {
		if (TrueViewCamera.active != null) {
			info.cancel();
		}
	}
}
