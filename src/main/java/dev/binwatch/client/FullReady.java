package dev.binwatch.client;

import dev.binwatch.log.BwLog;
import dev.binwatch.service.BinWatchService;
import dev.binwatch.trade.TradeChatParser;
import dev.binwatch.value.FlipCandidate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * "Full ready": puts the mouse cursor on the button you will click next, so buying an alerted flip takes two clicks
 * without moving the mouse. On the alerted auction's menu the cursor goes to "Buy Item Right Now"; after you click it,
 * on Confirm Purchase it goes to the green confirm button. It only moves the cursor: nothing is clicked and nothing is
 * sent to the server, every click is yours. Each position is set once per flip, so it never fights your mouse.
 */
final class FullReady {
	static final int BUY_SLOT = 31;
	static final int CONFIRM_SLOT = 11;
	/** Confirm Purchase only counts if it opens this soon after the cursor was placed on the buy button. */
	private static final long CONFIRM_WINDOW_MS = 15_000;
	private static final int PLAYER_INVENTORY_SLOTS = 36;

	private final BinWatchService service;
	private final FlipOverlay overlay;
	/** Runtime on/off from the command; null follows the config. Cleared when the config value changes. */
	private Boolean override;
	private boolean lastConfig;
	private UUID buyPlacedFor;
	private long buyPlacedAtMs;
	private UUID confirmPlacedFor;

	FullReady(BinWatchService service, FlipOverlay overlay) {
		this.service = service;
		this.overlay = overlay;
	}

	boolean enabled() {
		boolean configured = service.config().autoView.fullReady;
		if (configured != lastConfig) {
			lastConfig = configured;
			override = null;
		}
		return override != null ? override : configured;
	}

	List<String> setEnabled(boolean on) {
		override = on;
		BwLog.info("Full ready turned " + (on ? "on" : "off") + " by command");
		return List.of("Full ready " + (on ? "ON: the cursor is placed on Buy Item Right Now, then on Confirm; you click"
				: "OFF"));
	}

	List<String> status() {
		return List.of("Full ready: " + (enabled() ? "ON" : "OFF")
				+ " (moves the cursor onto the buy and confirm buttons of alerted flips; never clicks)");
	}

	/** Client tick. */
	void tick(Minecraft mc) {
		if (!enabled() || !(mc.gui.screen() instanceof AbstractContainerScreen<?> cs)) {
			return;
		}
		FlipCandidate c = overlay.currentTarget();
		if (c == null) {
			return;
		}
		String title = strip(cs.getTitle().getString()).toLowerCase(Locale.ROOT);
		AbstractContainerMenu menu = cs.getMenu();
		int containerSlots = Math.max(0, menu.slots.size() - PLAYER_INVENTORY_SLOTS);
		if (title.contains("auction view")) {
			if (c.auctionUuid().equals(buyPlacedFor) || containerSlots <= BUY_SLOT) {
				return;
			}
			ItemStack item = menu.getSlot(13).getItem();
			if (item.isEmpty() || !TradeChatParser.nameKey(strip(item.getHoverName().getString()))
					.equals(TradeChatParser.nameKey(c.itemName()))) {
				return;
			}
			if (name(menu.getSlot(BUY_SLOT)).contains("buy item right now")) {
				place(mc, cs, menu.getSlot(BUY_SLOT), containerSlots);
				buyPlacedFor = c.auctionUuid();
				buyPlacedAtMs = System.currentTimeMillis();
			}
		} else if (title.contains("confirm purchase")) {
			if (!c.auctionUuid().equals(buyPlacedFor) || c.auctionUuid().equals(confirmPlacedFor)
					|| System.currentTimeMillis() - buyPlacedAtMs > CONFIRM_WINDOW_MS || containerSlots <= CONFIRM_SLOT) {
				return;
			}
			if (name(menu.getSlot(CONFIRM_SLOT)).contains("confirm")) {
				place(mc, cs, menu.getSlot(CONFIRM_SLOT), containerSlots);
				confirmPlacedFor = c.auctionUuid();
			}
		}
	}

	/** Moves the system cursor to the centre of {@code slot}; Minecraft then shows it hovered like any mouse move. */
	private static void place(Minecraft mc, Screen screen, Slot slot, int containerSlots) {
		int rows = containerSlots / 9;
		int left = (screen.width - 176) / 2;
		int top = (screen.height - (114 + rows * 18)) / 2;
		double gx = left + slot.x + 8;
		double gy = top + slot.y + 8;
		var w = mc.getWindow();
		double sx = (double) w.getScreenWidth() / w.getGuiScaledWidth();
		double sy = (double) w.getScreenHeight() / w.getGuiScaledHeight();
		GLFW.glfwSetCursorPos(w.handle(), gx * sx, gy * sy);
	}

	private static String name(Slot slot) {
		ItemStack st = slot.getItem();
		return st.isEmpty() ? "" : strip(st.getHoverName().getString()).toLowerCase(Locale.ROOT);
	}

	private static String strip(String s) {
		String r = ChatFormatting.stripFormatting(s);
		return r == null ? "" : r.trim();
	}
}
