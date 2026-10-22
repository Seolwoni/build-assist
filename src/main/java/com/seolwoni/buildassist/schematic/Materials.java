package com.seolwoni.buildassist.schematic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a plan needs, in items: one item per block, except where the game builds a block from something else (a door
 * is one item for two cells, a double slab two slabs, a wall sign a sign, a potted flower a pot and the flower,
 * water a bucket...). Blocks that cannot be had in survival are listed apart, and so are blocks that are made in
 * place (farmland, crops, paths) rather than put down as they are.
 */
public final class Materials {
	/** Item id -> count. */
	public final Map<String, Integer> items = new TreeMap<>();
	/** Block id -> count: cannot be had in survival (skipped when building). */
	public final Map<String, Integer> unobtainable = new TreeMap<>();
	/** Block id -> count: made in place, not just put down (farmland, crops, paths...): later stages. */
	public final Map<String, Integer> special = new TreeMap<>();

	static final Set<String> UNOBTAINABLE = Set.of("light", "barrier", "structure_void", "structure_block", "jigsaw", "command_block",
			"chain_command_block", "repeating_command_block", "bedrock", "spawner", "trial_spawner", "vault", "end_portal_frame", "end_portal", "end_gateway",
			"nether_portal", "budding_amethyst", "reinforced_deepslate", "petrified_oak_slab", "infested_stone", "infested_cobblestone",
			"infested_stone_bricks", "infested_mossy_stone_bricks", "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks", "infested_deepslate",
			"fire", "soul_fire", "frosted_ice", "moving_piston", "piston_head", "test_block", "test_instance_block");

	/** Made in place (with a tool, by planting, by growing): not put down from an item as they are. */
	static final Set<String> MADE_IN_PLACE = Set.of("farmland", "dirt_path", "wheat", "carrots", "potatoes", "beetroots", "melon_stem", "pumpkin_stem",
			"attached_melon_stem", "attached_pumpkin_stem", "torchflower_crop", "pitcher_crop", "cocoa", "sweet_berry_bush", "nether_wart", "cave_vines",
			"cave_vines_plant", "kelp_plant", "twisting_vines_plant", "weeping_vines_plant", "bamboo_sapling", "bubble_column", "water_cauldron",
			"lava_cauldron", "powder_snow_cauldron", "frogspawn");

	public static Materials of(Schematic s) {
		int[] per = new int[s.palette.size()];
		for (int c : s.cells) per[c]++;
		Materials m = new Materials();
		for (int i = 0; i < per.length; i++) {
			if (per[i] == 0) continue;
			m.add(s.palette.get(i), per[i]);
		}
		return m;
	}

	private void add(String state, int n) {
		if (Schematic.isAir(state)) return;
		String id = Schematic.blockId(state);
		String path = id.substring(id.indexOf(':') + 1);
		Map<String, String> p = Schematic.properties(state);
		if (UNOBTAINABLE.contains(path)) {
			unobtainable.merge(id, n, Integer::sum);
			return;
		}
		// Wall torches are just torches put on a wall.
		if (path.equals("wall_torch") || path.equals("soul_wall_torch") || path.equals("redstone_wall_torch")) {
			items.merge("minecraft:" + path.replace("wall_", ""), n, Integer::sum);
			return;
		}
		if (MADE_IN_PLACE.contains(path)) {
			special.merge(id, n, Integer::sum);
			return;
		}
		// Two-cell blocks: the item is counted on one half only.
		if ("upper".equals(p.get("half")) && (path.endsWith("_door") || isTallPlant(path))) return;
		if ("head".equals(p.get("part")) && path.endsWith("_bed")) return;
		// Fluids: a source is a bucket; flowing water or lava comes by itself.
		if (path.equals("water") || path.equals("lava")) {
			if ("0".equals(p.getOrDefault("level", "0"))) items.merge("minecraft:" + path + "_bucket", n, Integer::sum);
			return;
		}
		int per = 1;
		if (path.endsWith("_slab") && "double".equals(p.get("type"))) per = 2;
		if (p.containsKey("candles")) per = parse(p.get("candles"), 1);
		if (p.containsKey("pickles")) per = parse(p.get("pickles"), 1);
		if (p.containsKey("eggs")) per = parse(p.get("eggs"), 1);
		if (p.containsKey("flower_amount")) per = parse(p.get("flower_amount"), 1);
		if (p.containsKey("segment_amount")) per = parse(p.get("segment_amount"), 1);
		if (path.equals("snow")) per = parse(p.get("layers"), 1);
		// Potted plants: a flower pot and the plant.
		if (path.startsWith("potted_")) {
			items.merge("minecraft:flower_pot", n, Integer::sum);
			String plant = path.substring("potted_".length());
			if (plant.equals("azalea_bush")) plant = "azalea";
			if (plant.equals("flowering_azalea_bush")) plant = "flowering_azalea";
			items.merge("minecraft:" + plant, n, Integer::sum);
			return;
		}
		// Wall-mounted forms use the standing item (signs, banners, heads, coral fans).
		if (path.equals("redstone_wire")) path = "redstone";
		if (path.equals("tripwire")) path = "string";
		String item = path.replace("_wall_hanging_sign", "_hanging_sign").replace("_wall_sign", "_sign").replace("_wall_banner", "_banner")
				.replace("_wall_head", "_head").replace("_wall_skull", "_skull").replace("_wall_fan", "_fan");
		items.merge("minecraft:" + item, n * per, Integer::sum);
	}

	private static boolean isTallPlant(String path) {
		return path.equals("tall_grass") || path.equals("large_fern") || path.equals("sunflower") || path.equals("lilac") || path.equals("rose_bush")
				|| path.equals("peony") || path.equals("pitcher_plant") || path.equals("tall_seagrass") || path.equals("small_dripleaf");
	}

	private static int parse(String s, int def) {
		try {
			return Integer.parseInt(s);
		} catch (Exception e) {
			return def;
		}
	}

	public int totalItems() {
		int n = 0;
		for (int v : items.values()) n += v;
		return n;
	}

	/** Items, most first. */
	public List<Map.Entry<String, Integer>> sorted() {
		List<Map.Entry<String, Integer>> l = new ArrayList<>(items.entrySet());
		l.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
		return l;
	}

	/** Stacks of 64 needed (16 for signs, banners, buckets, eggs; 1 for beds... roughly). */
	public int stacks() {
		int s = 0;
		for (Map.Entry<String, Integer> e : items.entrySet()) s += (e.getValue() + stackSize(e.getKey()) - 1) / stackSize(e.getKey());
		return s;
	}

	static int stackSize(String item) {
		if (item.endsWith("_bucket") || item.endsWith("_bed") || item.endsWith("cake")) return 1;
		if (item.endsWith("_sign") || item.endsWith("_banner") || item.endsWith("egg") || item.endsWith("ender_pearl")) return 16;
		return 64;
	}

	/** For logs: a short summary. */
	public Map<String, Object> summary() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("items", items.size());
		m.put("count", totalItems());
		m.put("stacks", stacks());
		m.put("unobtainable", unobtainable);
		m.put("special", special);
		return m;
	}
}
