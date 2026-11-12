package com.stone.portalbow.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.Direction;

/** The handful of facts about a portal that the renderer needs in order to draw it. */
public class PortalRenderState extends EntityRenderState {
	public boolean orange = true;
	public Direction facing = Direction.NORTH;
	public Direction up = Direction.UP;
	/** How to draw the view of the other side, or null if there is none to show. */
	public RenderType view;
	/** How to draw the real, fully rendered view of the other side, or null if unavailable. */
	public RenderType trueView;
	/** True when this portal must not be drawn at all this time. */
	public boolean hidden;
}
