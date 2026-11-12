package com.stone.portalbow;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A portal stuck to the face of a block: one block wide and two blocks long.
 *
 * <p>It is described by three directions. "facing" points out of the surface, towards you when
 * you look at the portal. "up" is the direction of the long side: straight up for a portal on a
 * wall, and the way the shooter was looking for one on a floor or ceiling. "right" is worked out
 * from those two. The portal's position is the very middle of the oval.
 */
public class PortalEntity extends Entity {
	public static final int ORANGE_COLOR = 0xFF8A16;
	public static final int BLUE_COLOR = 0x2696FF;

	/** How far out from the surface something still counts as touching the portal. */
	private static final double REACH = 0.25;
	/** Things come out at least this fast, so they never sit inside the exit portal. */
	private static final double MIN_EXIT_SPEED = 0.25;

	private static final EntityDataAccessor<Boolean> ORANGE =
			SynchedEntityData.defineId(PortalEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Direction> FACING =
			SynchedEntityData.defineId(PortalEntity.class, EntityDataSerializers.DIRECTION);
	private static final EntityDataAccessor<Direction> UP =
			SynchedEntityData.defineId(PortalEntity.class, EntityDataSerializers.DIRECTION);

	/**
	 * Who has just come out of which portal (traveller's id -> portal's id). A traveller cannot
	 * go back in through the portal they arrived at until they have stepped clear of it.
	 * Without this you would bounce back and forth forever while standing still.
	 */
	private static final Map<UUID, UUID> JUST_ARRIVED = new ConcurrentHashMap<>();

	private UUID owner;
	/** Which players have been sent which view (see PortalViewCapture.fingerprint). Server only. */
	private final Map<UUID, Integer> sentViews = new java.util.HashMap<>();
	private int viewUpdates;

	public PortalEntity(EntityType<? extends PortalEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	public PortalEntity(ServerLevel level, Vec3 pos, Direction facing, Direction up, boolean orange, UUID owner) {
		this(PortalBow.PORTAL, level);
		this.owner = owner;
		this.entityData.set(ORANGE, orange);
		this.entityData.set(FACING, facing);
		this.entityData.set(UP, up);
		this.setPos(pos);
	}

	public boolean isOrange() {
		return this.entityData.get(ORANGE);
	}

	public Direction getFacing() {
		return this.entityData.get(FACING);
	}

	public Direction getUp() {
		return this.entityData.get(UP);
	}

	// ---- shape -------------------------------------------------------------------------------

	/** The thin slab of space in front of the surface that a portal at this spot takes up. */
	public static AABB boxFor(Vec3 pos, Direction facing, Direction up) {
		Vec3 n = facing.getUnitVec3();
		Vec3 u = up.getUnitVec3();
		Vec3 r = u.cross(n);
		Vec3 a = pos.add(r.scale(-0.5)).add(u.scale(-1.0)).add(n.scale(-0.05));
		Vec3 b = pos.add(r.scale(0.5)).add(u.scale(1.0)).add(n.scale(REACH));
		return new AABB(a, b);
	}

	@Override
	protected AABB makeBoundingBox(Vec3 position) {
		// The game first calls this while the entity is still being built, before our data exists.
		if (this.entityData == null) {
			return super.makeBoundingBox(position);
		}
		return boxFor(position, this.getFacing(), this.getUp());
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (FACING.equals(accessor) || UP.equals(accessor)) {
			this.setBoundingBox(this.makeBoundingBox());
		}
	}

	// ---- every tick --------------------------------------------------------------------------

	@Override
	public void tick() {
		if (!(this.level() instanceof ServerLevel level)) {
			return;
		}
		if (this.owner == null) {
			this.discard();
			return;
		}

		PortalLinks links = PortalLinks.get(level.getServer());
		boolean orange = this.isOrange();

		// Twice a second, check that this portal should still exist.
		if (this.tickCount % 10 == 1) {
			boolean stillMine = links.find(this.owner, orange)
					.map(spot -> spot.entity().equals(this.getUUID()))
					.orElse(false);
			if (!stillMine) {
				// The owner has opened a newer portal of this colour somewhere else.
				this.close(level);
				return;
			}
			if (!PortalPlacer.isGoodSite(level, this.position(), this.getFacing(), this.getUp())) {
				// The wall behind it was broken, or something was built in front of it.
				links.forget(this.owner, orange, this.getUUID());
				this.close(level);
				return;
			}
			if (this.random.nextInt(3) == 0) {
				Vec3 c = this.getBoundingBox().getCenter();
				level.sendParticles(new DustParticleOptions(orange ? ORANGE_COLOR : BLUE_COLOR, 0.7F),
						c.x, c.y, c.z, 1, this.getBoundingBox().getXsize() / 3, this.getBoundingBox().getYsize() / 3,
						this.getBoundingBox().getZsize() / 3, 0.0);
			}
		}

		// Once a second, tell nearby players what can be seen through this portal.
		if ((this.tickCount + this.getId()) % 20 == 0) {
			this.updateViewers(level, links);
		}

		List<Entity> touching = level.getEntities(this, this.getBoundingBox(),
				entity -> !(entity instanceof PortalEntity) && entity.isAlive());

		// Anyone who arrived here earlier and has now stepped clear may use this portal again.
		Set<UUID> touchingIds = new HashSet<>();
		for (Entity entity : touching) {
			touchingIds.add(entity.getUUID());
		}
		UUID self = this.getUUID();
		JUST_ARRIVED.entrySet().removeIf(entry -> entry.getValue().equals(self) && !touchingIds.contains(entry.getKey()));

		Optional<PortalLinks.Spot> exit = links.find(this.owner, !orange);
		if (exit.isEmpty()) {
			return; // Only one portal open so far: it leads nowhere yet.
		}
		for (Entity entity : touching) {
			if (this.canEnter(entity)) {
				this.sendThrough(entity, entity.getKnownMovement(), exit.get());
			}
		}
	}

	/** Sends the view through this portal to every player close enough to see it, if it has changed. */
	private void updateViewers(ServerLevel level, PortalLinks links) {
		java.util.Collection<ServerPlayer> watchers = PlayerLookup.tracking(this);
		if (watchers.isEmpty()) {
			this.sentViews.clear();
			return;
		}

		PortalViewPayload view = PortalViewPayload.unlinked(this.getId());
		Optional<PortalLinks.Spot> exit = links.find(this.owner, !this.isOrange());
		if (exit.isPresent()) {
			ServerLevel exitLevel = level.getServer().getLevel(exit.get().dimension());
			// Keep the far side loaded while someone is looking, the same way a nether portal
			// does. The game drops this by itself about 15 seconds after we stop renewing it.
			if (exitLevel != null && this.viewUpdates++ % 5 == 0) {
				exitLevel.getChunkSource().addTicketWithRadius(
						TicketType.PORTAL, ChunkPos.containing(BlockPos.containing(exit.get().pos())), 2);
			}
			view = PortalViewCapture.capture(this.getId(), level.getServer(), exit.get());
		}

		int fingerprint = PortalViewCapture.fingerprint(view);
		Set<UUID> watcherIds = new HashSet<>();
		for (ServerPlayer player : watchers) {
			watcherIds.add(player.getUUID());
			Integer sent = this.sentViews.put(player.getUUID(), fingerprint);
			if (sent == null || sent != fingerprint) {
				ServerPlayNetworking.send(player, view);
			}
		}
		this.sentViews.keySet().retainAll(watcherIds);
	}

	/** Called when a player comes into range, so they are sent the view afresh. */
	public void forgetViewer(ServerPlayer player) {
		this.sentViews.remove(player.getUUID());
	}

	private boolean canEnter(Entity entity) {
		if (entity.isSpectator() || entity.isPassenger() || self().equals(JUST_ARRIVED.get(entity.getUUID()))) {
			return false;
		}
		// Arrows stuck in the wall beside the portal should stay where they are.
		if (entity instanceof AbstractArrow && entity.getDeltaMovement().lengthSqr() < 1.0E-6) {
			return false;
		}
		// The middle of the entity must be inside the oval's rectangle, so brushing past the
		// rim with an elbow does not count.
		return this.isWithinOpening(entity.getBoundingBox().getCenter(), 0.0);
	}

	private UUID self() {
		return this.getUUID();
	}

	/** Is this point in front of the opening (ignoring how far out from the surface it is)? */
	public boolean isWithinOpening(Vec3 point, double margin) {
		Vec3 offset = point.subtract(this.position());
		Vec3 u = this.getUp().getUnitVec3();
		Vec3 r = u.cross(this.getFacing().getUnitVec3());
		return Math.abs(offset.dot(r)) <= 0.5 + margin && Math.abs(offset.dot(u)) <= 1.0 + margin;
	}

	/**
	 * Sends an entity that is entering this portal out of the owner's other one.
	 * Called from tick() above, and from the arrow mixin for arrows that fly into the portal.
	 *
	 * @return true if the entity was moved
	 */
	public boolean sendThrough(Entity entity) {
		if (!(this.level() instanceof ServerLevel level) || this.owner == null) {
			return false;
		}
		Optional<PortalLinks.Spot> exit = PortalLinks.get(level.getServer()).find(this.owner, !this.isOrange());
		return exit.isPresent() && this.sendThrough(entity, entity.getDeltaMovement(), exit.get());
	}

	private boolean sendThrough(Entity entity, Vec3 velocity, PortalLinks.Spot exit) {
		ServerLevel from = (ServerLevel) this.level();
		ServerLevel to = from.getServer().getLevel(exit.dimension());
		if (to == null) {
			return false;
		}

		// The three directions of this portal (1) and of the exit portal (2).
		Vec3 n1 = this.getFacing().getUnitVec3();
		Vec3 u1 = this.getUp().getUnitVec3();
		Vec3 r1 = u1.cross(n1);
		Vec3 n2 = exit.facing().getUnitVec3();
		Vec3 u2 = exit.up().getUnitVec3();
		Vec3 r2 = u2.cross(n2);

		double width = entity.getBbWidth();
		double height = entity.getBbHeight();

		// Where in the oval the entity went in, so it comes out of the matching spot. Going
		// through is like turning around, so left and right swap. The spot is nudged inwards
		// if needed so the entity fits inside the exit oval.
		Vec3 offset = entity.getBoundingBox().getCenter().subtract(this.position());
		double alongUp = clampToFit(offset.dot(u1), 1.0 - halfSizeAlong(u2, width, height));
		double alongRight = clampToFit(-offset.dot(r1), 0.5 - halfSizeAlong(r2, width, height));
		double outwards = halfSizeAlong(n2, width, height) + 0.05;
		Vec3 center = exit.pos().add(u2.scale(alongUp)).add(r2.scale(alongRight)).add(n2.scale(outwards));
		Vec3 feet = center.subtract(0.0, height / 2.0, 0.0);

		// Speed is kept: what was "into this portal" becomes "out of the other one".
		Vec3 newVelocity = n2.scale(-velocity.dot(n1))
				.add(u2.scale(velocity.dot(u1)))
				.add(r2.scale(-velocity.dot(r1)));
		double outSpeed = newVelocity.dot(n2);
		if (outSpeed < MIN_EXIT_SPEED) {
			newVelocity = newVelocity.add(n2.scale(MIN_EXIT_SPEED - outSpeed));
		}

		// Which way the traveller ends up looking.
		float yaw = entity.getYRot();
		boolean entryIsWall = this.getFacing().getAxis().isHorizontal();
		boolean exitIsWall = exit.facing().getAxis().isHorizontal();
		if (entryIsWall && exitIsWall) {
			// Wall to wall: turn by however much the two walls differ.
			yaw += exit.facing().toYRot() - this.getFacing().getOpposite().toYRot();
		} else if (exitIsWall) {
			// Floor or ceiling to wall: come out looking straight away from the wall.
			yaw = exit.facing().toYRot();
		}
		// (Out of a floor or ceiling: keep looking the same way.)

		Entity arrived = entity.teleport(new TeleportTransition(
				to, feet, newVelocity, Mth.wrapDegrees(yaw), entity.getXRot(), TeleportTransition.DO_NOTHING));
		if (arrived == null) {
			return false;
		}
		arrived.resetFallDistance();
		JUST_ARRIVED.put(arrived.getUUID(), exit.entity());

		from.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.35F, 1.5F);
		to.playSound(null, center.x, center.y, center.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.35F, 1.5F);
		return true;
	}

	/** Half the entity's size measured along a direction: half its height if vertical, else half its width. */
	private static double halfSizeAlong(Vec3 direction, double width, double height) {
		return Math.abs(direction.y) > 0.5 ? height / 2.0 : width / 2.0;
	}

	private static double clampToFit(double value, double room) {
		return room <= 0.0 ? 0.0 : Mth.clamp(value, -room, room);
	}

	private void close(ServerLevel level) {
		Vec3 c = this.getBoundingBox().getCenter();
		level.sendParticles(new DustParticleOptions(this.isOrange() ? ORANGE_COLOR : BLUE_COLOR, 1.0F),
				c.x, c.y, c.z, 20, 0.3, 0.3, 0.3, 0.0);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.3F, 1.6F);
		this.discard();
	}

