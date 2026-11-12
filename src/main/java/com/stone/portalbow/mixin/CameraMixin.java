package com.stone.portalbow.mixin;

import com.stone.portalbow.client.TrueViewCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a portal view is being drawn, moves the game's camera to the far side of the portal
 * straight after the game has placed it at the player's eyes.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;
	@Shadow
	private Vec3 position;
	@Shadow
	@Final
	private Matrix4f cachedViewRotMatrix;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	public abstract Matrix4f getViewRotationMatrix(Matrix4f dest);

	@Shadow
	private void prepareCullFrustum(Matrix4fc modelViewMatrix, Matrix4f projectionMatrixForCulling, Vec3 cameraPos) {
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void portalbow$moveToPortalView(DeltaTracker deltaTracker, CallbackInfo info) {
		TrueViewCamera view = TrueViewCamera.active;
		if (view == null) {
			return;
		}
		this.setRotation(view.yaw(), view.pitch());
		this.setPosition(view.eye());
		// "Detached" is what third-person view uses: it makes the game draw the player's own body.
		this.detached = true;
		float far = Minecraft.getInstance().options.getEffectiveRenderDistance() * 64.0F;
		this.prepareCullFrustum(this.getViewRotationMatrix(this.cachedViewRotMatrix), view.cullingProjection(far), this.position);
	}
}
