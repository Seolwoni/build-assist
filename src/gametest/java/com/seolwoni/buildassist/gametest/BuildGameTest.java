package com.seolwoni.buildassist.gametest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.seolwoni.buildassist.BuildAssistClient;
import com.seolwoni.buildassist.gui.BuildScreen;
import com.seolwoni.buildassist.schematic.Materials;
import com.seolwoni.buildassist.schematic.Schematic;
import com.seolwoni.buildassist.schematic.States;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Stage 1: reading plans. The same small house saved four ways (Sponge .schem versions 2 and 3, Litematica with two
 * regions, one of them with a negative size) must read the same; every block and every material must be one the game
 * knows; the plan screen must open and show the materials, with what is carried.
 */
public class BuildGameTest implements FabricClientGameTest {
	private final List<String> log = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();

	private static final String[] FILES = {"test-house-v2.schem", "test-house-v3.schem", "test-house.litematic", "test-house-neg.litematic"};

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!RealWorld.suite().equals("flat")) return;
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			RealWorld.cmd(sp, "gamemode survival @a");
			RealWorld.cmd(sp, "clear @a");
			RealWorld.cmd(sp, "give @a minecraft:oak_planks 20");
			RealWorld.cmd(sp, "give @a minecraft:stone_bricks 64");
			context.waitTicks(20);

			// Put the test plans where players put theirs.
			Path folder = context.computeOnClient(mc -> BuildAssistClient.folder());
			for (String f : FILES) {
				try (InputStream in = BuildGameTest.class.getResourceAsStream("/schematics/" + f)) {
					if (in == null) {
						failures.add("test file missing: " + f);
						continue;
					}
					Files.copy(in, folder.resolve(f), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			int listed = context.computeOnClient(mc -> BuildAssistClient.files().size());
			log.add("files listed: " + listed);
			if (listed < FILES.length) failures.add("only " + listed + " of " + FILES.length + " files listed");

			Schematic first = null;
			for (String f : FILES) {
				Path p = folder.resolve(f);
				String err = context.computeOnClient(mc -> BuildScreen.load(p));
				if (err != null) {
					failures.add(f + ": " + err);
					continue;
				}
				Schematic s = context.computeOnClient(mc -> BuildScreen.plan());
				Materials m = context.computeOnClient(mc -> BuildScreen.materials());
				log.add(f + ": " + s + " | materials " + m.summary() + " " + m.items);
				if (s.sizeX != 7 || s.sizeY != 5 || s.sizeZ != 6) failures.add(f + ": size " + s.sizeX + "x" + s.sizeY + "x" + s.sizeZ + ", not 7x5x6");
				if (s.solidCount() != 150) failures.add(f + ": " + s.solidCount() + " blocks, not 150");
				Map<String, Integer> want = new TreeMap<>(Map.of("minecraft:oak_planks", 50, "minecraft:oak_slab", 42, "minecraft:stone_bricks", 42, "minecraft:oak_log", 12,
						"minecraft:glass", 2, "minecraft:chest", 1, "minecraft:crafting_table", 1));
				if (!new TreeMap<>(m.items).equals(want)) failures.add(f + ": materials " + m.items + ", not " + want);
				if (first == null) first = s;
				else if (!first.sameAs(s)) failures.add(f + ": not the same house as " + FILES[0]);
				// Every block a block this game knows, every material an item.
				Map<String, Integer> unknown = new TreeMap<>();
				context.runOnClient(mc -> States.all(s, unknown));
				if (!unknown.isEmpty()) failures.add(f + ": blocks the game does not know: " + unknown);
				for (String item : m.items.keySet()) {
					boolean known = context.computeOnClient(mc -> BuiltInRegistries.ITEM.getOptional(Identifier.parse(item)).isPresent());
					if (!known) failures.add(f + ": material is not an item: " + item);
				}
			}
			// The plans the player gave: read, block counts as counted outside the game, every block and material known.
			Map<String, Integer> given = new TreeMap<>(Map.of("hill-house.schem", 8530, "crane.schem", 155, "tree-farm.litematic", 765, "tokyo-street.litematic", 25285,
					"pine-small.litematic", 134, "pine-medium.litematic", 260, "pine-large.litematic", 442));
			for (Map.Entry<String, Integer> g : given.entrySet()) {
				Path p = folder.resolve(g.getKey());
				try (InputStream in = BuildGameTest.class.getResourceAsStream("/schematics/" + g.getKey())) {
					Files.copy(in, p, StandardCopyOption.REPLACE_EXISTING);
				}
				String err = context.computeOnClient(mc -> BuildScreen.load(p));
				if (err != null) {
					failures.add(g.getKey() + ": " + err);
					continue;
				}
				Schematic s = context.computeOnClient(mc -> BuildScreen.plan());
				Materials m = context.computeOnClient(mc -> BuildScreen.materials());
				log.add(g.getKey() + ": " + s + " | " + m.summary());
				if (s.solidCount() != g.getValue()) failures.add(g.getKey() + ": " + s.solidCount() + " blocks, not " + g.getValue());
				Map<String, Integer> unknown = new TreeMap<>();
				context.runOnClient(mc -> States.all(s, unknown));
				if (!unknown.isEmpty()) failures.add(g.getKey() + ": blocks the game does not know: " + unknown);
				List<String> notItems = context.computeOnClient(mc -> {
					List<String> out = new ArrayList<>();
					for (String item : m.items.keySet()) if (BuiltInRegistries.ITEM.getOptional(Identifier.parse(item)).isEmpty()) out.add(item);
					return out;
				});
				if (!notItems.isEmpty()) failures.add(g.getKey() + ": materials that are not items: " + notItems);
			}

			// A plan that is not a plan: a clear message, no crash.
			Path bad = folder.resolve("broken.schem");
			Files.write(bad, new byte[] {1, 2, 3, 4});
			String badErr = context.computeOnClient(mc -> BuildScreen.load(bad));
			log.add("broken file: " + badErr);
			if (badErr == null) failures.add("a broken file was read as a plan");
			Files.deleteIfExists(bad);

			// The screen, with the litematic picked: 20 of 50 planks and all the stone bricks carried.
			Path lit = folder.resolve("test-house.litematic");
			context.runOnClient(mc -> {
				BuildScreen.load(lit);
				mc.gui.setScreen(new BuildScreen(null));
			});
			context.waitTicks(10);
			Map<String, Integer> have = context.computeOnClient(mc -> BuildScreen.carried());
			log.add("carried: " + have);
			if (have.getOrDefault("minecraft:oak_planks", 0) != 20) failures.add("carried planks counted as " + have.get("minecraft:oak_planks"));
			context.takeScreenshot("build-screen");
			context.runOnClient(mc -> mc.gui.setScreen(null));
		} catch (Throwable t) {
			failures.add("exception: " + t);
		} finally {
			RealWorld.writeReport("buildassist", failures, log);
		}
		if (!failures.isEmpty()) throw new AssertionError("Build Assist test failed: " + String.join("; ", failures));
	}
}