	// ---- saving ------------------------------------------------------------------------------

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(ORANGE, true);
		builder.define(FACING, Direction.NORTH);
		builder.define(UP, Direction.UP);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putBoolean("orange", this.isOrange());
		output.store("facing", Direction.CODEC, this.getFacing());
		output.store("up", Direction.CODEC, this.getUp());
		output.storeNullable("owner", UUIDUtil.CODEC, this.owner);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.entityData.set(ORANGE, input.getBooleanOr("orange", true));
		this.entityData.set(FACING, input.read("facing", Direction.CODEC).orElse(Direction.NORTH));
		this.entityData.set(UP, input.read("up", Direction.CODEC).orElse(Direction.UP));
		this.owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
		this.setBoundingBox(this.makeBoundingBox());
	}

	// ---- a portal is scenery: nothing can hurt, push or ride it --------------------------------

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean canBeHitByProjectile() {
		return false;
	}

	@Override
	public boolean isIgnoringBlockTriggers() {
		return true;
	}

	@Override
	public PushReaction getPistonPushReaction() {
		return PushReaction.IGNORE_ENTITY;
	}

	@Override
	public boolean canUsePortal(boolean ignorePassenger) {
		return false;
	}

	@Override
	protected boolean couldAcceptPassenger() {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distanceSqr) {
		return distanceSqr < 128.0 * 128.0;
	}
}
