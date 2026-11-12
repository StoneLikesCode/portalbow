package com.stone.portalbow.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.stone.portalbow.PortalBow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/** The picture of the world as really drawn through one portal, kept as an ordinary texture. */
final class TrueView {
	private final Identifier textureId;
	private final Handle handle = new Handle();
	private TextureTarget target;
	final RenderType renderType;
	/** When this picture was last redrawn (System.nanoTime). */
	long drawnAt;

	TrueView(int entityId) {
		this.textureId = PortalBow.id("portal_true_view/" + entityId);
		Minecraft.getInstance().getTextureManager().register(this.textureId, this.handle);
		// "Solid" so that the picture's own transparency (shader packs leave odd values there) is ignored.
		this.renderType = RenderTypes.entitySolid(this.textureId);
	}

	/** Copies what the game has just drawn on the main screen into this picture. */
	void capture(RenderTarget screen) {
		if (this.target == null) {
			this.target = new TextureTarget("portalbow true view", screen.width, screen.height,
					screen.getColorTexture().getFormat(), null);
		} else if (this.target.width != screen.width || this.target.height != screen.height) {
			this.target.resize(screen.width, screen.height);
		}
		this.handle.pointAt(this.target);
		this.target.copyColorFrom(screen);
		this.drawnAt = System.nanoTime();
	}

	boolean isFresh() {
		return this.target != null && System.nanoTime() - this.drawnAt < 500_000_000L;
	}

	void close() {
		Minecraft.getInstance().getTextureManager().release(this.textureId);
		if (this.target != null) {
			this.target.destroyBuffers();
			this.target = null;
		}
	}

	/** What the texture manager holds: it simply points at whatever our render target currently is. */
	private static final class Handle extends AbstractTexture {
		void pointAt(RenderTarget target) {
			this.texture = target.getColorTexture();
			this.textureView = target.getColorTextureView();
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}

		@Override
		public void close() {
			// The render target owns the real texture and frees it; nothing to do here.
			this.texture = null;
			this.textureView = null;
		}
	}
}
