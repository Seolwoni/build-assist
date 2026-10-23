/*
 * Written for this mod. Baritone's schematic readers (github.com/cabaletta/baritone, LGPL-3.0: SpongeSchematic,
 * LitematicaSchematic) were used as a reference for the two file formats; this adds the third Sponge version and
 * Litematica files with several regions.
 */
package com.seolwoni.buildassist.schematic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A building plan read from a file: a box of {@link #sizeX} x {@link #sizeY} x {@link #sizeZ} cells, each holding an
 * index into {@link #palette} (block states written the game's way, {@code minecraft:oak_stairs[facing=east,...]},
 * properties sorted by name). Index 0 is always {@code minecraft:air}. Plain Java: the game's block states are
 * looked up from the strings when building ({@link States}).
 */
public final class Schematic {
	public static final String AIR = "minecraft:air";

	public final String format;
	public final int sizeX, sizeY, sizeZ;
	public final List<String> palette;
	/** Palette index per cell, at {@code (y * sizeZ + z) * sizeX + x}. */
	public final int[] cells;
	/** Blocks with stored contents (chest items, sign text...) and entities (paintings, item frames...) in the file. */
	public final int blockEntities, entities;
	public final int dataVersion;

	private Schematic(String format, int sx, int sy, int sz, List<String> palette, int[] cells, int blockEntities, int entities, int dataVersion) {
		this.format = format;
		this.sizeX = sx;
		this.sizeY = sy;
		this.sizeZ = sz;
		this.palette = palette;
		this.cells = cells;
		this.blockEntities = blockEntities;
		this.entities = entities;
		this.dataVersion = dataVersion;
	}

	public int index(int x, int y, int z) {
		return (y * sizeZ + z) * sizeX + x;
	}

	/** The block state written at a cell (outside the box: air). */
	public String at(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return AIR;
		return palette.get(cells[index(x, y, z)]);
	}

	/** Cells that are not air. */
	public int solidCount() {
		int n = 0;
		for (int c : cells) if (!isAir(palette.get(c))) n++;
		return n;
	}

	/** Cells per block (without properties), most first. */
	public Map<String, Integer> blockCounts() {
		int[] per = new int[palette.size()];
		for (int c : cells) per[c]++;
		Map<String, Integer> out = new TreeMap<>();
		for (int i = 0; i < per.length; i++) {
			if (per[i] == 0 || isAir(palette.get(i))) continue;
			out.merge(blockId(palette.get(i)), per[i], Integer::sum);
		}
		return out;
	}

	public static boolean isAir(String state) {
		String id = blockId(state);
		return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
	}

	/** {@code minecraft:oak_stairs[facing=east]} -> {@code minecraft:oak_stairs}. */
	public static String blockId(String state) {
		int i = state.indexOf('[');
		String id = i < 0 ? state : state.substring(0, i);
		return id.contains(":") ? id : "minecraft:" + id;
	}

	/** Properties of a state string, by name. */
	public static Map<String, String> properties(String state) {
		Map<String, String> m = new TreeMap<>();
		int i = state.indexOf('[');
		if (i < 0 || !state.endsWith("]")) return m;
		for (String kv : state.substring(i + 1, state.length() - 1).split(",")) {
			int e = kv.indexOf('=');
			if (e > 0) m.put(kv.substring(0, e).trim(), kv.substring(e + 1).trim());
		}
		return m;
	}

	/** A state string with the id given a namespace and the properties sorted. */
	static String normal(String id, Map<String, String> props) {
		String b = id.contains(":") ? id : "minecraft:" + id;
		if (props == null || props.isEmpty()) return b;
		StringBuilder sb = new StringBuilder(b).append('[');
		boolean first = true;
		for (Map.Entry<String, String> e : new TreeMap<>(props).entrySet()) {
			if (!first) sb.append(',');
			sb.append(e.getKey()).append('=').append(e.getValue());
			first = false;
		}
		return sb.append(']').toString();
	}

	// ------------------------------------------------------------------ loading

	public static Schematic load(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			return read(in, file.getFileName().toString());
		}
	}

	public static Schematic read(InputStream in, String name) throws IOException {
		Map<String, Object> root = Nbt.read(in);
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		if (root.containsKey("Regions") || lower.endsWith(".litematic")) return litematica(root);
		Map<String, Object> s = Nbt.map(root, "Schematic");
		if (s != null) return sponge(s); // Sponge version 3 wraps everything in "Schematic"
		if (root.containsKey("Palette") || root.containsKey("BlockData")) return sponge(root);
		if (root.containsKey("Blocks") && root.get("Blocks") instanceof byte[]) throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("옛날 .schematic 형식(1.12 이전)은 읽지 못해요"));
		throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("알 수 없는 설계도 형식이에요"));
	}

	/** Sponge .schem, versions 1 to 3 (WorldEdit, FastAsyncWorldEdit). */
	private static Schematic sponge(Map<String, Object> s) throws IOException {
		int version = Nbt.num(s, "Version", 1);
		int sx = Nbt.num(s, "Width", 0) & 0xffff, sy = Nbt.num(s, "Height", 0) & 0xffff, sz = Nbt.num(s, "Length", 0) & 0xffff;
		Map<String, Object> blocks = version >= 3 ? Nbt.map(s, "Blocks") : s;
		if (blocks == null) throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("설계도에 블록이 없어요"));
		Map<String, Object> pal = Nbt.map(blocks, "Palette");
		Object raw = blocks.get(version >= 3 ? "Data" : "BlockData");
		if (pal == null || !(raw instanceof byte[] data)) throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("설계도 블록 정보가 비었어요"));
		long volume = (long) sx * sy * sz;
		if (volume <= 0 || volume > 50_000_000) throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("설계도 크기가 이상해요: ") + sx + "x" + sy + "x" + sz);

		// File palette index -> our palette index (air first).
		List<String> palette = new ArrayList<>();
		palette.add(AIR);
		Map<String, Integer> ours = new HashMap<>();
		ours.put(AIR, 0);
		int max = 0;
		for (Object v : pal.values()) if (v instanceof Number n) max = Math.max(max, n.intValue());
		int[] map = new int[max + 1];
		for (Map.Entry<String, Object> e : pal.entrySet()) {
			if (!(e.getValue() instanceof Number n)) continue;
			String st = normal(blockId(e.getKey()), properties(e.getKey()));
			if (isAir(st)) st = AIR;
			Integer idx = ours.get(st);
			if (idx == null) {
				idx = palette.size();
				palette.add(st);
				ours.put(st, idx);
			}
			map[n.intValue()] = idx;
		}

		// Block data: varints, x fastest, then z, then y.
		int[] cells = new int[(int) volume];
		int off = 0;
		for (int i = 0; i < cells.length; i++) {
			int v = 0, shift = 0;
			while (true) {
				if (off >= data.length) throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("설계도 블록 정보가 중간에 끊겼어요"));
				int b = data[off++];
				v |= (b & 0x7f) << shift;
				if ((b & 0x80) == 0) break;
				shift += 7;
				if (shift > 28) throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("설계도 블록 정보가 깨졌어요"));
			}
			if (v < 0 || v >= map.length) throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("설계도 블록 번호가 이상해요: ") + v);
			cells[i] = map[v];
		}
		int be = Nbt.list(blocks, version >= 3 ? "BlockEntities" : "BlockEntities").size();
		if (be == 0 && version < 3) be = Nbt.list(s, "TileEntities").size();
		int ent = Nbt.list(s, "Entities").size();
		return new Schematic("schem v" + version, sx, sy, sz, palette, cells, be, ent, Nbt.num(s, "DataVersion", 0));
	}

	/** Litematica .litematic: one or more regions, put together in one box. */
	private static Schematic litematica(Map<String, Object> root) throws IOException {
		Map<String, Object> regions = Nbt.map(root, "Regions");
		if (regions == null || regions.isEmpty()) throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("설계도에 구역이 없어요"));
		record Region(int minX, int minY, int minZ, int sx, int sy, int sz, List<String> pal, long[] bits) {
		}
		List<Region> list = new ArrayList<>();
		int gx0 = Integer.MAX_VALUE, gy0 = Integer.MAX_VALUE, gz0 = Integer.MAX_VALUE, gx1 = Integer.MIN_VALUE, gy1 = Integer.MIN_VALUE, gz1 = Integer.MIN_VALUE;
		int be = 0, ent = 0;
		for (Object o : regions.values()) {
			if (!(o instanceof Map<?, ?>)) continue;
			@SuppressWarnings("unchecked")
			Map<String, Object> r = (Map<String, Object>) o;
			Map<String, Object> pos = Nbt.map(r, "Position"), size = Nbt.map(r, "Size");
			if (pos == null || size == null) continue;
			int px = Nbt.num(pos, "x", 0), py = Nbt.num(pos, "y", 0), pz = Nbt.num(pos, "z", 0);
			int sx = Nbt.num(size, "x", 0), sy = Nbt.num(size, "y", 0), sz = Nbt.num(size, "z", 0);
			// A negative size means the region runs the other way from its position.
			int minX = px + (sx < 0 ? sx + 1 : 0), minY = py + (sy < 0 ? sy + 1 : 0), minZ = pz + (sz < 0 ? sz + 1 : 0);
			sx = Math.abs(sx);
			sy = Math.abs(sy);
			sz = Math.abs(sz);
			if ((long) sx * sy * sz <= 0) continue;
			List<String> pal = new ArrayList<>();
			for (Object p : Nbt.list(r, "BlockStatePalette")) {
				if (!(p instanceof Map<?, ?>)) continue;
				@SuppressWarnings("unchecked")
				Map<String, Object> pm = (Map<String, Object>) p;
				Map<String, String> props = new TreeMap<>();
				Map<String, Object> pr = Nbt.map(pm, "Properties");
				if (pr != null) for (Map.Entry<String, Object> e : pr.entrySet()) props.put(e.getKey(), String.valueOf(e.getValue()));
				String st = normal(String.valueOf(pm.get("Name")), props);
				pal.add(isAir(st) ? AIR : st);
			}
			Object bits = r.get("BlockStates");
			if (pal.isEmpty() || !(bits instanceof long[] longs)) continue;
			list.add(new Region(minX, minY, minZ, sx, sy, sz, pal, longs));
			gx0 = Math.min(gx0, minX);
			gy0 = Math.min(gy0, minY);
			gz0 = Math.min(gz0, minZ);
			gx1 = Math.max(gx1, minX + sx - 1);
			gy1 = Math.max(gy1, minY + sy - 1);
			gz1 = Math.max(gz1, minZ + sz - 1);
			be += Nbt.list(r, "TileEntities").size();
			ent += Nbt.list(r, "Entities").size();
		}
		if (list.isEmpty()) throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("설계도 구역을 읽지 못했어요"));
		int sx = gx1 - gx0 + 1, sy = gy1 - gy0 + 1, sz = gz1 - gz0 + 1;
		long volume = (long) sx * sy * sz;
		if (volume > 50_000_000) throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("설계도가 너무 커요: ") + sx + "x" + sy + "x" + sz);
		List<String> palette = new ArrayList<>();
		palette.add(AIR);
		Map<String, Integer> ours = new HashMap<>();
		ours.put(AIR, 0);
		int[] cells = new int[(int) volume];
		for (Region r : list) {
			int[] map = new int[r.pal.size()];
			for (int i = 0; i < map.length; i++) {
				String st = r.pal.get(i);
				Integer idx = ours.get(st);
				if (idx == null) {
					idx = palette.size();
					palette.add(st);
					ours.put(st, idx);
				}
				map[i] = idx;
			}
			// Tightly packed entries of max(2, bits for the palette) bits, which may cross from one long to the next.
			int width = Math.max(2, 32 - Integer.numberOfLeadingZeros(r.pal.size() - 1));
			long mask = (1L << width) - 1;
			long n = (long) r.sx * r.sy * r.sz;
			if ((n * width + 63) / 64 > r.bits.length) throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("설계도 블록 정보가 모자라요"));
			for (long i = 0; i < n; i++) {
				long bit = i * width;
				int li = (int) (bit >>> 6), off = (int) (bit & 63);
				long v = r.bits[li] >>> off;
				if (off + width > 64) v |= r.bits[li + 1] << (64 - off);
				int p = (int) (v & mask);
				if (p >= map.length) throw new IOException(com.seolwoni.assistcore.i18n.Tr.t("설계도 블록 번호가 이상해요: ") + p);
				int idx = map[p];
				if (idx == 0) continue; // air never covers another region's block
				int x = (int) (i % r.sx), z = (int) ((i / r.sx) % r.sz), y = (int) (i / ((long) r.sx * r.sz));
				int gx = r.minX - gx0 + x, gy = r.minY - gy0 + y, gz = r.minZ - gz0 + z;
				cells[(gy * sz + gz) * sx + gx] = idx;
			}
		}
		return new Schematic("litematic v" + Nbt.num(root, "Version", 0) + (list.size() > 1 ? " (" + list.size() + " regions)" : ""), sx, sy, sz, palette, cells,
				be, ent, Nbt.num(root, "MinecraftDataVersion", 0));
	}

	/**
	 * The plan turned {@code quarterTurns} times a quarter clockwise (seen from above), mirrored left to right first
	 * (east and west swapped) if {@code mirror}. {@code stateFix} turns each block state the same way (stairs face the
	 * other way...); it is given the state string and returns the new one.
	 */
	public Schematic transformed(int quarterTurns, boolean mirror, java.util.function.UnaryOperator<String> stateFix) {
		int r = Math.floorMod(quarterTurns, 4);
		if (r == 0 && !mirror) return this;
		int nx = r % 2 == 0 ? sizeX : sizeZ, nz = r % 2 == 0 ? sizeZ : sizeX;
		List<String> pal = new ArrayList<>(palette.size());
		Map<String, Integer> ours = new HashMap<>();
		int[] map = new int[palette.size()];
		for (int i = 0; i < palette.size(); i++) {
			String st = i == 0 ? AIR : stateFix.apply(palette.get(i));
			Integer idx = ours.get(st);
			if (idx == null) {
				idx = pal.size();
				pal.add(st);
				ours.put(st, idx);
			}
			map[i] = idx;
		}
		int[] out = new int[cells.length];
		for (int y = 0; y < sizeY; y++) {
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					int mx = mirror ? sizeX - 1 - x : x, mz = z;
					// Quarter turns clockwise: (x, z) -> (depth - 1 - z, x), the box's sides swapping each time.
					int cx = mx, cz = mz, w = sizeX, d = sizeZ;
					for (int k = 0; k < r; k++) {
						int tx = d - 1 - cz, tz = cx;
						cx = tx;
						cz = tz;
						int tw = d;
						d = w;
						w = tw;
					}
					out[(y * nz + cz) * nx + cx] = map[cells[index(x, y, z)]];
				}
			}
		}
		return new Schematic(format, nx, sizeY, nz, pal, out, blockEntities, entities, dataVersion);
	}

	@Override
	public String toString() {
		return format + " " + sizeX + "x" + sizeY + "x" + sizeZ + ", " + solidCount() + " blocks, " + (palette.size() - 1) + " states, "
				+ blockCounts().size() + " kinds, " + blockEntities + " block entities, " + entities + " entities";
	}

	/** For tests: same contents? */
	public boolean sameAs(Schematic o) {
		if (sizeX != o.sizeX || sizeY != o.sizeY || sizeZ != o.sizeZ) return false;
		for (int i = 0; i < cells.length; i++) if (!palette.get(cells[i]).equals(o.palette.get(o.cells[i]))) return false;
		return true;
	}

	static int[] copy(int[] a) {
		return Arrays.copyOf(a, a.length);
	}
}
