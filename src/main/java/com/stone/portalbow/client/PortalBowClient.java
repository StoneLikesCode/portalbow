package com.stone.portalbow.client;

import com.stone.portalbow.PortalBow;
import com.stone.portalbow.PortalViewPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

/**
 * The starting point for things only a player's game needs: here, how to draw a portal.
 * fabric.mod.json names this class under "entrypoints" as "client". A server never loads it.
 */
public class PortalBowClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(PortalBow.PORTAL, PortalRenderer::new);

		// The server's snapshots of what lies beyond each portal arrive here.
		ClientPlayNetworking.registerGlobalReceiver(PortalViewPayload.TYPE, (payload, context) -> PortalViews.receive(payload));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			PortalViews.tick();
			TrueViews.tick();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			PortalViews.clear();
			TrueViews.clear();
		});
	}
}
