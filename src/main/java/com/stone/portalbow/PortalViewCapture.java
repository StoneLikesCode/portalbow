package com.stone.portalbow;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Takes the snapshot of blocks that a player sees when looking into a portal.
 *
 * <p>The snapshot is a box of cells in front of the EXIT portal, measured in that portal's own
 * directions rather than north/south/east/west:
 * <ul>
 *   <li>x runs along the portal's "right", {@link #HALF} cells either side of its middle,</li>
 *   <li>y runs along the portal's "up", {@link #HALF} cells either side of its middle,</li>
 *   <li>z runs straight out of the portal, {@link #SIZE} cells deep.</li>
 * </ul>
 * Each cell is exactly one block. Cell (x, y, z) is stored at index (z * SIZE + y) * SIZE + x.
 */
public final class PortalViewCapture {
	/** The snapshot is SIZE x SIZE x SIZE blocks. Bigger sees further but costs more to send. */
	public static final int SIZE = 24;
	public static final int HALF = SIZE / 2;
	public static final int CELLS = SIZE * SIZE * SIZE;

	private PortalViewCapture() {
	}

	public static PortalViewPayload capture(int entityId, MinecraftServer server, PortalLinks.Spot exit) {
		ServerLevel level = server.getLevel(exit.dimension());
		if (level == null) {
			return PortalViewPayload.unlinked(entityId);
		}

		Vec3 n = exit.facing().getUnitVec3();
		Vec3 u = exit.up().getUnitVec3();
		Vec3i stepN = exit.facing().getUnitVec3i();
		Vec3i stepU = exit.up().getUnitVec3i();
		Vec3i stepR = stepU.cross(stepN);
		// The block holding cell (HALF, HALF, 0): just in front of the portal, in its upper half.
		BlockPos origin = BlockPos.containing(exit.pos().add(n.scale(0.4)).add(u.scale(0.5)));

		int[] states = new int[CELLS];
		byte[] light = new byte[CELLS];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		LevelChunk chunk = null;
		int chunkX = Integer.MIN_VALUE;
		int chunkZ = Integer.MIN_VALUE;
		int index = 0;
		for (int z = 0; z < SIZE; z++) {
			for (int y = 0; y < SIZE; y++) {
				for (int x = 0; x < SIZE; x++, index++) {
					int dx = x - HALF;
					int dy = y - HALF;
					pos.set(origin.getX() + stepR.getX() * dx + stepU.getX() * dy + stepN.getX() * z,
							origin.getY() + stepR.getY() * dx + stepU.getY() * dy + stepN.getY() * z,
							origin.getZ() + stepR.getZ() * dx + stepU.getZ() * dy + stepN.getZ() * z);
					if (level.isOutsideBuildHeight(pos.getY())) {
						light[index] = (byte) (pos.getY() > 0 ? 15 : 0);
						continue;
					}
					// Only look at parts of the world that are already loaded; never force a load here.
					if ((pos.getX() >> 4) != chunkX || (pos.getZ() >> 4) != chunkZ) {
						chunkX = pos.getX() >> 4;
						chunkZ = pos.getZ() >> 4;
						chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
					}
					if (chunk == null) {
						continue;
					}
					BlockState state = chunk.getBlockState(pos);
					states[index] = state.isAir() ? 0 : Block.getId(state);
					if (!state.isSolidRender()) {
						light[index] = (byte) level.getMaxLocalRawBrightness(pos);
					}
				}
			}
		}
		return new PortalViewPayload(entityId, true, exit.dimension(), exit.pos(), exit.facing(), exit.up(), skyColor(level), states, light);
	}

	/** A number that changes whenever the snapshot does, so unchanged views are not re-sent. */
	public static int fingerprint(PortalViewPayload payload) {
		if (!payload.linked()) {
			return 1;
		}
		int hash = Arrays.hashCode(payload.states());
		hash = hash * 31 + Arrays.hashCode(payload.light());
		hash = hash * 31 + payload.skyColor();
		hash = hash * 31 + payload.exitPos().hashCode() + payload.exitDimension().hashCode();
		hash = hash * 31 + payload.exitFacing().ordinal() * 6 + payload.exitUp().ordinal();
		return hash == 1 ? 2 : hash;
	}

	private static int skyColor(ServerLevel level) {
		if (level.dimension() == Level.NETHER) {
			return 0x3A0B0B;
		}
		if (level.dimension() == Level.END) {
			return 0x150E1F;
		}
		if (!level.dimensionType().hasSkyLight()) {
			return 0x101014;
		}
		// Blend from a night sky to a day sky. getSkyDarken() is 0 at noon and about 11 at midnight.
		float day = 1.0F - Math.min(11, level.getSkyDarken()) / 11.0F;
		int r = (int) (0x0A + (0x7F - 0x0A) * day);
		int g = (int) (0x0E + (0xB0 - 0x0E) * day);
		int b = (int) (0x1E + (0xFF - 0x1E) * day);
		return r << 16 | g << 8 | b;
	}
}
