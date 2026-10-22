package com.seolwoni.buildassist.schematic;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** The game's block states for a plan's state strings (unknown blocks and properties are reported, not guessed). */
public final class States {
	private States() {
	}

	/** A state string as a block state, or null if the block is unknown to this game version. */
	public static BlockState resolve(String state) {
		String id = Schematic.blockId(state);
		Identifier key;
		try {
			key = Identifier.parse(id);
		} catch (Exception e) {
			return null;
		}
		Optional<Block> b = BuiltInRegistries.BLOCK.getOptional(key);
		if (b.isEmpty()) return null;
		Block block = b.get();
		if (block == Blocks.AIR && !Schematic.isAir(state)) return null;
		BlockState st = block.defaultBlockState();
		for (Map.Entry<String, String> e : Schematic.properties(state).entrySet()) {
			Property<?> p = block.getStateDefinition().getProperty(e.getKey());
			if (p == null) continue; // a property this version does not have: keep the default
			st = with(st, p, e.getValue());
		}
		return st;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState st, Property<T> p, String value) {
		Optional<T> v = p.getValue(value);
		return v.isPresent() ? st.setValue(p, v.get()) : st;
	}

	/** A block state written the way plans write them: {@code minecraft:oak_stairs[facing=east,half=bottom,...]}. */
	public static String string(BlockState t) {
		StringBuilder sb = new StringBuilder(BuiltInRegistries.BLOCK.getKey(t.getBlock()).toString());
		java.util.Map<String, String> props = new java.util.TreeMap<>();
		for (Property<?> p : t.getProperties()) props.put(p.getName(), String.valueOf(t.getValue(p)).toLowerCase(java.util.Locale.ROOT));
		boolean first = true;
		for (Map.Entry<String, String> e : props.entrySet()) {
			sb.append(first ? '[' : ',').append(e.getKey()).append('=').append(e.getValue());
			first = false;
		}
		if (!first) sb.append(']');
		return sb.toString();
	}

	/**
	 * The plan turned {@code quarterTurns} quarters clockwise and mirrored left to right (east-west) first, its blocks
	 * turned with it (stairs, doors, logs face the new way).
	 */
	public static Schematic turned(Schematic s, int quarterTurns, boolean mirror) {
		net.minecraft.world.level.block.Rotation rot = switch (Math.floorMod(quarterTurns, 4)) {
			case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
			case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
			case 3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
			default -> net.minecraft.world.level.block.Rotation.NONE;
		};
		return s.transformed(quarterTurns, mirror, st -> {
			BlockState b = resolve(st);
			if (b == null) return st;
			if (mirror) b = b.mirror(net.minecraft.world.level.block.Mirror.FRONT_BACK);
			b = b.rotate(rot);
			return string(b);
		});
	}

	/** Every palette entry resolved; unknown ones listed in {@code unknown}. */
	public static BlockState[] all(Schematic s, Map<String, Integer> unknown) {
		BlockState[] out = new BlockState[s.palette.size()];
		Map<Integer, Integer> counts = new HashMap<>();
		for (int c : s.cells) counts.merge(c, 1, Integer::sum);
		for (int i = 0; i < out.length; i++) {
			out[i] = resolve(s.palette.get(i));
			if (out[i] == null && unknown != null) unknown.merge(Schematic.blockId(s.palette.get(i)), counts.getOrDefault(i, 0), Integer::sum);
		}
		return out;
	}
}
