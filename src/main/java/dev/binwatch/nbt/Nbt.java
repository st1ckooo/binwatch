package dev.binwatch.nbt;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal reader for Minecraft's binary NBT format, used to decode the {@code item_bytes} field of the public
 * auction API (base64 of gzipped NBT). Compounds become {@code Map<String,Object>}, lists become {@code List<Object>},
 * primitives become boxed numbers or strings. Independent of Minecraft classes so it is unit-testable.
 */
public final class Nbt {
	private static final int MAX_DEPTH = 64;
	private static final int MAX_ARRAY = 1 << 20;

	private Nbt() {
	}

	public static Map<String, Object> decodeBase64Gzip(String base64) throws IOException {
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(base64.trim());
		} catch (IllegalArgumentException e) {
			throw new IOException("item_bytes is not valid base64", e);
		}
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
			return readRoot(new DataInputStream(in));
		}
	}

	public static Map<String, Object> readRoot(DataInput in) throws IOException {
		byte type = in.readByte();
		if (type != 10) {
			throw new IOException("root tag is not a compound (type " + type + ")");
		}
		in.readUTF();
		return readCompound(in, 0);
	}

	private static Map<String, Object> readCompound(DataInput in, int depth) throws IOException {
		if (depth > MAX_DEPTH) {
			throw new IOException("NBT nested too deeply");
		}
		Map<String, Object> map = new LinkedHashMap<>();
		while (true) {
			byte type = in.readByte();
			if (type == 0) {
				return map;
			}
			String name = in.readUTF();
			map.put(name, readPayload(in, type, depth + 1));
		}
	}

	private static Object readPayload(DataInput in, byte type, int depth) throws IOException {
		return switch (type) {
			case 1 -> in.readByte();
			case 2 -> in.readShort();
			case 3 -> in.readInt();
			case 4 -> in.readLong();
			case 5 -> in.readFloat();
			case 6 -> in.readDouble();
			case 7 -> {
				int len = arrayLength(in.readInt());
				byte[] b = new byte[len];
				in.readFully(b);
				yield b;
			}
			case 8 -> in.readUTF();
			case 9 -> {
				byte elementType = in.readByte();
				int len = arrayLength(in.readInt());
				List<Object> list = new ArrayList<>(Math.min(len, 1024));
				for (int i = 0; i < len; i++) {
					list.add(readPayload(in, elementType, depth + 1));
				}
				yield list;
			}
			case 10 -> readCompound(in, depth);
			case 11 -> {
				int len = arrayLength(in.readInt());
				int[] a = new int[len];
				for (int i = 0; i < len; i++) {
					a[i] = in.readInt();
				}
				yield a;
			}
			case 12 -> {
				int len = arrayLength(in.readInt());
				long[] a = new long[len];
				for (int i = 0; i < len; i++) {
					a[i] = in.readLong();
				}
				yield a;
			}
			case 0 -> null;
			default -> throw new IOException("unknown NBT tag type " + type);
		};
	}

	private static int arrayLength(int len) throws IOException {
		if (len < 0 || len > MAX_ARRAY) {
			throw new IOException("invalid NBT array length " + len);
		}
		return len;
	}

	// ---- typed accessors -------------------------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	public static Map<String, Object> compound(Map<String, Object> map, String key) {
		Object o = map == null ? null : map.get(key);
		return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(Map<String, Object> map, String key) {
		Object o = map == null ? null : map.get(key);
		return o instanceof List<?> l ? (List<Object>) l : null;
	}

	public static String string(Map<String, Object> map, String key) {
		Object o = map == null ? null : map.get(key);
		return o instanceof String s ? s : null;
	}

	public static Long number(Map<String, Object> map, String key) {
		Object o = map == null ? null : map.get(key);
		if (o instanceof Number n) {
			return n.longValue();
		}
		if (o instanceof String s) {
			try {
				return Long.parseLong(s.trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	/** Canonical, order-independent string form of any decoded NBT value, used for item comparison keys. */
	public static String canonical(Object value) {
		StringBuilder sb = new StringBuilder();
		appendCanonical(sb, value);
		return sb.toString();
	}

	private static void appendCanonical(StringBuilder sb, Object value) {
		if (value instanceof Map<?, ?> m) {
			sb.append('{');
			m.keySet().stream().map(String::valueOf).sorted().forEach(k -> {
				sb.append(k).append(':');
				appendCanonical(sb, m.get(k));
				sb.append(',');
			});
			sb.append('}');
		} else if (value instanceof List<?> l) {
			sb.append('[');
			for (Object o : l) {
				appendCanonical(sb, o);
				sb.append(',');
			}
			sb.append(']');
		} else if (value instanceof byte[] b) {
			sb.append(java.util.Arrays.toString(b));
		} else if (value instanceof int[] a) {
			sb.append(java.util.Arrays.toString(a));
		} else if (value instanceof long[] a) {
			sb.append(java.util.Arrays.toString(a));
		} else if (value instanceof Number n) {
			// Normalize integral types so a byte 1 and an int 1 compare equal.
			double d = n.doubleValue();
			sb.append(d == Math.rint(d) && !Double.isInfinite(d) ? Long.toString((long) d) : Double.toString(d));
		} else {
			sb.append(value);
		}
	}
}
