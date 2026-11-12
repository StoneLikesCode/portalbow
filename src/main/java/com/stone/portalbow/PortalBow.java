package com.stone.portalbow;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * The starting point of the mod. fabric.mod.json names this class under "entrypoints" as "main".
 *
 * <p>How the pieces fit together:
 * <ul>
 *   <li>The enchantment itself is a data file: data/portalbow/enchantment/portal.json.</li>
 *   <li>When an arrow is fired from a bow with that enchantment, it is tagged here with the colour
 *       of portal it should open (orange, or blue if the shooter was sneaking).</li>
 *   <li>{@code AbstractArrowMixin} notices when a tagged arrow lands and calls {@link PortalPlacer}.</li>
 *   <li>{@link PortalEntity} is the portal: it draws itself and teleports whatever touches it.</li>
 *   <li>{@link PortalViewCapture} snapshots the blocks beyond a portal and sends them to nearby
 *       players, whose game draws them inside the portal (client/PortalView).</li>
 *   <li>{@link PortalLinks} remembers each shooter's pair of portals, even across restarts.</li>
 * </ul>
 */
public class PortalBow implements ModInitializer {
	public static final String MOD_ID = "portalbow";

	/** The enchantment defined in data/portalbow/enchantment/portal.json. */
	public static final ResourceKey<Enchantment> PORTAL_ENCHANTMENT =
			ResourceKey.create(Registries.ENCHANTMENT, id("portal"));

	private static final ResourceKey<EntityType<?>> PORTAL_KEY =
			ResourceKey.create(Registries.ENTITY_TYPE, id("portal"));

	public static final EntityType<PortalEntity> PORTAL = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			PORTAL_KEY,
			EntityType.Builder.<PortalEntity>of(PortalEntity::new, MobCategory.MISC)
					// The real hitbox is worked out in PortalEntity.makeBoundingBox, since it
					// depends on which way the portal faces. This is only a placeholder.
					.sized(1.0F, 1.0F)
					.fireImmune()
					.noSummon()
					.clientTrackingRange(10)
					// Portals never move, so there is no need to keep re-sending their position.
					.updateInterval(Integer.MAX_VALUE)
					.build(PORTAL_KEY)
	);

	/**
	 * Extra data stuck onto an arrow in flight: present only on arrows fired from a Portal bow.
	 * true means it will open an orange portal, false a blue one. Saved with the arrow.
	 */
	public static final AttachmentType<Boolean> ARROW_OPENS_ORANGE =
			AttachmentRegistry.createPersistent(id("arrow_opens_orange"), Codec.BOOL);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		// Declares the "what is beyond this portal" message so it can be sent to players.
		PayloadTypeRegistry.clientboundPlay().register(PortalViewPayload.TYPE, PortalViewPayload.CODEC);
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			if (entity instanceof PortalEntity portal) {
				portal.forgetViewer(player);
			}
		});

		// Runs whenever any entity appears in a world. We only care about freshly fired arrows.
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof AbstractArrow arrow
					&& !arrow.hasAttached(ARROW_OPENS_ORANGE)
					&& hasPortalEnchantment(arrow)) {
				Entity shooter = arrow.getOwner();
				boolean sneaking = shooter != null && shooter.isShiftKeyDown();
				arrow.setAttached(ARROW_OPENS_ORANGE, !sneaking);
			}
		});
	}

	private static boolean hasPortalEnchantment(AbstractArrow arrow) {
		ItemStack weapon = arrow.getWeaponItem();
		if (weapon == null || weapon.isEmpty()) {
			return false;
		}
		Holder<Enchantment> enchantment = arrow.registryAccess()
				.lookupOrThrow(Registries.ENCHANTMENT)
				.get(PORTAL_ENCHANTMENT)
				.orElse(null);
		return enchantment != null && EnchantmentHelper.getItemEnchantmentLevel(enchantment, weapon) > 0;
	}
}
