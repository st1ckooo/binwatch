package dev.binwatch.nbt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Writes the binary NBT format. Used to build synthetic {@code item_bytes} for test mode and unit tests. */
public final class NbtWriter {
	private NbtWriter() {
	}

	public static String base64Gzip(Map<String, Object> root) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
				out.writeByte(10);
				out.writeUTF("");
				writeCompound(out, root);
			}
			return Base64.getEncoder().encodeToString(bytes.toByteArray());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void writeCompound(DataOutputStream out, Map<String, Object> map) throws IOException {
		for (Map.Entry<String, Object> e : map.entrySet()) {
			out.writeByte(typeOf(e.getValue()));
			out.writeUTF(e.getKey());
			writePayload(out, e.getValue());
		}
		out.writeByte(0);
	}

	private static byte typeOf(Object v) {
		if (v instanceof Byte || v instanceof Boolean) {
			return 1;
		}
		if (v instanceof Short) {
			return 2;
		}
		if (v instanceof Integer) {
			return 3;
		}
		if (v instanceof Long) {
			return 4;
		}
		if (v instanceof Float) {
			return 5;
		}
		if (v instanceof Double) {
			return 6;
		}
		if (v instanceof String) {
			return 8;
		}
		if (v instanceof List<?>) {
			return 9;
		}
		if (v instanceof Map<?, ?>) {
			return 10;
		}
		throw new IllegalArgumentException("unsupported NBT value " + v);
	}

	@SuppressWarnings("unchecked")
	private static void writePayload(DataOutputStream out, Object v) throws IOException {
		switch (typeOf(v)) {
			case 1 -> out.writeByte(v instanceof Boolean b ? (b ? 1 : 0) : (Byte) v);
			case 2 -> out.writeShort((Short) v);
			case 3 -> out.writeInt((Integer) v);
			case 4 -> out.writeLong((Long) v);
			case 5 -> out.writeFloat((Float) v);
			case 6 -> out.writeDouble((Double) v);
			case 8 -> out.writeUTF((String) v);
			case 9 -> {
				List<Object> list = (List<Object>) v;
				out.writeByte(list.isEmpty() ? 0 : typeOf(list.get(0)));
				out.writeInt(list.size());
				for (Object o : list) {
					writePayload(out, o);
				}
			}
			case 10 -> writeCompound(out, (Map<String, Object>) v);
			default -> throw new IllegalArgumentException("unsupported NBT value " + v);
		}
	}
}
