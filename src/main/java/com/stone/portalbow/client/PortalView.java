package com.stone.portalbow.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.stone.portalbow.PortalBow;
import com.stone.portalbow.PortalEntity;
import com.stone.portalbow.PortalViewCapture;
import com.stone.portalbow.PortalViewPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The picture shown inside one portal: what you would see if the portal were a real window.
 *
 * <p>The game is NOT drawn a second time (that is what shader packs cannot cope with). Instead,
 * for every pixel of a small picture we imagine a straight line from your eye, through that
 * point of the portal, and out of the exit portal, and follow it block by block through the
 * snapshot the server sent until it hits something. The pixel gets the colour of the block
 * texture at that spot. Because the lines start at your eye, the view shifts as you move, the
 * way a real window does. The finished picture is then drawn like any ordinary texture.
 */
public final class PortalView {
	/** Size of the picture. The portal is 1 block by 2, so this is 64 pixels per block. */
	private static final int WIDTH = 64;
	private static final int HEIGHT = 128;
	private static final int SIZE = PortalViewCapture.SIZE;
	private static final int HALF = PortalViewCapture.HALF;

	/** How bright each light level (0 to 15) is drawn, from 0 to 256. */
	private static final int[] BRIGHTNESS = new int[16];
	/** How much each side of a block is darkened, by Direction.ordinal(): down, up, north, south, west, east. */
	private static final int[] SIDE_SHADE = {128, 256, 205, 205, 154, 154};

	static {
		for (int level = 0; level < 16; level++) {
			BRIGHTNESS[level] = (int) (256 * (0.07 + 0.93 * Math.pow(level / 15.0, 1.3)));
		}
	}

	private final Identifier textureId;
	private final DynamicTexture texture;
	private final RenderType renderType;

	private int[] states;
	private byte[] light;
	private int skyColor;
	/** For each of the snapshot's directions (right, up, out): which world axis it lies along (0 = x, 1 = y, 2 = z)... */
	private final int[] worldAxis = new int[3];
	/** ...and whether it points the positive way along that axis. */
	private final boolean[] positive = new boolean[3];

	private boolean stale = true;
	private double lastEyeRight = Double.NaN;
	private double lastEyeUp;
	private double lastEyeOut;
	int ticksSinceSeen;

	PortalView(int entityId) {
		this.textureId = PortalBow.id("portal_view/" + entityId);
		this.texture = new DynamicTexture("portalbow view " + entityId, WIDTH, HEIGHT, true);
		Minecraft.getInstance().getTextureManager().register(this.textureId, this.texture);
		this.renderType = RenderTypes.entityCutout(this.textureId);
	}

	/** Where this portal leads. Used by {@link TrueViews} to decide whether it can draw the real thing. */
	record Exit(ResourceKey<Level> dimension, Vec3 pos, Direction facing, Direction up) {
	}

	private Exit exit;

	Exit exit() {
		return this.exit;
	}

	void accept(PortalViewPayload payload) {
		this.exit = new Exit(payload.exitDimension(), payload.exitPos(), payload.exitFacing(), payload.exitUp());
		this.states = payload.states();
		this.light = payload.light();
		this.skyColor = payload.skyColor();
		Vec3i out = payload.exitFacing().getUnitVec3i();
		Vec3i up = payload.exitUp().getUnitVec3i();
		Vec3i right = up.cross(out);
		Vec3i[] directions = {right, up, out};
		for (int i = 0; i < 3; i++) {
			Direction direction = Direction.getNearest(directions[i].getX(), directions[i].getY(), directions[i].getZ(), Direction.UP);
			this.worldAxis[i] = direction.getAxis().ordinal();
			this.positive[i] = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
		}
		this.stale = true;
	}

	void close() {
		Minecraft.getInstance().getTextureManager().release(this.textureId);
	}

	/**
	 * Brings the picture up to date for where the camera is now.
	 *
	 * @return how to draw it, or null if the camera is behind the portal
	 */
	RenderType update(PortalEntity portal, Vec3 camera) {
		this.ticksSinceSeen = 0;
		Vec3 out = portal.getFacing().getUnitVec3();
		Vec3 up = portal.getUp().getUnitVec3();
		Vec3 right = up.cross(out);
		Vec3 toEye = camera.subtract(portal.position());
		double eyeRight = toEye.dot(right);
		double eyeUp = toEye.dot(up);
		double eyeOut = toEye.dot(out);
		if (eyeOut <= 0.001) {
			return null;
		}

		boolean moved = Double.isNaN(this.lastEyeRight)
				|| Math.abs(eyeRight - this.lastEyeRight) + Math.abs(eyeUp - this.lastEyeUp) + Math.abs(eyeOut - this.lastEyeOut) > 0.004;
		// Far away the view is a speck, so only redraw it when the snapshot itself changes.
		if (this.stale || (moved && toEye.lengthSqr() < 64 * 64)) {
			this.paint(eyeRight, eyeUp, eyeOut);
			this.texture.upload();
			this.stale = false;
			this.lastEyeRight = eyeRight;
			this.lastEyeUp = eyeUp;
			this.lastEyeOut = eyeOut;
		}
		return this.renderType;
	}

