package dev.binwatch.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.binwatch.compare.TimingReport;
import dev.binwatch.service.BinWatchService;
import dev.binwatch.util.Redactor;
import dev.binwatch.util.TimeFmt;
import dev.binwatch.value.FlipCandidate;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Client-side {@code /binwatch} commands. They run locally and are never sent to the server. */
final class BinWatchCommands {
	private BinWatchCommands() {
	}

	static void register(CommandDispatcher<FabricClientCommandSource> d, BinWatchService s, AutoView autoView, FullReady fullReady,
			Runnable openGui) {
		d.register(literal("binwatch")
				.executes(c -> reply(c, help()))
				.then(literal("help").executes(c -> reply(c, help())))
				.then(literal("status").executes(c -> reply(c, s.statusLines())))
				.then(literal("start").executes(c -> reply(c, List.of(s.startApi()))))
				.then(literal("stop").executes(c -> reply(c, List.of(s.stopApi()))))
				.then(literal("top")
						.executes(c -> reply(c, top(s, 5)))
						.then(argument("count", IntegerArgumentType.integer(1, 20))
								.executes(c -> reply(c, top(s, IntegerArgumentType.getInteger(c, "count"))))))
				.then(literal("stats")
						.executes(c -> async(c, () -> stats(s, 24)))
						.then(argument("hours", IntegerArgumentType.integer(1, 24 * 30))
								.executes(c -> async(c, () -> stats(s, IntegerArgumentType.getInteger(c, "hours"))))))
				.then(literal("report")
						.executes(c -> async(c, () -> report(s, 24)))
						.then(argument("hours", IntegerArgumentType.integer(1, 24 * 30))
								.executes(c -> async(c, () -> report(s, IntegerArgumentType.getInteger(c, "hours"))))))
				.then(literal("export").executes(c -> async(c, () -> export(s))))
				.then(literal("testwebhook").executes(c -> testWebhook(c, s, false))
						.then(literal("preview").executes(c -> testWebhook(c, s, true))))
				.then(literal("reload").executes(c -> reply(c, s.reload())))
				.then(literal("diag").executes(c -> reply(c, s.diagnosticsLines())))
				.then(literal("autoview")
						.executes(c -> reply(c, autoView.status()))
						.then(literal("on").executes(c -> reply(c, autoView.setEnabled(true))))
						.then(literal("off").executes(c -> reply(c, autoView.setEnabled(false))))
						.then(literal("status").executes(c -> reply(c, autoView.status()))))
				.then(literal("autoopen")
						.executes(c -> reply(c, autoView.status()))
						.then(literal("on").executes(c -> reply(c, autoView.setEnabled(true))))
						.then(literal("off").executes(c -> reply(c, autoView.setEnabled(false))))
						.then(literal("status").executes(c -> reply(c, autoView.status()))))
				.then(literal("fullready")
						.executes(c -> reply(c, fullReady.status()))
						.then(literal("on").executes(c -> reply(c, fullReady.setEnabled(true))))
						.then(literal("off").executes(c -> reply(c, fullReady.setEnabled(false))))
						.then(literal("status").executes(c -> reply(c, fullReady.status()))))
				.then(literal("gui").executes(c -> {
					openGui.run();
					return 1;
				}))
				.then(literal("profit").executes(c -> reply(c, s.profitLines())))
				.then(literal("summary").executes(c -> async(c, () -> List.of("Profit summary: " + s.publishSummary().get()))))
				.then(literal("testmode").executes(c -> async(c, s::runSyntheticTest))));
	}

