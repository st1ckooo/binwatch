package dev.binwatch;

import dev.binwatch.service.BinWatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTest {
	@Test
	void syntheticTestModeRunsOfflineAndNeverSendsToDiscord(@TempDir Path dir) {
		BinWatchService s = new BinWatchService(dir.resolve("config"), dir.resolve("data"), null, Clock.systemUTC());
		// init() is deliberately not called: test mode must work without the live collector or network.
		List<String> out = s.runSyntheticTest();
		String text = String.join("\n", out);
		assertTrue(text.startsWith("[TEST DATA]"), text);
		assertTrue(text.contains("PASSES filters"), text);
		assertTrue(text.contains("SYNTHETIC_NOT_SENT"), text);
		assertTrue(text.contains("Pipeline OK"), text);
		assertFalse(s.ready(), "synthetic run must not open the live database");
	}
}