	private void paint(double eyeRight, double eyeUp, double eyeOut) {
		NativeImage image = this.texture.getPixels();
		for (int py = 0; py < HEIGHT; py++) {
			// Where this pixel is on the portal: x from -0.5 to 0.5 across, y from -1 to 1 up.
			double y = 1.0 - (py + 0.5) * 2.0 / HEIGHT;
			for (int px = 0; px < WIDTH; px++) {
				double x = (px + 0.5) / WIDTH - 0.5;
				double ex = x / 0.47;
				double ey = y / 0.97;
				if (ex * ex + ey * ey > 1.0) {
					image.setPixel(px, py, 0); // Outside the oval: see-through.
					continue;
				}
				// Going through a portal is like turning around, so "right" flips: a line that
				// was heading right-and-in comes out heading left-and-out.
				image.setPixel(px, py, 0xFF000000 | this.trace(
						-x + 0.5 + HALF, y + HALF,
						eyeRight - x, y - eyeUp, eyeOut));
			}
		}
	}

	/**
	 * Follows one line through the snapshot and returns the colour it ends on (0xRRGGBB).
	 * Positions are in snapshot cells: (ox, oy) is where the line leaves the exit portal.
	 */
	private int trace(double ox, double oy, double dx, double dy, double dz) {
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		dx /= length;
		dy /= length;
		dz /= length;
		double oz = 1.0E-4;

		int cx = (int) Math.floor(ox);
		int cy = (int) Math.floor(oy);
		int cz = 0;
		int stepX = dx > 0 ? 1 : -1;
		int stepY = dy > 0 ? 1 : -1;
		// How far along the line between crossing one cell wall and the next, per direction.
		double gapX = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
		double gapY = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
		double gapZ = Math.abs(1.0 / dz);
		// How far along the line the next cell wall is, per direction.
		double nextX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? cx + 1 - ox : ox - cx) * gapX;
		double nextY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? cy + 1 - oy : oy - cy) * gapY;
		double nextZ = (1 - oz) * gapZ;

		double distance = 0.0;   // how far along the line the current cell begins
		int enteredBy = 2;       // which direction we stepped in to enter this cell (0 = x, 1 = y, 2 = z)
		int lastLight = 15;      // brightness of the last open cell passed through

		while (cx >= 0 && cx < SIZE && cy >= 0 && cy < SIZE && cz < SIZE) {
			int index = (cz * SIZE + cy) * SIZE + cx;
			int stateId = this.states[index];
			BlockLooks.Look look = stateId == 0 ? null : BlockLooks.get(stateId);
			if (look == null || look.invisible) {
				lastLight = this.light[index];
			} else {
				double hitAt = distance;
				int hitSide = enteredBy;
				boolean hit = true;
				if (!look.fullCube) {
					lastLight = this.light[index];
					// A partial block (slab, fence...): does the line touch the box it fills?
					double near = distance;
					double far = Math.min(nextX, Math.min(nextY, nextZ));
					for (int axis = 0; axis < 3 && hit; axis++) {
						double origin = axis == 0 ? ox : axis == 1 ? oy : oz;
						double direction = axis == 0 ? dx : axis == 1 ? dy : dz;
						int cell = axis == 0 ? cx : axis == 1 ? cy : cz;
						int w = this.worldAxis[axis];
						double low = cell + (this.positive[axis] ? look.box[w] : 1.0 - look.box[w + 3]);
						double high = cell + (this.positive[axis] ? look.box[w + 3] : 1.0 - look.box[w]);
						if (direction == 0) {
							hit = origin >= low && origin <= high;
							continue;
						}
						double t1 = (low - origin) / direction;
						double t2 = (high - origin) / direction;
						double tNear = Math.min(t1, t2);
						if (tNear > near) {
							near = tNear;
							hitSide = axis;
						}
						far = Math.min(far, Math.max(t1, t2));
						hit = near <= far + 1.0E-9;
					}
					hitAt = near;
				}
				if (hit) {
					int color = this.sample(look, hitAt, hitSide, ox, oy, oz, dx, dy, dz, cx, cy, cz, stepX, stepY);
					if (color >>> 24 >= 128) {
						int brightness = look.glows ? 256 : BRIGHTNESS[lastLight & 15];
						return this.fog(shade(color, brightness), hitAt, lastLight);
					}
					// A see-through pixel (a gap between leaves, a window pane): keep going.
				}
			}

			// Step into whichever neighbouring cell the line reaches first.
			if (nextX <= nextY && nextX <= nextZ) {
				distance = nextX;
				nextX += gapX;
				cx += stepX;
				enteredBy = 0;
			} else if (nextY <= nextZ) {
				distance = nextY;
				nextY += gapY;
				cy += stepY;
				enteredBy = 1;
			} else {
				distance = nextZ;
				nextZ += gapZ;
				cz++;
				enteredBy = 2;
			}
		}
		// Ran out of snapshot without hitting anything: open sky (or darkness, underground).
		return shade(this.skyColor, BRIGHTNESS[lastLight & 15]);
	}

	/** The colour of a block's texture at the point where the line hits it (0xAARRGGBB, already side-shaded). */
	private int sample(BlockLooks.Look look, double hitAt, int hitSide,
			double ox, double oy, double oz, double dx, double dy, double dz,
			int cx, int cy, int cz, int stepX, int stepY) {
		// Where in its cell the line hits, 0 to 1 along each world axis.
		double[] within = new double[3];
		for (int axis = 0; axis < 3; axis++) {
			double origin = axis == 0 ? ox : axis == 1 ? oy : oz;
			double direction = axis == 0 ? dx : axis == 1 ? dy : dz;
			int cell = axis == 0 ? cx : axis == 1 ? cy : cz;
			double local = Math.clamp(origin + direction * hitAt - cell, 0.0, 0.999999);
			within[this.worldAxis[axis]] = this.positive[axis] ? local : 0.999999 - local;
		}

		// Which side of the block was hit, as a world direction. The side faces back along the line.
		int travel = hitSide == 0 ? stepX : hitSide == 1 ? stepY : 1;
		boolean sideIsPositive = this.positive[hitSide] != (travel > 0);
		int side = switch (this.worldAxis[hitSide]) {
			case 0 -> sideIsPositive ? Direction.EAST.ordinal() : Direction.WEST.ordinal();
			case 1 -> sideIsPositive ? Direction.UP.ordinal() : Direction.DOWN.ordinal();
			default -> sideIsPositive ? Direction.SOUTH.ordinal() : Direction.NORTH.ordinal();
		};

		double u;
		double v;
		switch (this.worldAxis[hitSide]) {
			case 0 -> {
				u = within[2];
				v = 1.0 - within[1];
			}
			case 1 -> {
				u = within[0];
				v = within[2];
			}
			default -> {
				u = within[0];
				v = 1.0 - within[1];
			}
		}
		int argb = look.pixels[side][((int) (v * 16) & 15) * 16 + ((int) (u * 16) & 15)];
		return argb & 0xFF000000 | shade(argb, SIDE_SHADE[side]);
	}

	/** Scales a colour's brightness; 256 leaves it unchanged. Returns 0xRRGGBB. */
	private static int shade(int rgb, int amount) {
		return ((rgb >> 16 & 0xFF) * amount >> 8) << 16
				| ((rgb >> 8 & 0xFF) * amount >> 8) << 8
				| ((rgb & 0xFF) * amount >> 8);
	}

	/** Fades distant blocks into the sky colour, so the edge of the snapshot is not a hard wall. */
	private int fog(int rgb, double distance, int lastLight) {
		double start = SIZE * 0.55;
		if (distance <= start) {
			return rgb;
		}
		int amount = (int) (256 * Math.min(1.0, (distance - start) / (SIZE * 0.45)));
		int sky = shade(this.skyColor, BRIGHTNESS[lastLight & 15]);
		int r = (rgb >> 16 & 0xFF) + (((sky >> 16 & 0xFF) - (rgb >> 16 & 0xFF)) * amount >> 8);
		int g = (rgb >> 8 & 0xFF) + (((sky >> 8 & 0xFF) - (rgb >> 8 & 0xFF)) * amount >> 8);
		int b = (rgb & 0xFF) + (((sky & 0xFF) - (rgb & 0xFF)) * amount >> 8);
		return r << 16 | g << 8 | b;
	}
}
