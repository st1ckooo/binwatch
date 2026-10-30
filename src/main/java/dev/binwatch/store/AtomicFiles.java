package dev.binwatch.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Write-to-temp-then-rename helper so a crash never leaves a half-written file behind. */
public final class AtomicFiles {
	private AtomicFiles() {
	}

	public static void writeString(Path target, String content) throws IOException {
		Path dir = target.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
		try {
			Files.writeString(tmp, content, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}
}
