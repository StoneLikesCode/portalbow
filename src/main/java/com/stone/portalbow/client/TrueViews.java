package com.stone.portalbow.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.stone.portalbow.PortalEntity;
import com.stone.portalbow.mixin.GameRendererAccess;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.slf4j.Logger;

/**
 * Draws the world a second time, from behind the exit portal, so a portal can show the real
 * view of the other side: mobs, players, particles, shader effects and all.
 *
 * <p>Just before the game draws each normal frame, for each portal you can see (up to
 * {@link #MAX_PER_FRAME}) we run the game's own three steps a whole extra time with the camera
 * moved (see {@link TrueViewCamera}): place the camera, gather what is visible, draw it. The
 * result lands on the screen's own canvas; we copy it into a texture for the portal to wear and
 * then let the normal frame draw over the canvas as usual.
 *
 * <p>This only works when the exit portal is in the same dimension and inside your render
 * distance, because your game only has those chunks. Otherwise the portal falls back to the
 * simpler picture made by {@link PortalView}. Each portal in view costs about as much as
 * drawing the whole game once more.
 */
public final class TrueViews {
	private static final Logger LOGGER = LogUtils.getLogger();
	/** How many portal views to draw per frame, nearest first. */
	private static final int MAX_PER_FRAME = 2;
	/** Portals further from the camera than this (in blocks) use the simpler picture. */
	private static final double MAX_DISTANCE = 48.0;

	private static final Int2ObjectMap<TrueView> VIEWS = new Int2ObjectOpenHashMap<>();
	// Also set if something goes wrong, so one failure does not become a crash every frame.
	/** Starts true if the real view is switched off by setting the environment variable PORTALBOW_TRUE_VIEW=off. */
	private static boolean broken = "off".equals(System.getenv("PORTALBOW_TRUE_VIEW"));

	private TrueViews() {
	}

	/** Called from MinecraftMixin at the start of every frame, before the normal camera is placed. */
	public static void renderAll(Minecraft minecraft, boolean advanceGameTime) {
		if (broken || !advanceGameTime || minecraft.level == null || minecraft.player == null
				|| !minecraft.isGameLoadFinished() || !minecraft.gameRenderer.gameRenderState().shouldRenderLevel) {
			return;
		}
		DeltaTracker deltaTracker = minecraft.getDeltaTracker();
		Camera camera = minecraft.gameRenderer.mainCamera();
		// Find out where the real camera is this frame.
		camera.update(deltaTracker);
		if (!camera.isInitialized()) {
			return;
		}
		Vec3 eye = camera.position();
		double reach = (minecraft.options.getEffectiveRenderDistance() - 2) * 16.0;

		record Job(PortalEntity portal, PortalView.Exit exit, double distanceSqr) {
		}
		List<Job> jobs = new ArrayList<>();
		for (Entity entity : minecraft.level.entitiesForRendering()) {
			if (!(entity instanceof PortalEntity portal)) {
				continue;
			}
			PortalView.Exit exit = PortalViews.exitOf(portal);
			if (exit == null || exit.dimension() != minecraft.level.dimension()) {
				continue;
			}
			Vec3 toEye = eye.subtract(portal.position());
			double distanceSqr = toEye.lengthSqr();
			boolean inFront = toEye.dot(portal.getFacing().getUnitVec3()) > 0.02;
			double exitDx = exit.pos().x - minecraft.player.getX();
			double exitDz = exit.pos().z - minecraft.player.getZ();
			boolean exitLoaded = exitDx * exitDx + exitDz * exitDz < reach * reach;
			if (inFront && exitLoaded && distanceSqr < MAX_DISTANCE * MAX_DISTANCE
					&& camera.getCullFrustum().isVisible(portal.getBoundingBox())) {
				jobs.add(new Job(portal, exit, distanceSqr));
			}
		}
		jobs.sort(Comparator.comparingDouble(Job::distanceSqr));
		for (int i = 0; i < Math.min(jobs.size(), MAX_PER_FRAME); i++) {
			Job job = jobs.get(i);
			try {
				renderOne(minecraft, deltaTracker, job.portal(), TrueViewCamera.compute(job.portal(), job.exit(), eye));
			} catch (Throwable problem) {
				broken = true;
				LOGGER.error("Portal Bow: drawing the real view through a portal failed, so it has been switched off "
						+ "until the game restarts. Portals will show the simpler picture instead.", problem);
				return;
			}
		}
	}

	private static void renderOne(Minecraft minecraft, DeltaTracker deltaTracker, PortalEntity portal, TrueViewCamera viewCamera)
			throws ReflectiveOperationException {
		GameRenderer gameRenderer = minecraft.gameRenderer;
		GameRendererAccess access = (GameRendererAccess) gameRenderer;
		RenderTarget screen = gameRenderer.mainRenderTarget();
		float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);

