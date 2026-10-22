package com.seolwoni.buildassist.gametest;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.seolwoni.buildassist.build.Builder;
import com.seolwoni.buildassist.schematic.Materials;
import com.seolwoni.buildassist.schematic.Schematic;
import com.seolwoni.buildassist.schematic.States;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stage 2: building the small test house on a stone field, survival, peaceful. Things are in the way, as on a real
 * spot: a dirt pillar and a flower inside, a stone block where the roof goes, a hole under the floor. The player has
 * the materials and tools. Afterwards every cell of the plan is checked (the chest, which has a direction, is left for
 * stage 3) and a picture of the whole house is taken.
 */
public class BuildFlatTest implements FabricClientGameTest {
	private final List<String> log = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!RealWorld.suite().equals("build-flat")) return;
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			RealWorld.cmd(sp, "gamerule spawn_mobs false");
			RealWorld.cmd(sp, "gamerule advance_time false");
			RealWorld.cmd(sp, "time set noon");
			RealWorld.cmd(sp, "difficulty peaceful");
			RealWorld.cmd(sp, "forceload add -32 -32 32 32");
			RealWorld.cmd(sp, "fill -24 99 -24 24 99 24 minecraft:stone");
			RealWorld.cmd(sp, "fill -24 100 -24 24 116 24 minecraft:air");
			RealWorld.cmd(sp, "fill -24 98 -24 24 98 24 minecraft:stone");
			RealWorld.cmd(sp, "kill @e[type=!player]");
			RealWorld.cmd(sp, "gamemode survival @a");
			RealWorld.cmd(sp, "clear @a");

			Schematic plan;
			try (InputStream in = BuildFlatTest.class.getResourceAsStream("/schematics/test-house.litematic")) {
				plan = Schematic.read(in, "test-house.litematic");
			}
			BlockPos o = new BlockPos(0, 100, 0);
			// In the way: a dirt pillar and a poppy inside, stone where the roof goes, a hole under the floor.
			RealWorld.cmd(sp, "fill 2 100 2 2 102 2 minecraft:dirt");
			RealWorld.cmd(sp, "setblock 4 100 3 minecraft:poppy");
			RealWorld.cmd(sp, "setblock 1 104 1 minecraft:stone");
			RealWorld.cmd(sp, "setblock 4 99 3 minecraft:air");
			RealWorld.cmd(sp, "setblock 5 99 1 minecraft:air");

			// Materials (all of them), a few filler blocks, tools.
			Materials m = Materials.of(plan);
			for (Map.Entry<String, Integer> e : m.items.entrySet()) RealWorld.cmd(sp, "give @a " + e.getKey() + " " + e.getValue());
			RealWorld.cmd(sp, "give @a minecraft:dirt 16");
			RealWorld.cmd(sp, "give @a minecraft:diamond_pickaxe");
			RealWorld.cmd(sp, "give @a minecraft:diamond_shovel");
			RealWorld.cmd(sp, "give @a minecraft:diamond_axe");
			RealWorld.cmd(sp, "effect give @a minecraft:saturation infinite 0 true");
			// The player stands where the house's corner goes, looking into it, as when pressing "build here".
			RealWorld.cmd(sp, "tp @a 0.5 100 0.5 -45 20");
			context.waitTicks(40);
			sp.getConnection().waitForChunksRender();

			final Schematic fp = plan;
			String err = context.computeOnClient(mc -> Builder.start(mc, fp, o));
			if (err != null) failures.add("start: " + err);
			context.takeScreenshot("build-flat-00-start");
			int t = 0, shots = 1;
			final int limit = 20 * 60 * 10;
			boolean running = true;
			while (running && t < limit) {
				context.waitTicks(40);
				t += 40;
				final int tt = t;
				log.add(context.computeOnClient(mc -> "t=" + tt + " pos=" + mc.player.blockPosition().toShortString() + " placed=" + Builder.placed + " broken=" + Builder.broken
						+ " status=" + Builder.status));
				running = context.computeOnClient(mc -> Builder.running);
				if (t % 400 == 0) context.takeScreenshot(String.format("build-flat-%02d-s%d", shots++, t / 20));
			}
			String stop = context.computeOnClient(mc -> Builder.lastStop);
			log.add("stopped after " + t + " ticks (" + t / 20 + " s): " + stop);
			log.add("missing: " + context.computeOnClient(mc -> new TreeMap<>(Builder.missing)));
			if (running) {
				failures.add("did not finish within " + limit / 20 + " s");
				context.runOnClient(mc -> Builder.stop(null));
			}

			// Every cell: plain blocks as planned, air where the plan has air. The chest (it has a direction) is for later.
			List<String> wrong = sp.getServer().computeOnServer(server -> {
				List<String> out = new ArrayList<>();
				var lv = server.overworld();
				BlockState[] st = States.all(fp, null);
				for (int y = 0; y < fp.sizeY; y++) for (int z = 0; z < fp.sizeZ; z++) for (int x = 0; x < fp.sizeX; x++) {
					BlockState want = st[fp.cells[fp.index(x, y, z)]];
					BlockPos w = o.offset(x, y, z);
					BlockState now = lv.getBlockState(w);
					String id = BuiltInRegistries.BLOCK.getKey(want.getBlock()).getPath();
					if (id.equals("chest")) continue;
					boolean ok = want.isAir() ? now.isAir() : same(now, want);
					if (!ok) out.add(w.toShortString() + " is " + now + ", plan " + want);
				}
				// Under the floor: the holes filled.
				for (int[] h : new int[][] {{4, 3}, {5, 1}}) if (lv.getBlockState(o.offset(h[0], -1, h[1])).isAir()) out.add("hole under the floor at " + h[0] + "," + h[1] + " not filled");
				return out;
			});
			log.add("wrong cells: " + wrong.size());
			for (String w : wrong.subList(0, Math.min(25, wrong.size()))) log.add("  " + w);
			if (!wrong.isEmpty()) failures.add(wrong.size() + " cells not as planned");
			log.add(context.computeOnClient(mc -> "placed=" + Builder.placed + " broken=" + Builder.broken + " filled=" + Builder.filled + " skipped=" + Builder.skipped));
			log.add(com.seolwoni.assistpath.AssistPath.Stats.summary());

			// A picture of the whole house: from a corner outside, a little above, no HUD.
			double cx = o.getX() + fp.sizeX / 2.0, cy = o.getY() + fp.sizeY / 2.0, cz = o.getZ() + fp.sizeZ / 2.0;
			double dist = Math.max(fp.sizeX, Math.max(fp.sizeY, fp.sizeZ)) * 1.6 + 4;
			double vx = cx - dist * 0.7, vz = cz - dist * 0.7, vy = cy + dist * 0.45;
			float yaw = (float) Math.toDegrees(Math.atan2(-(cx - vx), cz - vz));
			float pitch = (float) Math.toDegrees(Math.atan2(vy - cy, Math.hypot(cx - vx, cz - vz)));
			RealWorld.cmd(sp, "gamemode spectator @a");
			RealWorld.cmd(sp, String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", vx, vy - 1.62, vz, yaw, pitch));
			context.runOnClient(mc -> hideGui(mc, true));
			// Long enough for the chat line about the game mode to fade.
			context.waitTicks(240);
			sp.getConnection().waitForChunksRender();
			context.takeScreenshot("build-flat-99-done");
			context.runOnClient(mc -> hideGui(mc, false));
		} catch (Throwable th) {
			failures.add("exception: " + th);
		} finally {
			RealWorld.writeReport("buildassist-build-flat", failures, log);
		}
		if (!failures.isEmpty()) throw new AssertionError("Build Assist build-flat failed: " + String.join("; ", failures));
	}

	/** The F1 switch (no HUD), wherever this game version keeps it. */
	static void hideGui(net.minecraft.client.Minecraft mc, boolean on) {
		for (Object o : new Object[] {mc.options, mc.gui, mc}) {
			for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				for (var f : c.getDeclaredFields()) {
					if (!f.getName().equals("hideGui") || f.getType() != boolean.class) continue;
					try {
						f.setAccessible(true);
						f.setBoolean(o, on);
						return;
					} catch (Exception ignored) {
					}
				}
			}
		}
	}

	/** Same block, same properties except the ones the game sets itself. */
	private static boolean same(BlockState now, BlockState want) {
		if (now.getBlock() != want.getBlock()) return false;
		for (var p : want.getProperties()) {
			String n = p.getName();
			if (n.equals("distance") || n.equals("persistent") || n.equals("north") || n.equals("south") || n.equals("east") || n.equals("west") || n.equals("up")
					|| n.equals("waterlogged") || n.equals("snowy")) continue;
			if (!now.getValue(p).equals(want.getValue(p))) return false;
		}
		return true;
	}
}
