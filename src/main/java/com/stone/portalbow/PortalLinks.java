package com.stone.portalbow;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

/**
 * The server's notebook of who owns which portals. It is saved with the world
 * (in data/portalbow/portals.dat), so portals stay linked after a restart and one portal can
 * find its partner even when the partner is far away in an unloaded part of the world.
 */
public class PortalLinks extends SavedData {
	/** Everything needed to arrive at a portal without the portal itself being loaded. */
	public record Spot(ResourceKey<Level> dimension, Vec3 pos, Direction facing, Direction up, UUID entity) {
		public static final Codec<Spot> CODEC = RecordCodecBuilder.create(i -> i.group(
				Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Spot::dimension),
				Vec3.CODEC.fieldOf("pos").forGetter(Spot::pos),
				Direction.CODEC.fieldOf("facing").forGetter(Spot::facing),
				Direction.CODEC.fieldOf("up").forGetter(Spot::up),
				UUIDUtil.STRING_CODEC.fieldOf("entity").forGetter(Spot::entity)
		).apply(i, Spot::new));
	}

	/** One shooter's two portals. Either may be missing. */
	public record Pair(Optional<Spot> orange, Optional<Spot> blue) {
		public static final Pair EMPTY = new Pair(Optional.empty(), Optional.empty());
		public static final Codec<Pair> CODEC = RecordCodecBuilder.create(i -> i.group(
				Spot.CODEC.optionalFieldOf("orange").forGetter(Pair::orange),
				Spot.CODEC.optionalFieldOf("blue").forGetter(Pair::blue)
		).apply(i, Pair::new));

		public Optional<Spot> get(boolean isOrange) {
			return isOrange ? this.orange : this.blue;
		}

		public Pair with(boolean isOrange, Optional<Spot> spot) {
			return isOrange ? new Pair(spot, this.blue) : new Pair(this.orange, spot);
		}
	}

	private static final Codec<PortalLinks> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Pair.CODEC)
			.xmap(PortalLinks::new, links -> links.pairs);

	// The game insists on a "data fixer" category for every saved file and crashes on null.
	// Ours has no old formats to upgrade, so we borrow a harmless existing category.
	private static final SavedDataType<PortalLinks> TYPE = new SavedDataType<>(
			PortalBow.id("portals"), PortalLinks::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<UUID, Pair> pairs;

	public PortalLinks() {
		this(Map.of());
	}

	private PortalLinks(Map<UUID, Pair> pairs) {
		this.pairs = new HashMap<>(pairs);
	}

	/** One notebook for the whole server, kept with the overworld's data. */
	public static PortalLinks get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public Optional<Spot> find(UUID owner, boolean isOrange) {
		return this.pairs.getOrDefault(owner, Pair.EMPTY).get(isOrange);
	}

	public void put(UUID owner, boolean isOrange, Spot spot) {
		this.pairs.put(owner, this.pairs.getOrDefault(owner, Pair.EMPTY).with(isOrange, Optional.of(spot)));
		this.setDirty();
	}

	/** Forgets a portal, but only if the notebook still points at that exact portal entity. */
	public void forget(UUID owner, boolean isOrange, UUID entity) {
		Pair pair = this.pairs.get(owner);
		if (pair == null || pair.get(isOrange).map(spot -> !spot.entity().equals(entity)).orElse(true)) {
			return;
		}
		Pair updated = pair.with(isOrange, Optional.empty());
		if (updated.equals(Pair.EMPTY)) {
			this.pairs.remove(owner);
		} else {
			this.pairs.put(owner, updated);
		}
		this.setDirty();
	}
}
