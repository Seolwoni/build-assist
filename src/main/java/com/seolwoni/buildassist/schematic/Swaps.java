package com.seolwoni.buildassist.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Material swaps chosen by the player, per plan: every block of one kind is built as another, keeping what both have
 * in common (which way stairs face, top or bottom slab, which way a log lies). Kept in
 * {@code config/buildassist-swaps.json}, so a plan opened again keeps its swaps.
 */
public final class Swaps {
	private Swaps() {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("buildassist-swaps.json");
	private static Map<String, Map<String, String>> all;

	/** Woods (and the nether's stems) whose blocks are named the same way: {@code <wood>_planks}, {@code <wood>_stairs}... */
	public static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo",
			"crimson", "warped");

	private static Map<String, Map<String, String>> all() {
		if (all == null) {
			try {
				all = GSON.fromJson(Files.readString(PATH), new TypeToken<Map<String, Map<String, String>>>() {
				}.getType());
			} catch (Exception e) {
				all = null;
			}
			if (all == null) all = new TreeMap<>();
		}
		return all;
	}

	private static void save() {
		try {
			Files.writeString(PATH, GSON.toJson(all()));
		} catch (IOException ignored) {
		}
	}

	/** The swaps of a plan (block id -> block id), editable; an empty map if none. */
	public static Map<String, String> of(String plan) {
		return all().computeIfAbsent(plan == null ? "" : plan, k -> new LinkedHashMap<>());
	}

	public static void put(String plan, String from, String to) {
		Map<String, String> m = of(plan);
		// Swapping back to what the plan had: no swap any more.
		if (from.equals(to)) m.remove(from);
		else m.put(from, to);
		save();
	}

	public static void clear(String plan) {
		of(plan).clear();
		save();
	}

	/** The plan as it will be built: every swapped block as its new kind. */
	public static Schematic apply(Schematic s, Map<String, String> swaps) {
		if (swaps == null || swaps.isEmpty()) return s;
		return s.mapped(st -> swapState(st, swaps));
	}

	/** One block state with its block swapped, the properties both blocks have carried over. */
	public static String swapState(String state, Map<String, String> swaps) {
		String id = Schematic.blockId(state);
		String to = swaps.get(id);
		if (to == null) return state;
		StringBuilder sb = new StringBuilder(to);
		Map<String, String> props = Schematic.properties(state);
		if (!props.isEmpty()) {
			sb.append('[');
			boolean first = true;
			for (Map.Entry<String, String> e : props.entrySet()) {
				if (!first) sb.append(',');
				sb.append(e.getKey()).append('=').append(e.getValue());
				first = false;
			}
			sb.append(']');
		}
		// Properties the new block does not have are dropped (a stair swapped to a full block keeps nothing).
		BlockState b = States.resolve(sb.toString());
		return b == null ? state : States.string(b);
	}

	/** The block a material of the list stands for (its item id is the block id for building blocks), or null. */
	public static String blockFor(String itemId) {
		try {
			Identifier key = Identifier.parse(itemId);
			return BuiltInRegistries.BLOCK.containsKey(key) ? itemId : null;
		} catch (Exception e) {
			return null;
		}
	}

	/** Blocks of the same shape as {@code id} (same ending: _stairs, _slab, _planks...), for the picker's list. */
	public static List<String> sameShape(String id) {
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		int us = path.lastIndexOf('_');
		List<String> out = new ArrayList<>();
		if (us < 0) return out;
		String end = path.substring(us);
		for (Identifier k : BuiltInRegistries.BLOCK.keySet()) {
			if (k.getPath().endsWith(end) && !k.toString().equals(id)) out.add(k.toString());
			if (out.size() >= 60) break;
		}
		out.sort(null);
		return out;
	}

	/** The wood the plan uses most (by block count), or null. */
	public static String mainWood(Schematic s) {
		Map<String, Integer> per = new TreeMap<>();
		for (Map.Entry<String, Integer> e : s.blockCounts().entrySet()) {
			String w = woodOf(e.getKey());
			if (w != null) per.merge(w, e.getValue(), Integer::sum);
		}
		String best = null;
		int n = 0;
		for (Map.Entry<String, Integer> e : per.entrySet()) if (e.getValue() > n) {
			n = e.getValue();
			best = e.getKey();
		}
		return best;
	}

	/** Which wood a block is made of ({@code minecraft:spruce_stairs} -> spruce), or null. */
	public static String woodOf(String id) {
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		path = path.startsWith("stripped_") ? path.substring(9) : path;
		String best = null;
		for (String w : WOODS) if (path.startsWith(w + "_") && (best == null || w.length() > best.length())) best = w;
		return best;
	}

	/** {@code id} made of wood {@code to} instead of its own ({@code spruce_stairs}, birch -> {@code birch_stairs}), or null. */
	public static String inWood(String id, String to) {
		String w = woodOf(id);
		if (w == null) return null;
		String ns = id.substring(0, id.indexOf(':') + 1), path = id.substring(id.indexOf(':') + 1);
		boolean stripped = path.startsWith("stripped_");
		String rest = (stripped ? path.substring(9) : path).substring(w.length());
		String target = ns + (stripped ? "stripped_" : "") + to + rest;
		return blockFor(target);
	}

	/**
	 * Every block of the plan (as swapped so far) made of wood {@code from} becomes wood {@code to}, where that block
	 * exists. {@code original}: the plan as read from the file (the swaps are kept against its blocks).
	 */
	public static void swapWood(String plan, Schematic original, String from, String to) {
		Map<String, String> m = of(plan);
		for (String id : original.blockCounts().keySet()) {
			String cur = m.getOrDefault(id, id);
			if (!from.equals(woodOf(cur))) continue;
			String target = inWood(cur, to);
			if (target == null) continue;
			if (target.equals(id)) m.remove(id);
			else m.put(id, target);
		}
		save();
	}

	/** Swaps every block of the plan now built as {@code shown} to {@code to} (the material list shows the swapped plan). */
	public static void swapShown(String plan, Schematic original, String shown, String to) {
		Map<String, String> m = of(plan);
		for (String id : original.blockCounts().keySet()) {
			if (!m.getOrDefault(id, id).equals(shown)) continue;
			if (to.equals(id)) m.remove(id);
			else m.put(id, to);
		}
		save();
	}
}
