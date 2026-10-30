package dev.binwatch.log;

import dev.binwatch.util.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BIN Watch logger. Every message is passed through {@link Redactor} before it reaches any sink, so secrets cannot
 * leak through logs. Messages go to the game log (SLF4J) and to a size-rotated file. Repeated errors with the same key
 * are throttled so a persistent failure cannot flood the logs.
 */
public final class BwLog {
	public enum Level {
		DEBUG, INFO, WARN, ERROR
	}

	private static final Logger SLF4J = LoggerFactory.getLogger("BINWatch");
	private static final long REPEAT_WINDOW_MS = 60_000;

	private static volatile Level minLevel = Level.INFO;
	private static volatile Path logFile;
	private static volatile long maxFileBytes = 5L * 1024 * 1024;
	private static volatile int maxFiles = 5;
	private static final Object FILE_LOCK = new Object();
	private static Writer writer;
	private static long writtenBytes;

	private static final ConcurrentHashMap<String, long[]> REPEATS = new ConcurrentHashMap<>();

	private BwLog() {
	}

	/** Enables file logging. {@code dir} may be null to disable it (unit tests). */
	public static void configure(Path dir, Level level, int maxFileSizeMb, int fileCount) {
		minLevel = level;
		maxFileBytes = Math.max(1, maxFileSizeMb) * 1024L * 1024L;
		maxFiles = Math.max(1, fileCount);
		synchronized (FILE_LOCK) {
			closeWriter();
			if (dir == null) {
				logFile = null;
				return;
			}
			try {
				Files.createDirectories(dir);
				logFile = dir.resolve("binwatch.log");
				openWriter();
			} catch (IOException e) {
				logFile = null;
				SLF4J.warn("BIN Watch file logging unavailable: {}", e.toString());
			}
		}
	}

	public static Level parseLevel(String s) {
		try {
			return Level.valueOf(s.trim().toUpperCase(Locale.ROOT));
		} catch (RuntimeException e) {
			return Level.INFO;
		}
	}

	public static void setLevel(Level level) {
		minLevel = level;
	}

	public static void debug(String msg) {
		log(Level.DEBUG, msg, null);
	}

	public static void info(String msg) {
		log(Level.INFO, msg, null);
	}

	public static void warn(String msg) {
		log(Level.WARN, msg, null);
	}

	public static void error(String msg, Throwable t) {
		log(Level.ERROR, msg, t);
	}

	/**
	 * Logs at most once per minute per {@code key}; suppressed repeats are counted and reported with the next message
	 * that gets through.
	 */
	public static void throttled(Level level, String key, String msg, Throwable t) {
		long now = System.currentTimeMillis();
		long[] state = REPEATS.computeIfAbsent(key, k -> new long[] {0, 0});
		String suffix = null;
		synchronized (state) {
			if (now - state[0] < REPEAT_WINDOW_MS) {
				state[1]++;
				return;
			}
			if (state[1] > 0) {
				suffix = " (suppressed " + state[1] + " similar messages)";
			}
			state[0] = now;
			state[1] = 0;
		}
		log(level, suffix == null ? msg : msg + suffix, t);
	}

	/** Clears throttle state for a key, e.g. after the failing operation succeeds again. */
	public static void resetThrottle(String key) {
		REPEATS.remove(key);
	}

	private static void log(Level level, String msg, Throwable t) {
		if (level.ordinal() < minLevel.ordinal()) {
			return;
		}
		String safe = Redactor.redact(msg);
		String err = t == null ? null : Redactor.redact(t.toString());
		switch (level) {
			case DEBUG -> SLF4J.info("[debug] {}", safe);
			case INFO -> SLF4J.info(safe);
			case WARN -> SLF4J.warn(safe);
			case ERROR -> SLF4J.error(err == null ? safe : safe + " : " + err);
		}
		writeFile(level, safe, err);
		java.util.function.BiConsumer<Level, String> s = sink;
		if (s != null && level.ordinal() >= Level.WARN.ordinal()) {
			try {
				s.accept(level, err == null ? safe : safe + " : " + err);
			} catch (RuntimeException ignored) {
				// The sink must never break logging.
			}
		}
	}

	/** Receives every WARN and ERROR line (already redacted), e.g. for the Discord log channel. */
	private static volatile java.util.function.BiConsumer<Level, String> sink;

	public static void setSink(java.util.function.BiConsumer<Level, String> s) {
		sink = s;
	}

	private static void writeFile(Level level, String msg, String err) {
		if (logFile == null) {
			return;
		}
		String line = Instant.now() + " " + level + " " + msg + (err == null ? "" : " : " + err) + System.lineSeparator();
		synchronized (FILE_LOCK) {
			try {
				if (writer == null) {
					return;
				}
				writer.write(line);
				writer.flush();
				writtenBytes += line.getBytes(StandardCharsets.UTF_8).length;
				if (writtenBytes >= maxFileBytes) {
					rotate();
				}
			} catch (IOException e) {
				SLF4J.warn("BIN Watch log write failed: {}", e.toString());
			}
		}
	}

	private static void openWriter() throws IOException {
		writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		writtenBytes = Files.size(logFile);
	}

	private static void closeWriter() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException ignored) {
				// Nothing useful to do while closing a log file.
			}
			writer = null;
		}
	}

	private static void rotate() throws IOException {
		closeWriter();
		Path dir = logFile.getParent();
		Files.deleteIfExists(dir.resolve("binwatch." + (maxFiles - 1) + ".log"));
		for (int i = maxFiles - 2; i >= 1; i--) {
			Path src = dir.resolve("binwatch." + i + ".log");
			if (Files.exists(src)) {
				Files.move(src, dir.resolve("binwatch." + (i + 1) + ".log"), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		if (maxFiles > 1) {
			Files.move(logFile, dir.resolve("binwatch.1.log"), StandardCopyOption.REPLACE_EXISTING);
		} else {
			Files.deleteIfExists(logFile);
		}
		openWriter();
	}

	public static Path logFile() {
		return logFile;
	}
}
