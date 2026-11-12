package com.stone.portalbow.client;

import com.stone.portalbow.PortalEntity;
import com.stone.portalbow.PortalViewPayload;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;

/** Keeps one {@link PortalView} for each portal the server has told us about. */
public final class PortalViews {
	private static final Int2ObjectMap<PortalView> VIEWS = new Int2ObjectOpenHashMap<>();

	private PortalViews() {
	}

	/** The server sent a new snapshot (or said the portal no longer leads anywhere). */
	static void receive(PortalViewPayload payload) {
		if (!payload.linked()) {
			PortalView old = VIEWS.remove(payload.entityId());
			if (old != null) {
				old.close();
			}
			return;
		}
		VIEWS.computeIfAbsent(payload.entityId(), PortalView::new).accept(payload);
	}

	/** How to draw the inside of this portal right now, or null to fall back to the plain swirl. */
	static RenderType renderTypeFor(PortalEntity portal) {
		PortalView view = VIEWS.get(portal.getId());
		if (view == null) {
			return null;
		}
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		return view.update(portal, camera);
	}

	/** Where this portal leads, or null if it is not linked (or we have not been told yet). */
	static PortalView.Exit exitOf(PortalEntity portal) {
		PortalView view = VIEWS.get(portal.getId());
		if (view == null) {
			return null;
		}
		// Being asked counts as being in use, so tick() does not throw the snapshot away while
		// the real view (which only needs to know where the exit is) is showing.
		view.ticksSinceSeen = 0;
		return view.exit();
	}

	/** Once a tick: throw away pictures for portals that have not been drawn for a while. */
	static void tick() {
		VIEWS.int2ObjectEntrySet().removeIf(entry -> {
			if (++entry.getValue().ticksSinceSeen > 200) {
				entry.getValue().close();
				return true;
			}
			return false;
		});
	}

	static void clear() {
		VIEWS.values().forEach(PortalView::close);
		VIEWS.clear();
	}
}
