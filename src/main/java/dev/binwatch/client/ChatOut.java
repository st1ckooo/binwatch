package dev.binwatch.client;

import dev.binwatch.log.BwLog;
import dev.binwatch.util.Redactor;
import dev.binwatch.discord.FlipEmbed;
import dev.binwatch.util.TimeFmt;
import dev.binwatch.value.FlipCandidate;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.List;
import java.util.Locale;

/** Thread-safe chat output for results that arrive asynchronously. All text is redacted before display. */
final class ChatOut {
	static final String PREFIX = "§6[BIN Watch]§r ";

	private ChatOut() {
	}

	static void send(List<String> lines) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			for (String line : lines) {
				String safe = Redactor.redact(line);
				if (mc.player != null) {
					mc.player.sendSystemMessage(Component.literal(PREFIX + safe));
				} else {
					BwLog.info(safe);
				}
			}
		});
	}

	static void send(String line) {
		send(List.of(line));
	}

	/**
	 * Shows an alerted flip in the player's own chat. [VIEW] runs /viewauction only when the player clicks it (vanilla
	 * may ask for confirmation first); [COPY] copies the command. Nothing is sent to the server by showing the message.
	 */
	static void flip(FlipCandidate c) {
		String command = "/viewauction " + c.auctionUuid().toString().replace("-", "");
		int rarity = FlipEmbed.accentColor(new FlipEmbed.Style(0xFFFFFF, true, null), c.item());
		String name = c.itemName() == null ? "Unknown item" : c.itemName().trim();
		MutableComponent msg = Component.literal(PREFIX + "Flip: ")
				.append(Component.literal(name).withStyle(Style.EMPTY.withColor(rarity)))
				.append(Component.literal(" " + TimeFmt.shortCoins(c.price())).withStyle(Style.EMPTY.withColor(0xFFFFFF)))
				.append(Component.literal(" → est. +" + TimeFmt.shortCoins(c.effectiveNetProfit())
						+ (c.market() != null && c.market().available()
								? " (list " + TimeFmt.shortCoins(c.market().listPrice()) + " " + c.market().listHours() + "h)" : ""))
						.withStyle(Style.EMPTY.withColor(0x55FF55)))
				.append(Component.literal(String.format(Locale.ROOT, " (%.1f%%, conf %.0f%%) ", c.discountPercent(),
						c.valuation().confidence() * 100)).withStyle(Style.EMPTY.withColor(0xAAAAAA)))
				.append(Component.literal("[VIEW]").withStyle(Style.EMPTY.withColor(0x55FF55).withBold(true)
						.withClickEvent(new ClickEvent.RunCommand(command))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to open this auction\n" + command)))))
				.append(Component.literal(" "))
				.append(Component.literal("[COPY]").withStyle(Style.EMPTY.withColor(0x55FFFF)
						.withClickEvent(new ClickEvent.CopyToClipboard(command))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy " + command)))));
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.sendSystemMessage(msg);
			}
		});
	}
}
