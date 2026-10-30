package dev.binwatch.standalone;

import dev.binwatch.log.BwLog;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Console output of the standalone app: one short line per event, tagged like {@code [SCAN]} or {@code [DISCORD]}.
 * The full detail goes to the log file; WARN and ERROR lines from the shared code are echoed here as well, and
 * everything else only with {@code --verbose}.
 */
final class ConsoleOut {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private final PrintStream out;
	private final boolean verbose;

	ConsoleOut(PrintStream out, boolean verbose) {
		this.out = out;
		this.verbose = verbose;
	}

	boolean verbose() {
		return verbose;
	}

	void line(String tag, String msg) {
		String text = LocalTime.now().format(TIME) + " [" + tag + "] " + ascii(msg);
		synchronized (out) {
			out.println(text);
		}
	}

	/** Windows consoles often cannot show these, so the console gets ASCII stand-ins (Discord keeps the originals). */
	static String ascii(String s) {
		return s.replace(" · ", " | ").replace("·", "|").replace("→", "->").replace("µs", "us").replace("±", "+/-")
				.replace("≥", ">=").replace("…", "...");
	}

	/** Output for {@link BwLog#setOutput}; the lines are already redacted. */
	void fromLog(BwLog.Level level, String msg) {
		switch (level) {
			case ERROR -> line("ERROR", msg);
			case WARN -> line("WARN", msg);
			case INFO -> {
				if (verbose) {
					line("LOG", msg);
				}
			}
			case DEBUG -> {
				if (verbose) {
					line("DEBUG", msg);
				}
			}
		}
	}
}
