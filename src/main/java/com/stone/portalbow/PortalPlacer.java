package com.stone.portalbow;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Decides where a portal goes when a portal arrow lands, and whether it fits there. */
public final class PortalPlacer {
	/** How far the portal floats in front of the surface, so it does not flicker against it. */
	private static final double GAP = 0.02;

	private PortalPlacer() {
	}

	/**
	 * Opens the shooter's orange or blue portal where the arrow hit, replacing their previous
	 * portal of that colour. If it does not fit, the shot just fizzles.
	 */
	public static void open(ServerLevel level, BlockHitResult hit, Entity shooter, boolean orange) {
		Direction facing = hit.getDirection();
		// The long side: upright on a wall; on a floor or ceiling, the way the shooter is looking.
		Direction up = facing.getAxis().isHorizontal() ? Direction.UP : Direction.fromYRot(shooter.getYRot());

		// A portal covers two blocks: the one that was hit and a neighbour. Try the neighbour
		// on the side of the block the arrow landed nearer to first, then the other side.
		BlockPos hitBlock = hit.getBlockPos();
		double side = hit.getLocation().subtract(Vec3.atCenterOf(hitBlock)).dot(up.getUnitVec3());
		BlockPos first = side >= 0.0 ? hitBlock : hitBlock.relative(up.getOpposite());
		BlockPos second = side >= 0.0 ? hitBlock.relative(up.getOpposite()) : hitBlock;

		UUID owner = shooter.getUUID();
		PortalLinks links = PortalLinks.get(level.getServer());
		Optional<PortalLinks.Spot> otherColor = links.find(owner, !orange);

		for (BlockPos lower : new BlockPos[] {first, second}) {
			// The middle of the oval: on the surface, where the two blocks meet.
			Vec3 pos = Vec3.atCenterOf(lower)
					.add(facing.getUnitVec3().scale(0.5 + GAP))
					.add(up.getUnitVec3().scale(0.5));
			if (!isGoodSite(level, pos, facing, up) || overlaps(level, pos, facing, up, otherColor)) {
				continue;
			}

			// Close the old portal of this colour straight away if it is loaded. (If it is not,
			// it closes itself when it next loads and finds it is no longer in the notebook.)
			links.find(owner, orange).ifPresent(old -> {
				ServerLevel oldLevel = level.getServer().getLevel(old.dimension());
				if (oldLevel != null && oldLevel.getEntity(old.entity()) instanceof PortalEntity oldPortal) {
					oldPortal.discard();
				}
			});

			PortalEntity portal = new PortalEntity(level, pos, facing, up, orange, owner);
			links.put(owner, orange, new PortalLinks.Spot(level.dimension(), pos, facing, up, portal.getUUID()));
			level.addFreshEntity(portal);

			level.sendParticles(new DustParticleOptions(orange ? PortalEntity.ORANGE_COLOR : PortalEntity.BLUE_COLOR, 1.2F),
					pos.x, pos.y, pos.z, 30, 0.35, 0.35, 0.35, 0.0);
			level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 0.8F, orange ? 1.0F : 1.3F);
			return;
		}

		Vec3 at = hit.getLocation();
		level.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 8, 0.1, 0.1, 0.1, 0.01);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.3F, 1.8F);
	}

	/**
	 * A portal needs two solid block faces behind it and two clear spaces in front of it.
	 * Also used by existing portals to check they still have a wall to sit on.
	 */
	public static boolean isGoodSite(ServerLevel level, Vec3 pos, Direction facing, Direction up) {
		Vec3 n = facing.getUnitVec3();
		Vec3 u = up.getUnitVec3();
		for (double half : new double[] {-0.5, 0.5}) {
			Vec3 middle = pos.add(u.scale(half));
			BlockPos behind = BlockPos.containing(middle.subtract(n.scale(0.5)));
			BlockPos inFront = BlockPos.containing(middle.add(n.scale(0.4)));
			BlockState wall = level.getBlockState(behind);
			if (!wall.isFaceSturdy(level, behind, facing)) {
				return false;
			}
			if (!level.getBlockState(inFront).getCollisionShape(level, inFront).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Would a portal here sit on top of the shooter's portal of the other colour? */
	private static boolean overlaps(ServerLevel level, Vec3 pos, Direction facing, Direction up, Optional<PortalLinks.Spot> other) {
		return other
				.filter(spot -> spot.dimension().equals(level.dimension()))
				.map(spot -> PortalEntity.boxFor(pos, facing, up).deflate(0.01)
						.intersects(PortalEntity.boxFor(spot.pos(), spot.facing(), spot.up())))
				.orElse(false);
	}
}
