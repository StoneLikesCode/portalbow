package com.stone.portalbow.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Builds two walls with a linked pair of portals and photographs one of them from a few angles.
 * In front of the exit portal: red wool to the west, blue wool to the east, a gold block in the
 * middle distance and a pig, so it is easy to tell whether the view is the right way round.
 */
public class PortalViewTest implements FabricClientGameTest {
	private static final String BOW = "weapon:{id:\"minecraft:bow\",count:1,components:{\"minecraft:enchantments\":{\"portalbow:portal\":1}}}";

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			// The test harness turns clouds off by default; players have them on.
			context.runOnClient(client -> client.options.cloudStatus().set(net.minecraft.client.CloudStatus.FANCY));
			String[] setup = {
				"gamemode creative @a",
				"time set noon",
				"gamerule advance_time false",
				"fill 0 100 0 40 100 20 minecraft:smooth_stone",
				"fill 5 101 10 9 105 10 minecraft:stone_bricks",
				"fill 20 101 10 24 105 10 minecraft:stone_bricks",
				"fill 20 101 4 20 103 4 minecraft:red_wool",
				"fill 24 101 4 24 103 4 minecraft:blue_wool",
				"setblock 22 101 1 minecraft:gold_block",
				"setblock 23 101 6 minecraft:torch",
				"summon minecraft:pig 21.5 101 7 {NoAI:1b}",
				"summon minecraft:armor_stand 0.5 101 0.5 {UUID:[I;0,1,0,1],NoGravity:1b,Invisible:1b}",
				"tp @a 7.5 101 5.5 0 0",
			};
			for (String command : setup) {
				world.getServer().runCommand(command);
			}
			context.waitTicks(10);
			world.getServer().runCommand("summon minecraft:arrow 7.5 102.5 7.0 {Owner:[I;0,1,0,1],Motion:[0.0d,0.0d,2.0d],NoGravity:1b," + BOW + "}");
			world.getServer().runCommand("summon minecraft:arrow 22.5 102.5 7.0 {Owner:[I;0,1,0,1],Motion:[0.0d,0.0d,2.0d],NoGravity:1b,"
					+ "\"fabric:attachments\":{\"portalbow:arrow_opens_orange\":0b}}");
			context.waitTicks(40);
			// Long enough for the server's snapshot to arrive and a shader pack to compile.
			context.waitTicks(Integer.getInteger("portalbow.test.settleTicks", 80));
			context.takeScreenshot("1_front");

			world.getServer().runCommand("tp @a 5.0 101 7.0 -40 0");
			context.waitTicks(30);
			context.takeScreenshot("2_angled");

			world.getServer().runCommand("tp @a 7.5 101 8.6 0 0");
			context.waitTicks(30);
			context.takeScreenshot("3_close");

			// Stand in front of the exit so the player's own body should appear in the view.
			world.getServer().runCommand("tp @a 22.5 101 6.0 0 0");
			context.waitTicks(30);
			context.takeScreenshot("4_at_exit");

			world.getServer().runCommand("tp @a 14.5 101 2.0 0 10");
			context.waitTicks(30);
			context.takeScreenshot("5_both");

			// Walk and turn with both portals in view. Moving makes the game rebuild its clouds
			// for each camera, which once crashed the Vulkan renderer.
			for (int step = 0; step < 40; step++) {
				world.getServer().runCommand("tp @a " + (11.5 + step * 0.15) + " 101 " + (2.0 - step * 0.02) + " " + (step - 20) + " 5");
				context.waitTicks(2);
			}
			world.getServer().runCommand("tp @a 14.5 101 1.0 0 5");
			context.waitTicks(20);
			context.takeScreenshot("6_after_moving");

			// Move the blue portal to a wall directly opposite the orange one and stand between
			// them. Looking into orange, you look out of blue, so you must see your own back.
			world.getServer().runCommand("fill 5 101 -1 9 105 -1 minecraft:stone_bricks");
			world.getServer().runCommand("summon minecraft:arrow 7.5 102.5 3.0 {Owner:[I;0,1,0,1],Motion:[0.0d,0.0d,-2.0d],NoGravity:1b,"
					+ "\"fabric:attachments\":{\"portalbow:arrow_opens_orange\":0b}}");
			world.getServer().runCommand("tp @a 7.5 101 5.0 0 0");
			context.waitTicks(60);
			context.takeScreenshot("7_see_myself");

			// Step aside so both facing portals are in view at once, and walk about. This is the
			// case that crashed the Vulkan renderer: three drawings of the world in one frame,
			// each with its camera under a different patch of cloud.
			for (int step = 0; step < 30; step++) {
				world.getServer().runCommand("tp @a " + (1.5 - step * 0.1) + " 101 " + (5.0 + (step % 5) * 0.2) + " -90 0");
				context.waitTicks(2);
			}
			context.takeScreenshot("8_both_facing");
		}
	}
}
