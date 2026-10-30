package dev.binwatch.client;

import dev.binwatch.market.MarketCheck;
import dev.binwatch.trade.TradeChatParser;
import dev.binwatch.util.TimeFmt;
import dev.binwatch.value.FlipCandidate;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Draws the flip's expected profit and listing advice above the Auction House menu of an alerted flip, whether it was
 * opened automatically or with [VIEW]. Display only: it reads the menu, it never clicks.
 */
final class FlipOverlay {
	/** How long after /viewauction the banner may still show (covers the Confirm Purchase screen too). */
	private static final long SHOW_MS = 120_000;
	private static final int PLAYER_INVENTORY_SLOTS = 36;

	private final Map<UUID, FlipCandidate> recent = new LinkedHashMap<>(64, 0.75f, false) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, FlipCandidate> e) {
			return size() > 200;
		}
	};
	private volatile FlipCandidate target;
	private volatile long targetAtMs;

	void register() {
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (screen instanceof AbstractContainerScreen<?>) {
				ScreenEvents.afterExtract(screen).register(this::draw);
			}
		});
	}

	/** Every alerted flip, so a /viewauction typed or clicked later can be matched to it. */
	synchronized void remember(FlipCandidate c) {
		recent.put(c.auctionUuid(), c);
	}

	/** A /viewauction command is being sent (by the player, [VIEW] or auto-open). */
	void onCommand(String command) {
		if (command == null || !command.toLowerCase(Locale.ROOT).startsWith("viewauction ")) {
			return;
		}
		UUID uuid = parseUuid(command.substring("viewauction ".length()).trim());
		FlipCandidate c;
		synchronized (this) {
			c = uuid == null ? null : recent.get(uuid);
		}
		if (c != null) {
			show(c);
		}
	}

	/** The flip whose auction was opened most recently, while still fresh; otherwise null. */
	FlipCandidate currentTarget() {
		FlipCandidate c = target;
		return c != null && System.currentTimeMillis() - targetAtMs <= SHOW_MS ? c : null;
	}

	void show(FlipCandidate c) {
		target = c;
		targetAtMs = System.currentTimeMillis();
	}

	private void draw(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		FlipCandidate c = target;
		if (c == null || System.currentTimeMillis() - targetAtMs > SHOW_MS
				|| !(screen instanceof AbstractContainerScreen<?> cs)) {
			return;
		}
		String title = strip(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
		boolean view = title.contains("auction view");
		if (!view && !title.contains("confirm purchase")) {
			return;
		}
		AbstractContainerMenu menu = cs.getMenu();
		int containerSlots = Math.max(0, menu.slots.size() - PLAYER_INVENTORY_SLOTS);
		if (view) {
			// Only for the flip's own auction: the item in the middle slot must have the same name.
			ItemStack item = containerSlots > 13 ? menu.getSlot(13).getItem() : ItemStack.EMPTY;
			if (item.isEmpty() || !TradeChatParser.nameKey(strip(item.getHoverName().getString()))
					.equals(TradeChatParser.nameKey(c.itemName()))) {
				return;
			}
		}
		List<String> lines = lines(c);
		int[] colors = {c.effectiveNetProfit() >= 0 ? 0xFF55FF55 : 0xFFFF5555, 0xFFDDDDDD};
		Font font = Minecraft.getInstance().font;
		int textW = 0;
		for (String l : lines) {
			textW = Math.max(textW, font.width(l));
		}
		int rows = containerSlots / 9;
		int imageH = 114 + rows * 18;
		int top = (screen.height - imageH) / 2;
		int left = (screen.width - 176) / 2;
		int boxH = lines.size() * 11 + 6;
		int x;
		int y;
		if (top >= boxH + 2) {
			x = (screen.width - textW) / 2;
			y = top - boxH - 1;
		} else if (left >= textW + 12) {
			x = left - textW - 8;
			y = Math.max(2, top + 4);
		} else {
			x = (screen.width - textW) / 2;
			y = 2;
		}
		g.fill(x - 4, y, x + textW + 4, y + boxH, 0xC0000000);
		for (int i = 0; i < lines.size(); i++) {
			g.text(font, lines.get(i), x, y + 4 + i * 11, colors[Math.min(i, colors.length - 1)], true);
		}
	}

	static List<String> lines(FlipCandidate c) {
		long net = c.effectiveNetProfit();
		String profit = "BIN Watch: " + (net >= 0 ? "+" : "-") + TimeFmt.shortCoins(Math.abs(net)) + " profit after fees";
		MarketCheck m = c.market();
		if (m != null && m.available()) {
			return List.of(profit, "List at " + TimeFmt.shortCoins(m.listPrice()) + " for " + m.listHours() + "h · median "
					+ TimeFmt.shortCoins(m.quote().median()) + " · " + String.format(Locale.ROOT, "%.0f", m.quote().dailySales())
					+ " sales/day" + (m.worthIt() ? "" : " · NOT worth it"));
		}
		return List.of(profit, "Resale ~" + TimeFmt.shortCoins(c.effectiveResale()) + " (local estimate, no market data)");
	}

	static UUID parseUuid(String s) {
		String hex = s.replace("-", "").toLowerCase(Locale.ROOT);
		if (!hex.matches("[0-9a-f]{32}")) {
			return null;
		}
		return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
	}

	private static String strip(String s) {
		String r = ChatFormatting.stripFormatting(s);
		return r == null ? "" : r.trim();
	}
}
