package dev.binwatch.client;

import dev.binwatch.api.AuctionCollector;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.log.BwLog;
import dev.binwatch.service.BinWatchService;
import dev.binwatch.value.FlipCandidate;
import dev.binwatch.view.ViewClassifier;
import dev.binwatch.view.ViewClassifier.Result;
import dev.binwatch.view.ViewRecorder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Auto-open: opens each alerted flip with /viewauction so you can decide and buy it yourself, and records whether it was
 * still for sale when the menu arrived (the speed check). It only sends /viewauction (and /play skyblock or /lobby to
 * get into SkyBlock); it never clicks a slot, so it cannot buy or bid. A Confirm Purchase screen is never replaced.
 * All state is touched on the client thread only.
 */
final class AutoView {
	private static final int PLAYER_INVENTORY_SLOTS = 36;
	private static final int MAX_QUEUE = 10;
	private static final long JOIN_RETRY_MS = 15_000;
	/** Hypixel fills the menu, then refreshes it ("Seller: Refreshing..."); judge the refreshed state, at most this late. */
	private static final long SETTLE_MS = 1500;

	private record Pending(FlipCandidate candidate) {
	}

	private static final class Active {
		final FlipCandidate candidate;
		final long sentAtMs;
		/** The screen open when the command was sent; it must not be mistaken for the answer. */
		final Screen screenAtSend;
		final List<String> chat = new ArrayList<>();
		long loadedAtMs;
		Result firstResult;

		Active(FlipCandidate candidate, long sentAtMs, Screen screenAtSend) {
			this.candidate = candidate;
			this.sentAtMs = sentAtMs;
			this.screenAtSend = screenAtSend;
		}
	}

	private final BinWatchService service;
	private final FlipOverlay overlay;
	private final ViewRecorder recorder;
	private final Deque<Pending> queue = new ArrayDeque<>();
	private Active active;
	private long lastCommandMs;
	private long lastJoinMs;
	private int joinAttempts;
	/** Runtime on/off from the command; null follows the config. Cleared when the config value changes. */
	private Boolean override;
	private boolean lastConfigEnabled;

	AutoView(BinWatchService service, FlipOverlay overlay) {
		this.service = service;
		this.overlay = overlay;
		this.recorder = new ViewRecorder(service.dataDir().resolve("exports").resolve("autoview_checks.csv"));
	}

	private BinWatchConfig.AutoView cfg() {
		return service.config().autoView;
	}

	boolean enabled() {
		boolean configured = cfg().enabled;
		if (configured != lastConfigEnabled) {
			lastConfigEnabled = configured;
			override = null;
		}
		return override != null ? override : configured;
	}