		IrisPipelines.begin(portal.getId());
		TrueViewCamera.active = viewCamera;
		try {
			// Step 1: place the camera. (CameraMixin moves it to the virtual eye.)
			gameRenderer.mainCamera().update(deltaTracker);

			// Step 2: gather everything that camera can see.
			access.portalbow$extractCamera(deltaTracker, partialTicks);
			CameraRenderState cameraState = gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
			cameraState.projectionMatrix.set(viewCamera.projection(cameraState.depthFar));
			// The virtual eye is usually inside a wall, which would make the game think nothing is visible.
			cameraState.smartCull = false;
			// Tells the plain game's "what is visible" map to sit this one out (see LevelExtractorMixin).
			cameraState.isFrustumCaptured = true;
			// No walking bob or hurt tilt for this camera.
			cameraState.entityRenderState.isPlayer = false;
			cameraState.entityRenderState.isLiving = false;
			minecraft.levelExtractor.extract(deltaTracker, gameRenderer.mainCamera(), partialTicks);

			// Chunks are positioned on screen relative to a camera position the game hands to the
			// graphics card once per frame. Hand over the virtual eye's position for this drawing.
			GameRenderState state = gameRenderer.gameRenderState();
			access.portalbow$getGlobalSettings().update(
					state.windowRenderState.width, state.windowRenderState.height, state.optionsRenderState.glintStrength,
					state.levelRenderState.gameTime, state.levelRenderState.worldPartialTicks,
					state.optionsRenderState.menuBackgroundBlurriness, cameraState.pos,
					state.optionsRenderState.textureFiltering == TextureFilteringMethod.RGSS);

			// Step 3: draw it onto the (cleared) screen canvas, then keep a copy.
			RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
					screen.getColorTexture(), new Vector4f(0.0F, 0.0F, 0.0F, 1.0F), screen.getDepthTexture(), 0.0);
			gameRenderer.renderLevel();
			VIEWS.computeIfAbsent(portal.getId(), TrueView::new).capture(screen);

			// Tidy up as the game does at the end of a frame, so the next drawing starts clean.
			access.portalbow$getFogRenderer().endFrame();
			gameRenderer.renderBuffers().endFrame();
			access.portalbow$getResourcePool().endFrame();
			// Hand this drawing to the graphics card now, as its own batch of work. The game
			// normally does this once per frame and reuses a few buffers in rotation, assuming
			// one drawing per batch; three drawings in one batch (two portals plus the normal
			// view) made the Vulkan renderer reuse a buffer too soon and crash.
			RenderSystem.getDevice().createCommandEncoder().submit();
		} finally {
			TrueViewCamera.active = null;
			IrisPipelines.end();
		}
	}

	/**
	 * Fills the game's list of chunk sections to draw with every one inside the portal view,
	 * nearest first. Used with the plain renderer only (see LevelExtractorMixin).
	 */
	public static void addChunksInView(LevelRenderer levelRenderer, Frustum frustum, Vec3 eye) {
		ViewArea area = levelRenderer.viewArea();
		if (area == null) {
			return;
		}
		List<SectionRenderDispatcher.RenderSection> found = levelRenderer.visibleSections();
		int reach = area.getViewDistance();
		int eyeX = SectionPos.blockToSectionCoord(eye.x);
		int eyeZ = SectionPos.blockToSectionCoord(eye.z);
		for (int x = eyeX - 2 * reach; x <= eyeX + 2 * reach; x++) {
			for (int z = eyeZ - 2 * reach; z <= eyeZ + 2 * reach; z++) {
				for (int y = area.minSectionY(); y <= area.maxSectionY(); y++) {
					SectionRenderDispatcher.RenderSection section = area.getRenderSectionAt(new BlockPos(x << 4, y << 4, z << 4));
					if (section != null && frustum.isVisible(section.getBoundingBox())) {
						found.add(section);
					}
				}
			}
		}
		found.sort(Comparator.comparingDouble(section -> section.getBoundingBox().getCenter().distanceToSqr(eye)));
	}

	/** How to draw the inside of this portal with the real view, or null if there is none to show. */
	static RenderType renderTypeFor(PortalEntity portal) {
		TrueView view = VIEWS.get(portal.getId());
		return view != null && view.isFresh() ? view.renderType : null;
	}

	/** Once a tick: let go of pictures and shader pipelines that are no longer being used. */
	static void tick() {
		VIEWS.int2ObjectEntrySet().removeIf(entry -> {
			if (System.nanoTime() - entry.getValue().drawnAt > 10_000_000_000L) {
				entry.getValue().close();
				return true;
			}
			return false;
		});
		IrisPipelines.freeUnused();
	}

	static void clear() {
		VIEWS.values().forEach(TrueView::close);
		VIEWS.clear();
	}
}
