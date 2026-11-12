package com.stone.portalbow.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.stone.portalbow.PortalEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where the game's camera is put while it draws the view through one portal.
 *
 * <p>Think of the two portals as one doorway. Your eye is some distance in front of the entry
 * portal; the "virtual eye" is the same distance behind the exit portal, as if you were standing
 * on the far side of the doorway. The camera looks straight out of the exit portal, and its
 * field of view is squeezed so that the picture's edges are exactly the portal's edges. (That
 * also means everything nearer than the exit portal, such as the wall it hangs on, is cut away
 * automatically.) The finished picture can then be pasted straight onto the entry portal.
 *
 * @param eye       where the virtual eye is
 * @param exitPos   the middle of the exit portal, so that portal itself can be left out of the picture
 * @param left      edges of the picture as seen from the eye, at distance {@code near}
 * @param near      distance from the virtual eye to the exit portal
 */
public record TrueViewCamera(Vec3 eye, Vec3 exitPos, float yaw, float pitch,
		float left, float right, float bottom, float top, float near) {
	/** The camera currently in force, or null during normal drawing. Read by the mixins. */
	public static TrueViewCamera active;

	static TrueViewCamera compute(PortalEntity entry, PortalView.Exit exit, Vec3 mainEye) {
		Vec3 n1 = entry.getFacing().getUnitVec3();
		Vec3 u1 = entry.getUp().getUnitVec3();
		Vec3 r1 = u1.cross(n1);
		Vec3 toEye = mainEye.subtract(entry.position());
		double eyeRight = toEye.dot(r1);
		double eyeUp = toEye.dot(u1);
		double eyeOut = toEye.dot(n1);

		Vec3 n2 = exit.facing().getUnitVec3();
		Vec3 u2 = exit.up().getUnitVec3();
		Vec3 r2 = u2.cross(n2);
		// Going through is like turning around: left/right and in/out both flip.
		Vec3 eye = exit.pos().add(r2.scale(-eyeRight)).add(u2.scale(eyeUp)).add(n2.scale(-eyeOut));

		float pitch;
		float yaw;
		if (exit.facing().getAxis().isHorizontal()) {
			pitch = 0.0F;
			yaw = exit.facing().toYRot();
		} else {
			// Looking straight up or down. Of the four ways the camera could be turned, pick the
			// one whose "top of the screen" matches the long side of the exit portal.
			pitch = exit.facing() == Direction.UP ? -90.0F : 90.0F;
			yaw = 0.0F;
			double best = -2.0;
			for (float candidate = 0.0F; candidate < 360.0F; candidate += 90.0F) {
				Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(new Quaternionf().rotationYXZ(
						(float) Math.PI - candidate * (float) (Math.PI / 180.0), -pitch * (float) (Math.PI / 180.0), 0.0F));
				double match = up.x * u2.x + up.y * u2.y + up.z * u2.z;
				if (match > best) {
					best = match;
					yaw = candidate;
				}
			}
		}

		// The portal is 1 wide and 2 tall around its middle. From the virtual eye, looking out,
		// the portal's "right" is on the left of the screen.
		return new TrueViewCamera(eye, exit.pos(), yaw, pitch,
				(float) (-0.5 - eyeRight), (float) (0.5 - eyeRight), (float) (-1.0 - eyeUp), (float) (1.0 - eyeUp),
				(float) Math.max(eyeOut, 0.02));
	}

	/**
	 * The lens for drawing. Minecraft measures depth "backwards" (far things are 0), which it gets
	 * by swapping near and far, so we do the same.
	 */
	Matrix4f projection(float far) {
		float k = far / this.near;
		return new Matrix4f().setFrustum(this.left * k, this.right * k, this.bottom * k, this.top * k, far, this.near,
				RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
	}

	/** The same lens the ordinary way round, used only to work out which chunks and mobs are in view. */
	public Matrix4f cullingProjection(float far) {
		return new Matrix4f().setFrustum(this.left, this.right, this.bottom, this.top, this.near, far,
				RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
	}
}