	static List<String> help() {
		return List.of(
				"Commands (all local, read-only):",
				"/binwatch status | diag - source status and diagnostics",
				"/binwatch start | stop - start/stop the public-API collector",
				"/binwatch top [n] - best current candidate flips",
				"/binwatch stats [hours] - observation timing statistics",
				"/binwatch report [hours] - write a timing report file",
				"/binwatch export - write CSV exports",
				"/binwatch testwebhook - send one test message to your Discord webhook",
				"/binwatch testwebhook preview - send a sample flip alert marked [TEST DATA] to preview the layout",
				"/binwatch reload - reload config.json and secrets.json",
				"/binwatch gui - open the status screen",
				"/binwatch autoopen on|off|status - open each alerted flip with /viewauction for you (never buys; autoview also works)",
				"/binwatch fullready on|off - put the cursor on Buy Item Right Now, then Confirm (you click; off by default)",
				"/binwatch profit - profit from your tracked flips (from your own AH chat messages)",
				"/binwatch summary - update the live profit summary in Discord now",
				"/binwatch testmode - run the pipeline on synthetic [TEST] data (no network, no Discord)");
	}

	private static List<String> top(BinWatchService s, int n) {
		if (!s.ready()) {
			return List.of(s.notReadyReason());
		}
		List<FlipCandidate> list = s.topCandidates(n);
		if (list.isEmpty()) {
			return List.of("No candidates with positive estimated profit yet. Valuations need at least one complete API snapshot.");
		}
		List<String> out = new ArrayList<>();
		out.add("Top candidates (estimates, not guaranteed):");
		for (FlipCandidate c : list) {
			out.add(String.format(Locale.ROOT, "%s %s - BIN %s, est. resale %s, est. net %s, %.0f%% off, conf %.2f%s",
					c.passed() ? "§a[PASS]§r" : "§7[----]§r", c.itemName(), TimeFmt.shortCoins(c.price()),
					TimeFmt.shortCoins(c.estimatedResale()), TimeFmt.shortCoins(c.estimatedNetProfit()), c.discountPercent(),
					c.valuation().confidence(), c.passed() ? "" : " (" + String.join("; ", c.rejectReasons()) + ")"));
			out.add("   /viewauction " + c.auctionUuid().toString().replace("-", ""));
		}
		return out;
	}

	private static List<String> stats(BinWatchService s, int hours) throws Exception {
		if (!s.ready()) {
			return List.of(s.notReadyReason());
		}
		TimingReport r = s.timingReport(hours * 3_600_000L);
		return r.toLines();
	}

	private static List<String> report(BinWatchService s, int hours) throws Exception {
		if (!s.ready()) {
			return List.of(s.notReadyReason());
		}
		Path file = s.writeReport(hours * 3_600_000L);
		List<String> out = new ArrayList<>(s.timingReport(hours * 3_600_000L).toLines());
		out.add("Report written to " + file);
		return out;
	}

	private static List<String> export(BinWatchService s) throws Exception {
		if (!s.ready()) {
			return List.of(s.notReadyReason());
		}
		List<String> out = new ArrayList<>();
		out.add("CSV export written:");
		for (Path p : s.exportCsv()) {
			out.add("  " + p);
		}
		return out;
	}

	private static int testWebhook(CommandContext<FabricClientCommandSource> c, BinWatchService s, boolean preview) {
		String what = preview ? "Sample alert" : "Webhook test";
		c.getSource().sendFeedback(Component.literal(ChatOut.PREFIX + (preview ? "Sending sample alert..." : "Sending webhook test...")));
		s.testWebhook(preview).thenAccept(r -> ChatOut.send(r.delivered()
				? what + " delivered (Discord answered " + r.detail() + ")"
				: what + " NOT delivered: " + r.outcome() + " - " + r.detail()));
		return 1;
	}

	private interface Task {
		List<String> run() throws Exception;
	}

	private static int async(CommandContext<FabricClientCommandSource> c, Task task) {
		c.getSource().sendFeedback(Component.literal(ChatOut.PREFIX + "Working..."));
		CompletableFuture.runAsync(() -> {
			try {
				ChatOut.send(task.run());
			} catch (Exception e) {
				ChatOut.send("Failed: " + e);
			}
		});
		return 1;
	}

	private static int reply(CommandContext<FabricClientCommandSource> c, List<String> lines) {
		for (String line : lines) {
			c.getSource().sendFeedback(Component.literal(ChatOut.PREFIX + Redactor.redact(line)));
		}
		return 1;
	}
}
