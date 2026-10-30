package dev.binwatch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.binwatch.api.ScriptedTransport;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.config.Secrets;
import dev.binwatch.discord.DiscordBot;
import dev.binwatch.discord.DiscordFeed;
import dev.binwatch.discord.FlipEmbed;
import dev.binwatch.discord.SummaryPublisher;
import dev.binwatch.discord.WebhookClient;
import dev.binwatch.store.ObservationStore;
import dev.binwatch.util.Redactor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordBotTest {
	/** Fake token with the shape of a real one; the transport is scripted, so nothing leaves the machine. */
	static final String FAKE_TOKEN = "MTIzNDU2Nzg5MDEyMzQ1Njc4OQ.GabcDE.abcdefghijklmnopqrstuvwxyz0123456789AB";
	static final String CHANNEL = "123456789012345678";
	final MutableClock clock = new MutableClock(1_793_310_000_000L);

	@Test
	void alertsGoToABotChannelWithTheBotToken() {
		ScriptedTransport t = new ScriptedTransport().enqueue("/channels/" + CHANNEL + "/messages",
				() -> ScriptedTransport.ok("{\"id\":\"999\"}"));
		DiscordBot bot = new DiscordBot(t, () -> FAKE_TOKEN);
		String target = bot.target(CHANNEL);
		assertEquals("channel:" + CHANNEL, target);
		assertNull(bot.target(""), "no channel id: fall back to the webhook");
		assertNull(new DiscordBot(t, () -> "").target(CHANNEL), "no token: fall back to the webhook");

		WebhookClient.Result r = new WebhookClient(t, bot).send(target, FlipEmbed.testPayload(clock.millis()),
				Duration.ofSeconds(5), clock.millis());
		assertTrue(r.delivered(), r.detail());
		assertEquals("POST", t.methods.get(0));
		assertEquals("https://discord.com/api/v10/channels/" + CHANNEL + "/messages", t.requests.get(0).toString());
		assertEquals("Bot " + FAKE_TOKEN, t.requestHeaders.get(0).get("Authorization"));
		JsonObject sent = JsonParser.parseString(t.postedBodies.get(0)).getAsJsonObject();
		assertFalse(sent.has("username"), "bots cannot set a per-message name");
		assertTrue(sent.has("embeds"));
	}

	@Test
	void summaryInABotChannelIsPostedThenEdited() throws Exception {
		try (ObservationStore store = ObservationStore.openInMemory("bot-summary-" + UUID.randomUUID())) {
			ScriptedTransport t = new ScriptedTransport()
					.enqueue(CHANNEL + "/messages", () -> ScriptedTransport.ok("{\"id\":\"555\"}"))
					.enqueue("/messages/555", () -> ScriptedTransport.ok("{}"));
			DiscordBot bot = new DiscordBot(t, () -> FAKE_TOKEN);
			BinWatchConfig cfg = new BinWatchConfig();
			SummaryPublisher p = new SummaryPublisher(new WebhookClient(t, bot), clock, () -> cfg, () -> bot.target(CHANNEL),
					store, List::of, () -> "attr");
			assertTrue(p.publish(true).startsWith("posted"));
			assertTrue(p.publish(true).startsWith("updated"));
			assertEquals(List.of("POST", "PATCH"), t.methods);
			assertTrue(t.requests.get(1).getPath().endsWith("/channels/" + CHANNEL + "/messages/555"));
		}
	}

	@Test
	void feedPostsInTheBackground() throws Exception {
		ScriptedTransport t = new ScriptedTransport().whenEmpty("/messages", u -> ScriptedTransport.ok("{\"id\":\"1\"}"));
		DiscordBot bot = new DiscordBot(t, () -> FAKE_TOKEN);
		DiscordFeed feed = new DiscordFeed(new WebhookClient(t, bot), clock::millis, Duration.ofSeconds(5));
		feed.post(bot.target(CHANNEL), DiscordFeed.embed("Sold: Juju Shortbow", "profit +1.2M", 0x2ECC71, clock.millis()));
		feed.post(null, DiscordFeed.embed("ignored", "no channel", 0, clock.millis()));
		feed.shutdown();
		assertEquals(1, t.requests.size());
		assertTrue(t.postedBodies.get(0).contains("Sold: Juju Shortbow"));
	}

	@Test
	void tokenIsValidatedAndNeverShown() {
		assertNull(Secrets.validateBotToken(FAKE_TOKEN));
		assertNotNull(Secrets.validateBotToken("not a token"));
		String problem = Secrets.validateBotToken("abc.def");
		assertFalse(problem.contains("abc.def"), "the reason never echoes the token");
		Secrets s = new Secrets();
		s.discordBotToken = FAKE_TOKEN;
		assertFalse(s.toString().contains(FAKE_TOKEN));
		assertEquals("Authorization failed for [REDACTED-TOKEN] (HTTP 401)",
				Redactor.redact("Authorization failed for " + FAKE_TOKEN + " (HTTP 401)"));
	}
}
