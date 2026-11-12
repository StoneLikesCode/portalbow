package com.stone.portalbow.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Opens up a few private parts of the game's renderer that drawing an extra view needs. */
@Mixin(GameRenderer.class)
public interface GameRendererAccess {
	@Invoker("extractCamera")
	void portalbow$extractCamera(DeltaTracker deltaTracker, float worldPartialTicks);

	@Accessor("fogRenderer")
	FogRenderer portalbow$getFogRenderer();

	@Accessor("globalSettingsUniform")
	GlobalSettingsUniform portalbow$getGlobalSettings();

	@Accessor("resourcePool")
	CrossFrameResourcePool portalbow$getResourcePool();
}
