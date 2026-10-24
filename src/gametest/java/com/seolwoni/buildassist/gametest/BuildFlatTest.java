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
		String suite = RealWorld.suite();
		com.seolwoni.assistcore.i18n.Tr.korean = true; // the checks below are written against the Korean
		// build-flat: the small test house (everything checked). The others: the player's plans, built as far as this
		// stage can (blocks with a direction, and parts out of reach, are left and counted).
		// "-b": the same plan once more (a second run shows how much results vary from run to run).
		String plan0 = suite.endsWith("-b") ? suite.substring(0, suite.length() - 2) : suite;
		String file = switch (plan0) {
			case "build-flat", "build-flat-turned", "build-chest" -> "test-house.litematic";
			case "build-crane" -> "crane.schem";
			case "build-pine" -> "pine-small.litematic";
			case "build-pine-large" -> "pine-large.litematic";
			case "build-tree-farm" -> "tree-farm.litematic";
			case "build-hill-house" -> "hill-house.schem";
			default -> null;
		};
		if (file == null) return;
		// "build-chest": the small house with its materials in a marked chest beside it, none in the bag.
		boolean fromChest = suite.equals("build-chest");
		boolean own = suite.startsWith("build-flat") || fromChest;
		// "-turned": the same house turned a quarter clockwise and mirrored left to right (as chosen when placing it).
		boolean turned = suite.equals("build-flat-turned");
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			RealWorld.cmd(sp, "gamerule spawn_mobs false");
			RealWorld.cmd(sp, "gamerule advance_time false");
			RealWorld.cmd(sp, "time set noon");
			RealWorld.cmd(sp, "difficulty peaceful");
			RealWorld.cmd(sp, "forceload add -48 -48 48 48");
			for (int x0 = -44; x0 < 44; x0 += 22) {
				// In parts: one fill may change at most 32768 blocks.
				RealWorld.cmd(sp, "fill " + x0 + " 98 -44 " + (x0 + 21) + " 99 43 minecraft:stone");
				for (int y0 = 100; y0 < 160; y0 += 8) RealWorld.cmd(sp, "fill " + x0 + " " + y0 + " -44 " + (x0 + 21) + " " + (y0 + 7) + " 43 minecraft:air");
			}
			RealWorld.cmd(sp, "kill @e[type=!player]");
			RealWorld.cmd(sp, "gamemode survival @a");
			RealWorld.cmd(sp, "clear @a");

			Schematic plan;
			try (InputStream in = BuildFlatTest.class.getResourceAsStream("/schematics/" + file)) {
				plan = Schematic.read(in, file);
			}
			if (turned) {
				final Schematic base = plan;
				plan = context.computeOnClient(mc -> States.turned(base, 1, true));
			}
			BlockPos o = own ? new BlockPos(0, 100, 0) : new BlockPos(-plan.sizeX / 2, 100, -plan.sizeZ / 2);
			log.add("plan " + file + ": " + plan + " at " + o.toShortString());
			if (own && !turned) {
				// In the way: a dirt pillar and a poppy inside, stone where the roof goes, a hole under the floor.
				RealWorld.cmd(sp, "fill 2 100 2 2 102 2 minecraft:dirt");
				RealWorld.cmd(sp, "setblock 4 100 3 minecraft:poppy");
				RealWorld.cmd(sp, "setblock 1 104 1 minecraft:stone");
				RealWorld.cmd(sp, "setblock 4 99 3 minecraft:air");
				RealWorld.cmd(sp, "setblock 5 99 1 minecraft:air");
			}

			// Materials (all of them), a few filler blocks, tools.
			// Tools and filler first, so they always fit: a plan with many kinds of blocks fills the rest of the bag
			// (what does not fit is dropped and shows as missing). Dug-out filler is picked up again, so 8 stacks do.
			Materials m = Materials.of(plan);
			RealWorld.cmd(sp, "give @a minecraft:diamond_pickaxe");
			RealWorld.cmd(sp, "give @a minecraft:diamond_shovel");
			RealWorld.cmd(sp, "give @a minecraft:diamond_axe");
			// Tall plans need towers and walkways band after band: 256 ran out at the big pine's 27th layer ("not enough
			// filler for a tower"), and a player building one would bring more.
			RealWorld.cmd(sp, "give @a minecraft:dirt " + (own ? 64 : 512));
			log.add("materials: about " + m.stacks() + " stacks");
			// More than the bag holds (the tree farm: 28 kinds): all of it in marked chests beside the plan, as a
			// player would keep it; the builder fetches what the plan needs next. (Given into the bag, what did not fit
			// was dropped, and the test counted it as missing: 71 stripped spruce logs.)
			boolean chests = fromChest || m.stacks() > 26;
			if (chests) {
				context.runOnClient(mc -> com.seolwoni.buildassist.build.Supply.chests().clear());
				int slot = 27, chest = -1, total = 0;
				BlockPos cp = null;
				for (Map.Entry<String, Integer> e : m.items.entrySet()) {
					int size = com.seolwoni.buildassist.schematic.Materials.stackSize(e.getKey());
					for (int left = e.getValue(); left > 0; left -= size) {
						if (slot >= 27) {
							chest++;
							slot = 0;
							cp = o.offset(-4, 0, 2 + 2 * chest);
							RealWorld.cmd(sp, "setblock " + cp.getX() + " " + cp.getY() + " " + cp.getZ() + " minecraft:chest");
							final BlockPos at = cp;
							context.runOnClient(mc -> {
								var ce = new com.seolwoni.assistcore.storage.ChestEntry();
								ce.x = at.getX();
								ce.y = at.getY();
								ce.z = at.getZ();
								com.seolwoni.buildassist.build.Supply.chests().add(ce);
							});
						}
						RealWorld.cmd(sp, "item replace block " + cp.getX() + " " + cp.getY() + " " + cp.getZ() + " container." + slot++ + " with " + e.getKey() + " " + Math.min(size, left));
						total++;
					}
				}
				log.add("materials in " + (chest + 1) + " chest(s) beside the plan (" + total + " stacks)");
			} else {
				context.runOnClient(mc -> com.seolwoni.buildassist.build.Supply.chests().clear());
				for (Map.Entry<String, Integer> e : m.items.entrySet()) RealWorld.cmd(sp, "give @a " + e.getKey() + " " + e.getValue());
			}
			context.waitTicks(5);
			RealWorld.cmd(sp, "kill @e[type=item]");
			RealWorld.cmd(sp, "effect give @a minecraft:saturation infinite 0 true");
			// The player stands where the plan's corner goes, looking into it, as when pressing "build here".
			RealWorld.cmd(sp, "tp @a " + (o.getX() + 0.5) + " 100 " + (o.getZ() + 0.5) + " -45 20");
			context.waitTicks(40);
			sp.getConnection().waitForChunksRender();

			final Schematic fp = plan;
			if (own && !turned) {
				// Choosing the spot from the sky: the view leaves the character, the plan shows as a see-only copy,
				// turning works, and closing puts everything back as it really is.
				context.runOnClient(mc -> mc.gui.setScreen(new com.seolwoni.buildassist.gui.PlaceScreen(null, fp)));
				context.waitTicks(40);
				context.takeScreenshot(suite + "-00-place");
				log.add(context.computeOnClient(mc -> {
					var ps = (com.seolwoni.buildassist.gui.PlaceScreen) mc.gui.screen();
					return "place screen: sky view " + ps.skyView() + ", see-only blocks " + ps.ghostCount() + ", at " + ps.origin().toShortString();
				}));
				if (!context.computeOnClient(mc -> mc.gui.screen() instanceof com.seolwoni.buildassist.gui.PlaceScreen ps && ps.skyView() && ps.ghostCount() > 0))
					failures.add("place screen: no sky view or no see-only copy");
				context.runOnClient(mc -> ((com.seolwoni.buildassist.gui.PlaceScreen) mc.gui.screen()).turnForTest());
				context.waitTicks(20);
				context.takeScreenshot(suite + "-00-place-turned");
				final BlockPos po = context.computeOnClient(mc -> ((com.seolwoni.buildassist.gui.PlaceScreen) mc.gui.screen()).origin());
				final Schematic pp = context.computeOnClient(mc -> ((com.seolwoni.buildassist.gui.PlaceScreen) mc.gui.screen()).plan());
				context.runOnClient(mc -> mc.gui.screen().onClose());
				context.waitTicks(5);
				boolean back = context.computeOnClient(mc -> mc.getCameraEntity() == mc.player && mc.gui.screen() == null);
				if (!back) failures.add("place screen: view not back on the character");
				int clientSolid = context.computeOnClient(mc -> {
					int n = 0;
					for (BlockPos q : BlockPos.betweenClosed(po, po.offset(pp.sizeX - 1, pp.sizeY - 1, pp.sizeZ - 1))) if (!mc.level.getBlockState(q).isAir()) n++;
					return n;
				});
				int serverSolid = sp.getServer().computeOnServer(server -> {
					int n = 0;
					for (BlockPos q : BlockPos.betweenClosed(po, po.offset(pp.sizeX - 1, pp.sizeY - 1, pp.sizeZ - 1))) if (!server.overworld().getBlockState(q).isAir()) n++;
					return n;
				});
				log.add("after closing: blocks in that box client " + clientSolid + ", server " + serverSolid);
				if (clientSolid != serverSolid) failures.add("place screen: see-only blocks left behind (" + clientSolid + " vs " + serverSolid + ")");
			}
			String err = context.computeOnClient(mc -> Builder.start(mc, fp, o));
			if (err != null) failures.add("start: " + err);
			context.takeScreenshot(suite + "-00-start");
			int t = 0, shots = 1;
			// Big plans (the tree farm: 765 blocks, the big pine: 442) finish building in about 18 minutes and then
			// need the time to take their temporary blocks down: 40 minutes for them (the step itself may run 55).
			final int limit = 20 * 60 * (own ? 10 : fp.solidCount() > 300 ? 40 : 25);
			boolean running = true;
			float minHealth = 20;
			while (running && t < limit) {
				context.waitTicks(40);
				t += 40;
				final int tt = t;
				log.add(context.computeOnClient(mc -> "t=" + tt + " pos=" + mc.player.blockPosition().toShortString() + " placed=" + Builder.placed + " broken=" + Builder.broken
						+ " status=" + Builder.status));
				running = context.computeOnClient(mc -> Builder.running);
				minHealth = Math.min(minHealth, context.computeOnClient(mc -> mc.player.getHealth()));
				if (t % (own ? 400 : 2400) == 0) context.takeScreenshot(String.format("%s-%02d-s%d", suite, shots++, t / 20));
			}
			String stop = context.computeOnClient(mc -> Builder.lastStop);
			if (running) {
				failures.add("did not finish within " + limit / 20 + " s");
				context.runOnClient(mc -> Builder.stop(null));
			}
			if (own && !turned) {
				// The timelapse: pictures while building, then a GIF of them.
				for (int i = 0; i < 40 && !context.computeOnClient(mc -> com.seolwoni.buildassist.build.Timelapse.gifDone); i++) context.waitTicks(10);
				int frames = context.computeOnClient(mc -> com.seolwoni.buildassist.build.Timelapse.frames());
				java.nio.file.Path gif = context.computeOnClient(mc -> com.seolwoni.buildassist.build.Timelapse.lastGif);
				log.add("timelapse: " + frames + " pictures, gif " + (gif == null ? "none" : gif.getFileName() + " " + java.nio.file.Files.size(gif) / 1024 + " KB"));
				if (frames < 5) failures.add("timelapse: only " + frames + " pictures");
				if (gif == null) failures.add("timelapse: no GIF");
				java.nio.file.Path tdir = context.computeOnClient(mc -> com.seolwoni.buildassist.build.Timelapse.folder());
				if (tdir != null) {
					java.nio.file.Path shotDir = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("screenshots"));
					java.nio.file.Files.createDirectories(shotDir);
					if (gif != null) java.nio.file.Files.copy(gif, shotDir.resolve(suite + "-timelapse.gif"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					java.nio.file.Path dir = tdir;
					java.nio.file.Path mid = dir.resolve(String.format("frame-%04d.png", Math.max(1, frames / 2)));
					if (java.nio.file.Files.exists(mid)) java.nio.file.Files.copy(mid, shotDir.resolve(suite + "-timelapse-mid.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
			log.add("lowest health " + minHealth);
			if (minHealth < 14) failures.add("got hurt (lowest health " + minHealth + ")");
			log.add("stopped after " + t + " ticks (" + t / 20 + " s): " + stop);
			log.add("missing: " + context.computeOnClient(mc -> new TreeMap<>(Builder.missing)));

			if (own && !turned) {
				// The worker's journal: this job written down, with what it used, and shown on its screen.
				var entries = context.computeOnClient(mc -> com.seolwoni.assistcore.log.Journal.entries("build"));
				if (entries.isEmpty()) failures.add("journal: no entry");
				else {
					var e = entries.get(0);
					log.add("journal: " + entries.size() + " entries, last: " + e.reason + " | used " + e.used + " | got " + e.got + " | hurt " + e.hurt + ", falls " + e.falls);
					if (e.used.isEmpty()) failures.add("journal: nothing used");
				}
				context.runOnClient(mc -> mc.gui.setScreen(new com.seolwoni.assistcore.gui.JournalScreen(null, "build")));
				context.waitTicks(20);
				context.takeScreenshot(suite + "-journal");
				context.runOnClient(mc -> mc.gui.setScreen(null));
			}
			// Every cell: blocks as planned, air where the plan has air. Blocks with a direction are for later stages.
			final boolean ownPlan = own;
			List<String> wrong = sp.getServer().computeOnServer(server -> {
				List<String> out = new ArrayList<>();
				var lv = server.overworld();
				BlockState[] st = States.all(fp, null);
				int planned = 0, done = 0, plainPlanned = 0, plainDone = 0;
				for (int y = 0; y < fp.sizeY; y++) for (int z = 0; z < fp.sizeZ; z++) for (int x = 0; x < fp.sizeX; x++) {
					BlockState want = st[fp.cells[fp.index(x, y, z)]];
					BlockPos w = o.offset(x, y, z);
					BlockState now = lv.getBlockState(w);
					if (want == null || want.isAir()) {
						if (!now.isAir()) out.add(w.toShortString() + " is " + now + ", plan air");
						continue;
					}
					planned++;
					boolean ok = same(now, want);
					if (ok) done++;
					if (com.seolwoni.buildassist.build.Builder.plainForTest(want)) {
						plainPlanned++;
						if (ok) plainDone++;
						else if (ownPlan || out.size() < 40) out.add(w.toShortString() + " is " + now + ", plan " + want);
					}
				}
				out.add(0, "SUMMARY blocks as planned " + done + "/" + planned + ", of the blocks this stage builds " + plainDone + "/" + plainPlanned);
				if (ownPlan && !turned) for (int[] h : new int[][] {{4, 3}, {5, 1}}) if (lv.getBlockState(o.offset(h[0], -1, h[1])).isAir()) out.add("hole under the floor at " + h[0] + "," + h[1] + " not filled");
				return out;
			});
			log.add(wrong.remove(0));
			log.add("wrong cells: " + wrong.size());
			for (String w : wrong.subList(0, Math.min(25, wrong.size()))) log.add("  " + w);
			if (own && !wrong.isEmpty()) failures.add(wrong.size() + " cells not as planned");
			// Temporary blocks left standing (dirt where the plan has nothing, around it too).
			int leftDirt = sp.getServer().computeOnServer(server -> {
				int n = 0;
				var lv = server.overworld();
				for (BlockPos q : BlockPos.betweenClosed(o.offset(-6, 0, -6), o.offset(fp.sizeX + 6, fp.sizeY + 4, fp.sizeZ + 6))) {
					if (!lv.getBlockState(q).is(net.minecraft.world.level.block.Blocks.DIRT)) continue;
					int x = q.getX() - o.getX(), y = q.getY() - o.getY(), z = q.getZ() - o.getZ();
					String want = fp.at(x, y, z);
					if (!want.startsWith("minecraft:dirt")) n++;
				}
				return n;
			});
			log.add("temporary blocks left standing: " + leftDirt);
			if (leftDirt > 0) failures.add(leftDirt + " temporary blocks left standing");
			log.add(context.computeOnClient(mc -> "scaffold: " + Builder.scaffoldSummary()));
			if (fromChest && context.computeOnClient(mc -> Builder.scaffoldSummary()).startsWith("supply trips=0")) failures.add("no trip to the material chest");
			for (String e : context.computeOnClient(mc -> Builder.scaffoldLog())) log.add("  event " + e);
			log.add(context.computeOnClient(mc -> "placed=" + Builder.placed + " broken=" + Builder.broken + " filled=" + Builder.filled + " skipped=" + Builder.skipped));
			log.add(com.seolwoni.assistpath.AssistPath.Stats.summary());

			// A picture of the whole house: from a corner outside, a little above, no HUD.
			double cx = o.getX() + fp.sizeX / 2.0, cy = o.getY() + fp.sizeY / 2.0, cz = o.getZ() + fp.sizeZ / 2.0;
			double dist = Math.max(fp.sizeX, Math.max(fp.sizeY, fp.sizeZ)) * 1.15 + 3;
			double vx = cx - dist * 0.7, vz = cz - dist * 0.7, vy = cy + dist * 0.45;
			float yaw = (float) Math.toDegrees(Math.atan2(-(cx - vx), cz - vz));
			float pitch = (float) Math.toDegrees(Math.atan2(vy - cy, Math.hypot(cx - vx, cz - vz)));
			RealWorld.cmd(sp, "gamemode spectator @a");
			RealWorld.cmd(sp, String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", vx, vy - 1.62, vz, yaw, pitch));
			context.runOnClient(mc -> hideGui(mc, true));
			// Long enough for the chat line about the game mode to fade.
			context.waitTicks(240);
			sp.getConnection().waitForChunksRender();
			context.takeScreenshot(suite + "-99-done");
			context.runOnClient(mc -> hideGui(mc, false));
		} catch (Throwable th) {
			failures.add("exception: " + th);
		} finally {
			RealWorld.writeReport("buildassist-" + suite, failures, log);
		}
		if (!failures.isEmpty()) throw new AssertionError("Build Assist " + suite + " failed: " + String.join("; ", failures));
	}

	/** The F1 switch (no HUD), wherever this game version keeps it. */
	static void hideGui(net.minecraft.client.Minecraft mc, boolean on) {
		Object hud = null;
		try {
			hud = mc.gui.getClass().getField("hud").get(mc.gui);
		} catch (Exception ignored) {
		}
		for (Object o : new Object[] {mc.options, hud, mc.gui, mc}) {
			if (o == null) continue;
			for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				for (var f : c.getDeclaredFields()) {
					if (!(f.getName().equals("hideGui") || f.getName().equals("isHidden")) || f.getType() != boolean.class) continue;
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
