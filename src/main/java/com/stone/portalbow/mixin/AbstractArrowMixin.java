package com.stone.portalbow.mixin;

import com.stone.portalbow.PortalBow;
import com.stone.portalbow.PortalEntity;
import com.stone.portalbow.PortalPlacer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mixin adds code to one of Minecraft's own classes. This one runs just before an arrow would
 * stick into a block, and handles two cases:
 * <ol>
 *   <li>An arrow from a Portal bow opens a portal there and vanishes.</li>
 *   <li>Any other arrow that lands on an open portal flies through it instead of sticking.</li>
 * </ol>
 */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {
	@Inject(method = "onHitBlock", at = @At("HEAD"), cancellable = true)
	private void portalbow$onHitBlock(BlockHitResult hit, CallbackInfo info) {
		AbstractArrow arrow = (AbstractArrow) (Object) this;
		if (!(arrow.level() instanceof ServerLevel level)) {
			return;
		}

		Boolean opensOrange = arrow.getAttached(PortalBow.ARROW_OPENS_ORANGE);
		if (opensOrange != null) {
			Entity shooter = arrow.getOwner();
			if (shooter != null) {
				PortalPlacer.open(level, hit, shooter, opensOrange);
			}
			arrow.discard();
			info.cancel();
			return;
		}

		AABB nearHit = AABB.ofSize(hit.getLocation(), 0.6, 0.6, 0.6);
		for (PortalEntity portal : level.getEntitiesOfClass(PortalEntity.class, nearHit)) {
			if (portal.getFacing() == hit.getDirection()
					&& portal.isWithinOpening(hit.getLocation(), 0.0)
					&& portal.sendThrough(arrow)) {
				info.cancel();
				return;
			}
		}
	}
}
