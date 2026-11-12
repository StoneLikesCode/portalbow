package com.stone.portalbow;

import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A message from the server to a player's game: "here is what lies beyond this portal".
 *
 * <p>It carries a block-by-block snapshot of the space in front of the exit portal (see
 * {@link PortalViewCapture} for its layout). The player's game turns that into the picture
 * shown inside the portal. If {@code linked} is false the portal has no partner yet.
 *
 * @param exitDimension which world the exit portal is in
 * @param exitPos   the middle of the exit portal
 * @param entityId  network id of the portal the player is looking at
 * @param states    one block-state id per cell of the snapshot
 * @param light     how bright each cell is, 0 to 15
 * @param skyColor  colour to show where the view runs out of blocks
 */
public record PortalViewPayload(int entityId, boolean linked, ResourceKey<Level> exitDimension, Vec3 exitPos,
		Direction exitFacing, Direction exitUp, int skyColor, int[] states, byte[] light) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<PortalViewPayload> TYPE =
			new CustomPacketPayload.Type<>(PortalBow.id("portal_view"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PortalViewPayload> CODEC =
			CustomPacketPayload.codec(PortalViewPayload::write, PortalViewPayload::new);

	public static PortalViewPayload unlinked(int entityId) {
		return new PortalViewPayload(entityId, false, Level.OVERWORLD, Vec3.ZERO, Direction.NORTH, Direction.UP, 0, new int[0], new byte[0]);
	}

	private PortalViewPayload(FriendlyByteBuf buffer) {
		this(buffer.readVarInt(), buffer.readBoolean(), buffer.readResourceKey(Registries.DIMENSION),
				new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readEnum(Direction.class), buffer.readEnum(Direction.class),
				buffer.readInt(), buffer.readVarIntArray(PortalViewCapture.CELLS), buffer.readByteArray(PortalViewCapture.CELLS));
	}

	private void write(FriendlyByteBuf buffer) {
		buffer.writeVarInt(this.entityId);
		buffer.writeBoolean(this.linked);
		buffer.writeResourceKey(this.exitDimension);
		buffer.writeDouble(this.exitPos.x);
		buffer.writeDouble(this.exitPos.y);
		buffer.writeDouble(this.exitPos.z);
		buffer.writeEnum(this.exitFacing);
		buffer.writeEnum(this.exitUp);
		buffer.writeInt(this.skyColor);
		buffer.writeVarIntArray(this.states);
		buffer.writeByteArray(this.light);
	}

	@Override
	public CustomPacketPayload.Type<PortalViewPayload> type() {
		return TYPE;
	}
}
