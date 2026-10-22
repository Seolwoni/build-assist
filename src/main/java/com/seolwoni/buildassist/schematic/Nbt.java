package com.seolwoni.buildassist.schematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A small reader for NBT files (the format schematics are saved in), independent of the game's own NBT classes so
 * it does not change with game versions. Compounds become {@code Map<String, Object>}, lists {@code List<Object>},
 * numbers their boxed Java types, and arrays {@code byte[]}, {@code int[]}, {@code long[]}.
 */
public final class Nbt {
	private Nbt() {
	}

	/** Reads a (normally gzip-compressed) NBT file: the root compound. */
	public static Map<String, Object> read(InputStream raw) throws IOException {
		BufferedInputStream in = new BufferedInputStream(raw);
		in.mark(2);
		int a = in.read(), b = in.read();
		in.reset();
		InputStream src = (a == 0x1f && b == 0x8b) ? new GZIPInputStream(in) : in;
		DataInputStream d = new DataInputStream(src);
		int type = d.readUnsignedByte();
		if (type != 10) throw new IOException("not an NBT compound (type " + type + ")");
		readString(d); // root name
		return compound(d, 0);
	}

	private static String readString(DataInputStream d) throws IOException {
		int len = d.readUnsignedShort();
		byte[] b = new byte[len];
		d.readFully(b);
		return new String(b, StandardCharsets.UTF_8); // modified UTF-8: the same for the names schematics use
	}

	private static Map<String, Object> compound(DataInputStream d, int depth) throws IOException {
		if (depth > 64) throw new IOException("NBT nested too deep");
		Map<String, Object> m = new LinkedHashMap<>();
		while (true) {
			int type = d.readUnsignedByte();
			if (type == 0) return m;
			String name = readString(d);
			m.put(name, value(d, type, depth + 1));
		}
	}

	private static Object value(DataInputStream d, int type, int depth) throws IOException {
		switch (type) {
			case 1:
				return d.readByte();
			case 2:
				return d.readShort();
			case 3:
				return d.readInt();
			case 4:
				return d.readLong();
			case 5:
				return d.readFloat();
			case 6:
				return d.readDouble();
			case 7: {
				int n = length(d);
				byte[] b = new byte[n];
				d.readFully(b);
				return b;
			}
			case 8:
				return readString(d);
			case 9: {
				int et = d.readUnsignedByte();
				int n = length(d);
				List<Object> l = new ArrayList<>(Math.min(n, 4096));
				for (int i = 0; i < n; i++) l.add(value(d, et, depth + 1));
				return l;
			}
			case 10:
				return compound(d, depth);
			case 11: {
				int n = length(d);
				int[] v = new int[n];
				for (int i = 0; i < n; i++) v[i] = d.readInt();
				return v;
			}
			case 12: {
				int n = length(d);
				long[] v = new long[n];
				for (int i = 0; i < n; i++) v[i] = d.readLong();
				return v;
			}
			default:
				throw new IOException("unknown NBT tag type " + type);
		}
	}

	private static int length(DataInputStream d) throws IOException {
		int n = d.readInt();
		if (n < 0 || n > 64_000_000) throw new IOException("bad NBT length " + n);
		return n;
	}

	// ---- helpers for reading the maps ----

	@SuppressWarnings("unchecked")
	public static Map<String, Object> map(Map<String, Object> m, String key) {
		Object o = m.get(key);
		return o instanceof Map ? (Map<String, Object>) o : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(Map<String, Object> m, String key) {
		Object o = m.get(key);
		return o instanceof List ? (List<Object>) o : List.of();
	}

	public static int num(Map<String, Object> m, String key, int def) {
		Object o = m.get(key);
		return o instanceof Number n ? n.intValue() : def;
	}

	public static String str(Map<String, Object> m, String key) {
		Object o = m.get(key);
		return o instanceof String s ? s : null;
	}
}
