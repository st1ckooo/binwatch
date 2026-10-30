package dev.binwatch.client;

import dev.binwatch.service.BinWatchService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/** Fabric client entrypoint for BIN Watch. */
public final class BinWatchClient implements ClientModInitializer {
	private static BinWatchService service;
	private static volatile boolean openGuiNextTick;

	@Override
	public void onInitializeClient() {
		FabricLoader loader = FabricLoader.getInstance();
		Path configDir = loader.getConfigDir().resolve("binwatch");
		Path dataDir = loader.getGameDir().resolve("binwatch");
		Path logDir = loader.getGameDir().resolve("logs").resolve("binwatch");
		service = new BinWatchService(configDir, dataDir, logDir, Clock.systemUTC());
		service.init();
		service.setFlipListener(ChatOut::flip);
		FlipOverlay flipOverlay = new FlipOverlay();
		flipOverlay.register();
		AutoView autoView = new AutoView(service, flipOverlay);
		FullReady fullReady = new FullReady(service, flipOverlay);
		service.setAutoViewListener(c -> {
			flipOverlay.remember(c);
			autoView.offer(c);
		});
		ClientSendMessageEvents.COMMAND.register(flipOverlay::onCommand);
		ClientReceiveMessageEvents.GAME.register(autoView::onGameMessage);
		// Your own Auction House messages (purchase, sale, collection) feed the profit tracker. Read-only.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return;
			}
			try {
				String text = net.minecraft.ChatFormatting.stripFormatting(message.getString());
				if (text != null && !text.startsWith("[BIN Watch]")) {
					List<String> replies = service.onGameChat(text.trim());
					if (!replies.isEmpty()) {
						ChatOut.send(replies);
					}
				}
			} catch (RuntimeException e) {
				dev.binwatch.log.BwLog.throttled(dev.binwatch.log.BwLog.Level.ERROR, "trade-chat", "Trade tracking failed", e);
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				BinWatchCommands.register(dispatcher, service, autoView, fullReady, () -> openGuiNextTick = true));

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> service.close());
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				autoView.tick(client);
				fullReady.tick(client);
			} catch (RuntimeException e) {
				dev.binwatch.log.BwLog.throttled(dev.binwatch.log.BwLog.Level.ERROR, "autoview-tick", "Auto-view failed", e);
			}
			// The chat screen closes after a command runs, so the GUI is opened on the following tick.
			if (openGuiNextTick && client.gui.screen() == null) {
				openGuiNextTick = false;
				client.gui.setScreen(new BinWatchScreen(service));
			}
		});
	}
}
