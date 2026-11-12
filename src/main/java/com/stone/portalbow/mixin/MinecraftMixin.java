package com.stone.portalbow.mixin;

import com.stone.portalbow.client.TrueViews;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks the start of every frame, which is when the views through portals are drawn. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "renderFrame", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"))
	private void portalbow$drawPortalViews(boolean advanceGameTime, CallbackInfo info) {
		TrueViews.renderAll((Minecraft) (Object) this, advanceGameTime);
	}
}
