package dev.binwatch.client;

import dev.binwatch.service.BinWatchService;
import dev.binwatch.util.Redactor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Small status and control screen, opened with {@code /binwatch gui}. Mirrors the commands. */
final class BinWatchScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFAAAAAA;

	private final BinWatchService service;
	private List<String> lines = List.of();
	private String message = "";
	private int ticks;
	private Button apiButton;

	BinWatchScreen(BinWatchService service) {
		super(Component.literal("BIN Watch"));
		this.service = service;
	}

	@Override
	protected void init() {
		int w = 120;
		int gap = 4;
		int total = w * 3 + gap * 2;
		int x = (this.width - total) / 2;
		int y = this.height - 52;
		apiButton = addRenderableWidget(Button.builder(Component.literal(apiLabel()), b -> {
			message = service.apiRunning() ? service.stopApi() : service.startApi();
			refresh();
		}).bounds(x, y, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Test Discord webhook"), b -> {
			message = "Sending webhook test...";
			service.testWebhook().thenAccept(r -> message = r.delivered()
					? "Webhook test delivered (" + r.detail() + ")"
					: "Webhook test NOT delivered: " + r.outcome() + " - " + r.detail());
		}).bounds(x + w + gap, y, w, 20).build());
		y += 24;
		addRenderableWidget(Button.builder(Component.literal("Export CSV"), b -> {
			message = "Exporting...";
			CompletableFuture.runAsync(() -> {
				try {
					List<Path> files = service.exportCsv();
					message = "Exported " + files.size() + " files to " + files.get(0).getParent();
				} catch (Exception e) {
					message = "Export failed: " + e.getMessage();
				}
			});
		}).bounds(x, y, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reload config"), b -> {
			message = String.join(" | ", service.reload());
			refresh();
		}).bounds(x + w + gap, y, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
				.bounds(x + 2 * (w + gap), y, w, 20).build());
		refresh();
	}

	private String apiLabel() {
		return service.apiRunning() ? "Stop API collector" : "Start API collector";
	}

	private void refresh() {
		lines = new ArrayList<>(service.statusLines());
		if (apiButton != null) {
			apiButton.setMessage(Component.literal(apiLabel()));
		}
	}

	@Override
	public void tick() {
		if (++ticks % 20 == 0) {
			refresh();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(this.font, this.title, this.width / 2, 10, WHITE);
		int y = 28;
		int maxWidth = this.width - 20;
		for (String line : lines) {
			String safe = Redactor.redact(line);
			if (this.font.width(safe) > maxWidth) {
				safe = this.font.plainSubstrByWidth(safe, maxWidth - 6) + "...";
			}
			g.text(this.font, safe, 10, y, line.startsWith("  ") ? GREY : WHITE);
			y += 11;
			if (y > this.height - 70) {
				break;
			}
		}
		if (!message.isEmpty()) {
			String m = Redactor.redact(message);
			if (this.font.width(m) > maxWidth) {
				m = this.font.plainSubstrByWidth(m, maxWidth - 6) + "...";
			}
			g.centeredText(this.font, m, this.width / 2, this.height - 66, 0xFFFFFF55);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
