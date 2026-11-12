package com.stone.portalbow.mixin;

import com.stone.portalbow.client.TrueViewCamera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.profiling.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The view through a portal should not have your hand and held item drawn over it. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "render3dHud", at = @At("HEAD"), cancellable = true)
	private void portalbow$noHandInPortalView(CallbackInfo info) {
		if (TrueViewCamera.active != null) {
			// The skipped method would have closed the profiler section its caller opened.
			Profiler.get().pop();
			info.cancel();
		}
	}
}
