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
