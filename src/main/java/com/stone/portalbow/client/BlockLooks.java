package com.stone.portalbow.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.stone.portalbow.mixin.SpriteContentsAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Works out, once per kind of block, what it looks like in a portal view: a small 16x16 picture
 * for each of its six sides (taken from the block's real textures, so resource packs are
 * respected) and the box it fills within its cell (a slab fills only half, for example).
 */
public final class BlockLooks {
	/** What one block state looks like. Pixels are colours as 0xAARRGGBB. */
	public static final class Look {
		/** Nothing to draw: rays pass straight through. */
		boolean invisible;
		/** Fills its whole cell. */
		boolean fullCube = true;
		/** Glows by itself (lava), so it ignores how dark its surroundings are. */
		boolean glows;
		/** One 16x16 picture per side, indexed by Direction.ordinal(). */
		final int[][] pixels = new int[6][];
		/** The box it fills inside its cell, each from 0 to 1: minX, minY, minZ, maxX, maxY, maxZ. */
		final double[] box = {0, 0, 0, 1, 1, 1};
	}

	private static final Look INVISIBLE = new Look();
	private static final Int2ObjectOpenHashMap<Look> CACHE = new Int2ObjectOpenHashMap<>();
	private static BlockStateModelSet cachedFor;

	static {
		INVISIBLE.invisible = true;
	}

	private BlockLooks() {
	}

	public static Look get(int stateId) {
		// Changing resource packs rebuilds the game's block models; our pictures must follow.
		BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		if (models != cachedFor) {
			CACHE.clear();
			cachedFor = models;
		}
		Look look = CACHE.get(stateId);
		if (look == null) {
			try {
				look = build(Block.stateById(stateId), models);
			} catch (RuntimeException e) {
				look = INVISIBLE; // An unusual modded block we cannot make sense of: leave it out.
			}
			CACHE.put(stateId, look);
		}
		return look;
	}

	private static Look build(BlockState state, BlockStateModelSet models) {
		if (state.isAir()) {
			return INVISIBLE;
		}
		Look look = new Look();

		if (state.getBlock() instanceof LiquidBlock) {
			boolean lava = state.getFluidState().is(FluidTags.LAVA);
			int[] flat = flat(lava ? 0xFFD8650F : 0xFF3A6FD9, lava ? 40 : 14);
			for (int side = 0; side < 6; side++) {
				look.pixels[side] = flat;
			}
			look.glows = lava;
			look.fullCube = false;
			look.box[4] = 0.875; // Liquid surfaces sit a little below the top of the block.
			return look;
		}

		VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		if (shape.isEmpty()) {
			return INVISIBLE;
		}
		AABB bounds = shape.bounds();
		look.box[0] = bounds.minX;
		look.box[1] = bounds.minY;
		look.box[2] = bounds.minZ;
		look.box[3] = bounds.maxX;
		look.box[4] = bounds.maxY;
		look.box[5] = bounds.maxZ;
		look.fullCube = bounds.minX <= 0.001 && bounds.minY <= 0.001 && bounds.minZ <= 0.001
				&& bounds.maxX >= 0.999 && bounds.maxY >= 0.999 && bounds.maxZ >= 0.999;

		BlockStateModel model = models.get(state);
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(42L), parts);

		int fallbackColor = 0xFF000000 | state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
		for (Direction side : Direction.values()) {
			BakedQuad quad = findQuad(parts, side);
			TextureAtlasSprite sprite = quad != null ? quad.materialInfo().sprite() : model.particleMaterial().sprite();
			int tint = -1;
			if (quad != null && quad.materialInfo().isTinted()) {
				BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, quad.materialInfo().tintIndex());
				if (source != null) {
					tint = source.color(state);
				}
			} else if (quad == null) {
				tint = anyTint(parts, state);
			}
			int[] pixels = readSprite(sprite, tint);
			look.pixels[side.ordinal()] = pixels != null ? pixels : flat(fallbackColor, 0);
		}
		return look;
	}

	/** The first flat piece of the model that faces this side. */
	private static BakedQuad findQuad(List<BlockStateModelPart> parts, Direction side) {
		for (BlockStateModelPart part : parts) {
			List<BakedQuad> quads = part.getQuads(side);
			if (!quads.isEmpty()) {
				return quads.getFirst();
			}
		}
		for (BlockStateModelPart part : parts) {
			for (BakedQuad quad : part.getQuads(null)) {
				if (quad.direction() == side) {
					return quad;
				}
			}
		}
		return null;
	}

	/** For plants and the like, whose models have no proper sides: the colour (e.g. grass green) they are tinted. */
	private static int anyTint(List<BlockStateModelPart> parts, BlockState state) {
		for (BlockStateModelPart part : parts) {
			for (BakedQuad quad : part.getQuads(null)) {
				if (quad.materialInfo().isTinted()) {
					BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, quad.materialInfo().tintIndex());
					return source != null ? source.color(state) : -1;
				}
			}
		}
		return -1;
	}

	/** Shrinks a block texture (first animation frame) to 16x16 and applies a tint colour. */
	private static int[] readSprite(TextureAtlasSprite sprite, int tint) {
		SpriteContents contents = sprite.contents();
		NativeImage image = ((SpriteContentsAccessor) contents).portalbow$getOriginalImage();
		if (image == null) {
			return null;
		}
		int width = Math.min(contents.width(), image.getWidth());
		int height = Math.min(contents.height(), image.getHeight());
		int tintR = tint >> 16 & 0xFF;
		int tintG = tint >> 8 & 0xFF;
		int tintB = tint & 0xFF;
		int[] pixels = new int[256];
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int argb = image.getPixel(x * width / 16, y * height / 16);
				if (tint != -1) {
					argb = argb & 0xFF000000
							| (argb >> 16 & 0xFF) * tintR / 255 << 16
							| (argb >> 8 & 0xFF) * tintG / 255 << 8
							| (argb & 0xFF) * tintB / 255;
				}
				pixels[y * 16 + x] = argb;
			}
		}
		return pixels;
	}

	/** A plain-coloured picture with a little speckle so large areas are not perfectly flat. */
	private static int[] flat(int argb, int speckle) {
		int[] pixels = new int[256];
		RandomSource random = RandomSource.create(argb);
		for (int i = 0; i < 256; i++) {
			int d = speckle == 0 ? 0 : random.nextInt(speckle) - speckle / 2;
			int r = Math.clamp((argb >> 16 & 0xFF) + d, 0, 255);
			int g = Math.clamp((argb >> 8 & 0xFF) + d, 0, 255);
			int b = Math.clamp((argb & 0xFF) + d, 0, 255);
			pixels[i] = 0xFF000000 | r << 16 | g << 8 | b;
		}
		return pixels;
	}
}
