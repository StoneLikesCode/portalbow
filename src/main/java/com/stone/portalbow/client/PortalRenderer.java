package com.stone.portalbow.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.stone.portalbow.PortalBow;
import com.stone.portalbow.PortalEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a portal as three flat pictures stacked on the wall: the inside (a view of the other
 * side once both portals exist, a slowly spinning swirl until then), the bright oval rim on top
 * of it, and a glow over the rim.
 *
 * <p>Shader compatibility is the whole point of this mod, so nothing clever happens here. There
 * is no "window" into the other side and no custom shader. Each layer is an ordinary textured
 * rectangle drawn with one of the game's own entity render types (the same ones used for mobs
 * and for spider eyes), which shader packs such as Complementary already know how to handle.
 */
public class PortalRenderer extends EntityRenderer<PortalEntity, PortalRenderState> {
	private static final Layers ORANGE = new Layers("orange");
	private static final Layers BLUE = new Layers("blue");

	public PortalRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public PortalRenderState createRenderState() {
		return new PortalRenderState();
	}

	@Override
	public void extractRenderState(PortalEntity portal, PortalRenderState state, float partialTick) {
		super.extractRenderState(portal, state, partialTick);
		state.orange = portal.isOrange();
		state.facing = portal.getFacing();
		state.up = portal.getUp();
		// Best first: the real view, else the simpler picture made from the server's snapshot.
		state.trueView = TrueViews.renderTypeFor(portal);
		state.view = state.trueView != null ? null : PortalViews.renderTypeFor(portal);
		// While the view through a portal is being drawn, the camera sits right behind its exit
		// portal. That portal must be left out or it would fill the whole picture.
		TrueViewCamera viewCamera = TrueViewCamera.active;
		state.hidden = viewCamera != null && portal.position().distanceToSqr(viewCamera.exitPos()) < 0.01;
	}

	@Override
	public void submit(PortalRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		super.submit(state, poseStack, collector, camera);
		if (state.hidden) {
			return;
		}

		Layers layers = state.orange ? ORANGE : BLUE;
		Vec3 n = state.facing.getUnitVec3();
		Vec3 u = state.up.getUnitVec3();
		Vec3 r = u.cross(n);
		// The two colours spin in opposite directions.
		float spin = state.ageInTicks * 0.05F * (state.orange ? 1.0F : -1.0F);

		if (state.trueView != null) {
			// The real thing: the world as drawn from the far side (see TrueViews).
			collector.submitCustomGeometry(poseStack, state.trueView, (pose, buffer) -> oval(pose, buffer, r, u, n));
		} else if (state.view != null) {
			// Linked: show what is on the other side (see PortalView for how that picture is made).
			collector.submitCustomGeometry(poseStack, state.view, (pose, buffer) ->
					quad(pose, buffer, r, u, n, 0.5F, 1.0F, 0.0F, 0.0F));
		} else {
			// Not linked yet: just the swirl.
			collector.submitCustomGeometry(poseStack, layers.swirl, (pose, buffer) ->
					quad(pose, buffer, r, u, n, 0.45F, 0.95F, spin, 0.0F));
		}
		collector.submitCustomGeometry(poseStack, layers.rim, (pose, buffer) ->
				quad(pose, buffer, r, u, n, 0.5F, 1.0F, 0.0F, 0.004F));
		collector.submitCustomGeometry(poseStack, layers.glow, (pose, buffer) ->
				quad(pose, buffer, r, u, n, 0.5F, 1.0F, 0.0F, 0.008F));
	}

	/**
	 * Adds one textured rectangle, centred on the portal and lying flat against the surface.
	 *
	 * @param halfWidth  half its size along "right"
	 * @param halfHeight half its size along "up"
	 * @param spin       how far (in radians) the picture is turned before being stretched to fit
	 * @param lift       how far it is raised off the surface, so stacked layers do not flicker
	 */
	private static void quad(PoseStack.Pose pose, VertexConsumer buffer, Vec3 r, Vec3 u, Vec3 n,
			float halfWidth, float halfHeight, float spin, float lift) {
		float cos = Mth.cos(spin);
		float sin = Mth.sin(spin);
		// The four corners, anticlockwise from bottom-left, with the matching texture corners.
		float[][] corners = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
		for (float[] corner : corners) {
			float x = (corner[0] * cos - corner[1] * sin) * halfWidth;
			float y = (corner[0] * sin + corner[1] * cos) * halfHeight;
			buffer.addVertex(pose,
							(float) (r.x * x + u.x * y + n.x * lift),
							(float) (r.y * x + u.y * y + n.y * lift),
							(float) (r.z * x + u.z * y + n.z * lift))
					.setColor(-1)
					.setUv(corner[2], corner[3])
					.setOverlay(OverlayTexture.NO_OVERLAY)
					// Always fully lit: a portal glows the same in a dark cave as in daylight.
					.setLight(LightCoordsUtil.FULL_BRIGHT)
					.setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
		}
	}

	/**
	 * Adds an oval covered by a whole picture, built from thin horizontal strips. Used for the
	 * real view, whose picture is rectangular and so cannot supply the oval shape itself.
	 */
	private static void oval(PoseStack.Pose pose, VertexConsumer buffer, Vec3 r, Vec3 u, Vec3 n) {
		int strips = 24;
		for (int i = 0; i < strips; i++) {
			float y0 = -0.97F + 1.94F * i / strips;
			float y1 = -0.97F + 1.94F * (i + 1) / strips;
			float w0 = 0.47F * Mth.sqrt(Math.max(0.0F, 1.0F - (y0 / 0.97F) * (y0 / 0.97F)));
			float w1 = 0.47F * Mth.sqrt(Math.max(0.0F, 1.0F - (y1 / 0.97F) * (y1 / 0.97F)));
			ovalVertex(pose, buffer, r, u, n, -w0, y0);
			ovalVertex(pose, buffer, r, u, n, w0, y0);
			ovalVertex(pose, buffer, r, u, n, w1, y1);
			ovalVertex(pose, buffer, r, u, n, -w1, y1);
		}
	}

	private static void ovalVertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 r, Vec3 u, Vec3 n, float x, float y) {
		buffer.addVertex(pose, (float) (r.x * x + u.x * y), (float) (r.y * x + u.y * y), (float) (r.z * x + u.z * y))
				.setColor(-1)
				// The picture's left edge is the portal's left edge; its bottom row is the portal's bottom.
				.setUv(x + 0.5F, (y + 1.0F) / 2.0F)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightCoordsUtil.FULL_BRIGHT)
				.setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
	}

	/** The three render types for one colour, built once and reused every frame. */
	private static final class Layers {
		final RenderType swirl;
		final RenderType rim;
		final RenderType glow;

		Layers(String color) {
			this.swirl = RenderTypes.entityCutout(PortalBow.id("textures/entity/portal_" + color + "_swirl.png"));
			this.rim = RenderTypes.entityCutout(PortalBow.id("textures/entity/portal_" + color + "_rim.png"));
			// "eyes" is the render type that makes spider and enderman eyes glow in the dark.
			this.glow = RenderTypes.eyes(PortalBow.id("textures/entity/portal_" + color + "_glow.png"));
		}
	}
}