	/** Called from the valuation thread for every alerted flip. */
	void offer(FlipCandidate c) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (!enabled()) {
				return;
			}
			queue.addLast(new Pending(c));
			while (queue.size() > MAX_QUEUE) {
				Pending dropped = queue.removeFirst();
				record(dropped.candidate(), Result.SKIPPED_STALE, null, System.currentTimeMillis(), "", null, "", List.of(),
						List.of(), List.of());
			}
		});
	}

	List<String> setEnabled(boolean on) {
		override = on;
		if (!on) {
			queue.clear();
			active = null;
		}
		BwLog.info("Auto-open turned " + (on ? "on" : "off") + " by command");
		return List.of("Auto-open " + (on ? "ON: alerted flips open with /viewauction; you decide and buy (nothing is clicked)"
				: "OFF"));
	}

	List<String> status() {
		List<String> out = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		out.add("Auto-open: " + (enabled() ? "ON" : "OFF") + ", " + (onHypixel(mc) ? inSkyblock(mc) ? "in SkyBlock"
				: "on Hypixel, not in SkyBlock" : "not on Hypixel") + ", queued " + queue.size()
				+ (active != null ? ", waiting for a result" : ""));
		out.addAll(recorder.summaryLines());
		return out;
	}

	/** Game (server) chat message; used to catch replies such as "This auction wasn't found!". */
	void onGameMessage(Component message, boolean overlay) {
		if (overlay || active == null) {
			return;
		}
		String text = strip(message.getString());
		if (text.isBlank() || text.startsWith("[BIN Watch]")) {
			return;
		}
		if (active.chat.size() < 5) {
			active.chat.add(text);
		}
		Result r = ViewClassifier.classifyChat(text);
		if (r != null) {
			finish(r, System.currentTimeMillis(), "", "", null, "", List.of(), List.of());
		}
	}

	void tick(Minecraft mc) {
		if (!enabled()) {
			return;
		}
		if (mc.player == null || mc.getConnection() == null || !onHypixel(mc)) {
			return;
		}
		long now = System.currentTimeMillis();
		if (active != null) {
			checkScreen(mc, now);
			return;
		}
		if (!inSkyblock(mc)) {
			maybeJoinSkyblock(mc, now);
			return;
		}
		joinAttempts = 0;
		// Opening the auction replaces whatever screen is open, chat included: waiting for chat to close skewed timings.
		if (queue.isEmpty() || now - lastCommandMs < cfg().minGapMs) {
			return;
		}
		Screen open = mc.gui.screen();
		if (open != null && strip(open.getTitle().getString()).toLowerCase(Locale.ROOT).contains("confirm")) {
			return; // you are confirming a purchase: never pull that screen away
		}
		FlipCandidate c = queue.removeFirst().candidate();
		if (now - c.firstApiObservedMs() > cfg().maxAgeSeconds * 1000L) {
			record(c, Result.SKIPPED_STALE, null, now, "", null, "", List.of(), List.of(), List.of());
			return;
		}
		String uuid = c.auctionUuid().toString().replace("-", "");
		mc.getConnection().sendCommand("viewauction " + uuid);
		lastCommandMs = now;
		active = new Active(c, now, mc.gui.screen());
		overlay.show(c);
		BwLog.info("Auto-view: sent /viewauction " + uuid + " for " + c.itemName());
	}

	private void checkScreen(Minecraft mc, long now) {
		Screen s = mc.gui.screen();
		if (s != active.screenAtSend && s instanceof AbstractContainerScreen<?> cs) {
			String title = strip(cs.getTitle().getString());
			if (title.toLowerCase(Locale.ROOT).contains("auction")) {
				AbstractContainerMenu menu = cs.getMenu();
				int containerSlots = Math.max(0, menu.slots.size() - PLAYER_INVENTORY_SLOTS);
				ItemStack button = ItemStack.EMPTY;
				boolean loaded = false;
				for (int i = 0; i < containerSlots; i++) {
					ItemStack st = menu.getSlot(i).getItem();
					if (st.isEmpty()) {
						continue;
					}
					loaded = true;
					String name = strip(st.getHoverName().getString()).toLowerCase(Locale.ROOT);
					if (name.contains("buy item right now") || name.contains("collect auction")
							|| name.contains("cancel auction") || name.contains("submit bid")) {
						button = st;
						break;
					}
				}
				if (button.isEmpty() && containerSlots > 31) {
					button = menu.getSlot(31).getItem();
				}
				if (loaded && !button.isEmpty()) {
					ItemStack item = containerSlots > 13 ? menu.getSlot(13).getItem() : ItemStack.EMPTY;
					String buttonId = BuiltInRegistries.ITEM.getKey(button.getItem()).toString();
					String buttonName = strip(button.getHoverName().getString());
					List<String> buttonLore = lore(button);
					List<String> itemLore = lore(item);
					Result r = ViewClassifier.classifyView(title, buttonId, buttonName, buttonLore, itemLore);
					if (active.loadedAtMs == 0) {
						active.loadedAtMs = now;
						active.firstResult = r;
					}
					boolean refreshing = itemLore.stream().anyMatch(l -> l.toLowerCase(Locale.ROOT).contains("refreshing"));
					if (!refreshing || now - active.loadedAtMs >= SETTLE_MS) {
						List<String> status = new ArrayList<>();
						status.add("Item: " + strip(item.getHoverName().getString()));
						status.addAll(statusLines(itemLore));
						if (active.firstResult != r) {
							status.add("First frame: " + active.firstResult);
						}
						finish(r, active.loadedAtMs, title, title, buttonId, buttonName, buttonLore, status);
					}
					return;
				}
			}
		}
		if (now - active.sentAtMs > cfg().resultTimeoutMs) {
			String title = s == null ? "" : strip(s.getTitle().getString());
			finish(Result.NO_RESPONSE, now, title + (s == active.screenAtSend ? " (screen from before the command)" : ""),
					title, null, "", List.of(), List.of());
		}
	}

	/** @param resultAtMs when the answer arrived: the menu's first fill, or the chat reply */
	private void finish(Result r, long resultAtMs, String note, String title, String buttonId, String buttonName,
			List<String> buttonLore, List<String> itemStatusLore) {
		Active a = active;
		active = null;
		if (a == null) {
			return;
		}
		record(a.candidate, r, a.sentAtMs, resultAtMs, note, buttonId, buttonName, buttonLore, itemStatusLore, a.chat);
	}

	private void record(FlipCandidate c, Result r, Long sentAtMs, long now, String title, String buttonId,
			String buttonName, List<String> buttonLore, List<String> itemStatusLore, List<String> chat) {
		AuctionCollector.PublishEstimate pub = c.snapshotLastUpdatedMs() == null ? null
				: service.publishEstimate(c.snapshotLastUpdatedMs());
		Long cmdAfterPublish = pub == null || sentAtMs == null ? null : sentAtMs - pub.publishedLocalMs();
		Long resultAfterPublish = pub == null || sentAtMs == null ? null : now - pub.publishedLocalMs();
		Long resultAfterCommand = sentAtMs == null ? null : now - sentAtMs;
		long pageToCommand = (sentAtMs == null ? now : sentAtMs) - c.firstApiObservedMs();
		recorder.record(new ViewRecorder.Check(now, c.auctionUuid(), c.itemName(), c.price(), c.estimatedNetProfit(), r,
				cmdAfterPublish, resultAfterPublish, resultAfterCommand, pageToCommand,
				pub == null ? null : pub.uncertaintyMs(), title, buttonId, buttonName, buttonLore, itemStatusLore,
				List.copyOf(chat)));
		String when = resultAfterPublish != null ? AuctionCollector.secs(resultAfterPublish) + " after Hypixel published"
				: resultAfterCommand != null ? AuctionCollector.secs(resultAfterCommand) + " after the command" : "";
		String verdict = r.stillForSale == null ? r.name() : r.stillForSale ? "STILL FOR SALE (" + r.name() + ")"
				: "ALREADY GONE (" + r.name() + ")";
		BwLog.info("Auto-view result for " + c.auctionUuid() + " " + c.itemName() + ": " + r
				+ (when.isEmpty() ? "" : ", " + when) + "; button " + buttonId + " '" + buttonName + "' "
				+ buttonLore + "; status " + itemStatusLore + "; chat " + chat);
		ChatOut.send("Auto-open: " + (c.itemName() == null ? "item" : c.itemName().trim()) + " -> " + verdict
				+ (when.isEmpty() ? "" : ", " + when));
	}

	private void maybeJoinSkyblock(Minecraft mc, long now) {
		if (!cfg().autoJoinSkyblock || now - lastJoinMs < JOIN_RETRY_MS) {
			return;
		}
		lastJoinMs = now;
		joinAttempts++;
		// From limbo /play may be refused; every third try goes through the lobby first.
		String command = joinAttempts % 3 == 0 ? "lobby" : "play skyblock";
		mc.getConnection().sendCommand(command);
		BwLog.info("Auto-view: not in SkyBlock, sent /" + command);
		ChatOut.send("Auto-open: not in SkyBlock, sent /" + command + " (stop with /binwatch autoopen off)");
	}

	static boolean onHypixel(Minecraft mc) {
		ServerData sd = mc.getCurrentServer();
		return sd != null && sd.ip != null && sd.ip.toLowerCase(Locale.ROOT).contains("hypixel");
	}

	/** The SkyBlock sidebar is titled "SKYBLOCK" (also "SKYBLOCK CO-OP" / "SKYBLOCK GUEST"). */
	static boolean inSkyblock(Minecraft mc) {
		if (mc.level == null) {
			return false;
		}
		Objective o = mc.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
		return o != null && strip(o.getDisplayName().getString()).toUpperCase(Locale.ROOT).contains("SKYBLOCK");
	}

	private static List<String> lore(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return List.of();
		}
		ItemLore l = stack.get(DataComponents.LORE);
		if (l == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (Component c : l.lines()) {
			out.add(strip(c.getString()));
		}
		return out;
	}

	/** The auction lines Hypixel appends to the item lore (seller, price, status, time left), for the record. */
	private static List<String> statusLines(List<String> itemLore) {
		List<String> out = new ArrayList<>();
		for (String line : itemLore) {
			String l = line.toLowerCase(Locale.ROOT);
			if (l.startsWith("seller:") || l.startsWith("buy it now:") || l.startsWith("status:") || l.startsWith("ends in:")
					|| l.startsWith("ended") || l.contains("sold") || l.contains("expired") || l.startsWith("starting bid:")) {
				out.add(line);
			}
		}
		return out;
	}

	private static String strip(String s) {
		String r = ChatFormatting.stripFormatting(s);
		return r == null ? "" : r.trim();
	}
}
