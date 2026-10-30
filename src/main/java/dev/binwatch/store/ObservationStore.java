package dev.binwatch.store;

import dev.binwatch.log.BwLog;
import dev.binwatch.store.Records.Alert;
import dev.binwatch.store.Records.AlertStatus;
import dev.binwatch.store.Records.ApiObservation;
import dev.binwatch.store.Records.Sale;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Persistent store backed by an embedded H2 database (pure Java, transactional). First-observation rows are
 * insert-only: once a UUID has a first-seen timestamp it is never overwritten, which keeps detection history intact
 * across restarts. All methods are synchronized on a single connection; callers run on background threads.
 */
public final class ObservationStore implements AutoCloseable {
	public static final int SCHEMA_VERSION = 4;
	private static final String DUPLICATE_KEY_STATE = "23505";
	private static final int IN_CHUNK = 500;

	private final Connection conn;
	private final String description;

	private ObservationStore(Connection conn, String description) throws SQLException {
		this.conn = conn;
		this.description = description;
		migrate();
	}

	/** Opens (or creates) the on-disk database. A corrupted database is moved aside and a fresh one is created. */
	public static ObservationStore openFile(Path dir) throws SQLException, IOException {
		Files.createDirectories(dir);
		Path base = dir.resolve("binwatch").toAbsolutePath();
		String url = "jdbc:h2:file:" + base + ";DB_CLOSE_ON_EXIT=FALSE";
		try {
			return new ObservationStore(connect(url), base + ".mv.db");
		} catch (SQLException first) {
			Path file = Path.of(base + ".mv.db");
			if (!Files.exists(file)) {
				throw first;
			}
			Path aside = Path.of(base + ".corrupt-" + System.currentTimeMillis() + ".mv.db");
			BwLog.error("Database could not be opened; moving it aside to " + aside.getFileName()
					+ " and starting a fresh one. Previous history is preserved in that file.", first);
			Files.move(file, aside);
			return new ObservationStore(connect(url), base + ".mv.db");
		}
	}

	/** Private in-memory database, used for unit tests and synthetic test mode so live data is never touched. */
	public static ObservationStore openInMemory(String name) throws SQLException {
		return new ObservationStore(connect("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1"), "in-memory:" + name);
	}

	private static Connection connect(String url) throws SQLException {
		Properties props = new Properties();
		props.setProperty("user", "sa");
		props.setProperty("password", "");
		// Direct driver use avoids DriverManager class-loader issues inside the mod loader.
		Connection c = new org.h2.Driver().connect(url, props);
		if (c == null) {
			throw new SQLException("H2 driver refused URL");
		}
		return c;
	}

	public String description() {
		return description;
	}

	private synchronized void migrate() throws SQLException {
		try (Statement s = conn.createStatement()) {
			s.execute("CREATE TABLE IF NOT EXISTS meta (k VARCHAR(64) PRIMARY KEY, v VARCHAR(1024))");
		}
		String version = getMeta("schema_version");
		int current = version == null ? 0 : Integer.parseInt(version);
		if (current > SCHEMA_VERSION) {
			throw new SQLException("database schema version " + current + " is newer than this mod supports ("
					+ SCHEMA_VERSION + ")");
		}
		if (current < 1) {
			try (Statement s = conn.createStatement()) {
				s.execute("""
						CREATE TABLE IF NOT EXISTS api_obs (
						  auction_uuid UUID PRIMARY KEY,
						  item_uuid VARCHAR(64),
						  item_id VARCHAR(128),
						  item_name VARCHAR(256),
						  norm_key VARCHAR(2048),
						  tier VARCHAR(32),
						  bin BOOLEAN NOT NULL,
						  starting_bid BIGINT NOT NULL,
						  item_count INT NOT NULL,
						  auction_start_ms BIGINT,
						  auction_end_ms BIGINT,
						  first_api_observed_ms BIGINT NOT NULL,
						  snapshot_last_updated_ms BIGINT,
						  snapshot_id VARCHAR(64),
						  poll_id BIGINT,
						  page INT,
						  source_status VARCHAR(32),
						  observation_kind VARCHAR(40))""");
				s.execute("CREATE INDEX IF NOT EXISTS api_obs_item_uuid ON api_obs(item_uuid)");
				s.execute("CREATE INDEX IF NOT EXISTS api_obs_observed ON api_obs(first_api_observed_ms)");
				s.execute("CREATE INDEX IF NOT EXISTS api_obs_poll ON api_obs(poll_id)");
				s.execute("""
						CREATE TABLE IF NOT EXISTS alerts (
						  auction_uuid UUID PRIMARY KEY,
						  norm_key VARCHAR(2048),
						  status VARCHAR(20) NOT NULL,
						  created_ms BIGINT NOT NULL,
						  last_attempt_ms BIGINT,
						  attempts INT NOT NULL,
						  delivered_ms BIGINT,
						  item_name VARCHAR(256),
						  price BIGINT,
						  est_net_profit BIGINT,
						  detail VARCHAR(1024))""");
				s.execute("CREATE INDEX IF NOT EXISTS alerts_key ON alerts(norm_key)");
				s.execute("CREATE INDEX IF NOT EXISTS alerts_delivered ON alerts(delivered_ms)");
				s.execute("""
						CREATE TABLE IF NOT EXISTS sales (
						  auction_id UUID PRIMARY KEY,
						  norm_key VARCHAR(2048) NOT NULL,
						  unit_price BIGINT NOT NULL,
						  ended_ms BIGINT NOT NULL)""");
				s.execute("CREATE INDEX IF NOT EXISTS sales_key ON sales(norm_key, ended_ms)");
			}
		}
		if (current < 3) {
			// v3: the in-game observer was removed; drop its tables (v2 only added columns to them).
			try (Statement s = conn.createStatement()) {
				s.execute("DROP TABLE IF EXISTS comparisons");
				s.execute("DROP TABLE IF EXISTS client_obs");
			}
		}
		if (current < 4) {
			// v4: flips you make by hand, tracked from chat for the profit summary.
			try (Statement s = conn.createStatement()) {
				s.execute("""
						CREATE TABLE IF NOT EXISTS trades (
						  id BIGINT AUTO_INCREMENT PRIMARY KEY,
						  item_name VARCHAR(256) NOT NULL,
						  name_key VARCHAR(256) NOT NULL,
						  auction_uuid UUID,
						  bought_price BIGINT,
						  bought_ms BIGINT,
						  expected_list_price BIGINT,
						  expected_list_hours INT,
						  expected_net BIGINT,
						  listed_ms BIGINT,
						  sold_price BIGINT,
						  buyer VARCHAR(64),
						  sold_ms BIGINT,
						  collected_coins BIGINT,
						  collected_ms BIGINT,
						  profit BIGINT,
						  status VARCHAR(16) NOT NULL)""");
				s.execute("CREATE INDEX IF NOT EXISTS trades_name ON trades(name_key, status)");
			}
		}
		if (current < SCHEMA_VERSION) {
			setMeta("schema_version", Integer.toString(SCHEMA_VERSION));
		}
	}

	// ---- meta ------------------------------------------------------------------------------------------------------

	public synchronized String getMeta(String key) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
			ps.setString(1, key);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	public synchronized void setMeta(String key, String value) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("MERGE INTO meta (k, v) KEY (k) VALUES (?, ?)")) {
			ps.setString(1, key);
			ps.setString(2, value);
			ps.executeUpdate();
		}
	}

	// ---- API observations ------------------------------------------------------------------------------------------

	/** Returns the subset of {@code uuids} that already have an API first-observation row. */
	public synchronized Set<UUID> existingApiUuids(Collection<UUID> uuids) throws SQLException {
		Set<UUID> found = new HashSet<>();
		List<UUID> list = new ArrayList<>(uuids);
		for (int i = 0; i < list.size(); i += IN_CHUNK) {
			List<UUID> chunk = list.subList(i, Math.min(list.size(), i + IN_CHUNK));
			StringBuilder sql = new StringBuilder("SELECT auction_uuid FROM api_obs WHERE auction_uuid IN (");
			for (int j = 0; j < chunk.size(); j++) {
				sql.append(j == 0 ? "?" : ",?");
			}
			sql.append(')');
			try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
				for (int j = 0; j < chunk.size(); j++) {
					ps.setObject(j + 1, chunk.get(j));
				}
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						found.add(rs.getObject(1, UUID.class));
					}
				}
			}
		}
		return found;
	}

	private static final String INSERT_API = "INSERT INTO api_obs (auction_uuid, item_uuid, item_id, item_name, norm_key,"
			+ " tier, bin, starting_bid, item_count, auction_start_ms, auction_end_ms, first_api_observed_ms,"
			+ " snapshot_last_updated_ms, snapshot_id, poll_id, page, source_status, observation_kind)"
			+ " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

	/**
	 * Inserts first observations. Rows whose UUID already exists are skipped, never updated, so an existing
	 * first-seen timestamp is preserved. Returns the number of rows actually inserted.
	 */
	public synchronized int insertApiObservations(List<ApiObservation> rows) throws SQLException {
		if (rows.isEmpty()) {
			return 0;
		}
		boolean auto = conn.getAutoCommit();
		conn.setAutoCommit(false);
		try (PreparedStatement ps = conn.prepareStatement(INSERT_API)) {
			int inserted;
			try {
				for (ApiObservation r : rows) {
					bindApi(ps, r);
					ps.addBatch();
				}
				int[] res = ps.executeBatch();
				inserted = res.length;
				conn.commit();
			} catch (BatchUpdateException e) {
				conn.rollback();
				inserted = insertOneByOne(rows);
				conn.commit();
			}
			return inserted;
		} catch (SQLException e) {
			conn.rollback();
			throw e;
		} finally {
			conn.setAutoCommit(auto);
		}
	}

	private int insertOneByOne(List<ApiObservation> rows) throws SQLException {
		int inserted = 0;
		try (PreparedStatement ps = conn.prepareStatement(INSERT_API)) {
			for (ApiObservation r : rows) {
				bindApi(ps, r);
				try {
					inserted += ps.executeUpdate();
				} catch (SQLException e) {
					if (!DUPLICATE_KEY_STATE.equals(e.getSQLState())) {
						throw e;
					}
				}
			}
		}
		return inserted;
	}

	private static void bindApi(PreparedStatement ps, ApiObservation r) throws SQLException {
		ps.setObject(1, r.auctionUuid());
		ps.setString(2, r.itemUuid());
		ps.setString(3, truncate(r.itemId(), 128));
		ps.setString(4, truncate(r.itemName(), 256));
		ps.setString(5, truncate(r.normKey(), 2048));
		ps.setString(6, truncate(r.tier(), 32));
		ps.setBoolean(7, r.bin());
		ps.setLong(8, r.startingBid());
		ps.setInt(9, r.count());
		setLong(ps, 10, r.auctionStartMs());
		setLong(ps, 11, r.auctionEndMs());
		ps.setLong(12, r.firstApiObservedMs());
		setLong(ps, 13, r.snapshotLastUpdatedMs());
		ps.setString(14, r.snapshotId());
		ps.setLong(15, r.pollId());
		ps.setInt(16, r.page());
		ps.setString(17, r.sourceStatus());
		ps.setString(18, r.observationKind());
	}

	/** Records the final snapshot status (COMPLETE/INCONSISTENT/INCOMPLETE) for all rows first seen in a poll. */
	public synchronized int updatePollStatus(long pollId, String status) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("UPDATE api_obs SET source_status = ? WHERE poll_id = ?")) {
			ps.setString(1, status);
			ps.setLong(2, pollId);
			return ps.executeUpdate();
		}
	}

	private static final String API_COLUMNS = "auction_uuid, item_uuid, item_id, item_name, norm_key, tier, bin,"
			+ " starting_bid, item_count, auction_start_ms, auction_end_ms, first_api_observed_ms,"
			+ " snapshot_last_updated_ms, snapshot_id, poll_id, page, source_status, observation_kind";

	public synchronized ApiObservation getApiObservation(UUID uuid) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT " + API_COLUMNS + " FROM api_obs WHERE auction_uuid = ?")) {
			ps.setObject(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? readApi(rs) : null;
			}
		}
	}

	public synchronized void forEachApiObservation(long sinceMs, int limit, Consumer<ApiObservation> consumer) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT " + API_COLUMNS
				+ " FROM api_obs WHERE first_api_observed_ms >= ? ORDER BY first_api_observed_ms LIMIT ?")) {
			ps.setLong(1, sinceMs);
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					consumer.accept(readApi(rs));
				}
			}
		}
	}

	/** API delays (first observation minus auction start) for auctions created during continuous monitoring. */
	public synchronized List<long[]> apiTimingSamples(long sinceMs) throws SQLException {
		List<long[]> out = new ArrayList<>();
		try (PreparedStatement ps = conn.prepareStatement("SELECT auction_start_ms, first_api_observed_ms FROM api_obs"
				+ " WHERE first_api_observed_ms >= ? AND observation_kind = ? AND auction_start_ms IS NOT NULL")) {
			ps.setLong(1, sinceMs);
			ps.setString(2, Records.KIND_NEW);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new long[] {rs.getLong(1), rs.getLong(2)});
				}
			}
		}
		return out;
	}

	private static ApiObservation readApi(ResultSet rs) throws SQLException {
		return new ApiObservation(
				rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getString(6), rs.getBoolean(7), rs.getLong(8), rs.getInt(9), getLong(rs, 10), getLong(rs, 11),
				rs.getLong(12), getLong(rs, 13), rs.getString(14), rs.getLong(15), rs.getInt(16), rs.getString(17),
				rs.getString(18));
	}

	/** Latest first API observation time, or null when empty. */
	public synchronized Long latestObservationMs() throws SQLException {
		try (Statement s = conn.createStatement();
				ResultSet rs = s.executeQuery("SELECT MAX(first_api_observed_ms) FROM api_obs")) {
			rs.next();
			return getLong(rs, 1);
		}
	}

	// ---- alerts ----------------------------------------------------------------------------------------------------

	/**
	 * Creates the alert row if the auction never had one. Returns false if an alert for this auction already exists
	 * (in any state), which is how deduplication survives restarts.
	 */
	// ---- trades ----------------------------------------------------------------------------------------------------

	private static final String TRADE_COLUMNS = "item_name, name_key, auction_uuid, bought_price, bought_ms,"
			+ " expected_list_price, expected_list_hours, expected_net, listed_ms, sold_price, buyer, sold_ms,"
			+ " collected_coins, collected_ms, profit, status";

	/** Inserts a trade and returns it with its generated id. */
	public synchronized Records.Trade insertTrade(Records.Trade t) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("INSERT INTO trades (" + TRADE_COLUMNS
				+ ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
			bindTrade(ps, t);
			ps.executeUpdate();
			try (ResultSet rs = ps.getGeneratedKeys()) {
				rs.next();
				long id = rs.getLong(1);
				return new Records.Trade(id, t.itemName(), t.nameKey(), t.auctionUuid(), t.boughtPrice(), t.boughtMs(),
						t.expectedListPrice(), t.expectedListHours(), t.expectedNet(), t.listedMs(), t.soldPrice(), t.buyer(),
						t.soldMs(), t.collectedCoins(), t.collectedMs(), t.profit(), t.status());
			}
		}
	}

	public synchronized void updateTrade(Records.Trade t) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("UPDATE trades SET item_name=?, name_key=?, auction_uuid=?,"
				+ " bought_price=?, bought_ms=?, expected_list_price=?, expected_list_hours=?, expected_net=?, listed_ms=?,"
				+ " sold_price=?, buyer=?, sold_ms=?, collected_coins=?, collected_ms=?, profit=?, status=? WHERE id=?")) {
			bindTrade(ps, t);
			ps.setLong(17, t.id());
			ps.executeUpdate();
		}
	}

	/** Trades with this name key in one of the given states, oldest purchase first. */
	public synchronized List<Records.Trade> tradesByName(String nameKey, Collection<Records.TradeStatus> states)
			throws SQLException {
		List<Records.Trade> out = new ArrayList<>();
		for (Records.Trade t : allTrades()) {
			if (t.nameKey().equals(nameKey) && states.contains(t.status())) {
				out.add(t);
			}
		}
		return out;
	}

	/** Every trade, oldest first. Trades are few (one per flip you make), so they are kept for good. */
	public synchronized List<Records.Trade> allTrades() throws SQLException {
		List<Records.Trade> out = new ArrayList<>();
		try (Statement s = conn.createStatement();
				ResultSet rs = s.executeQuery("SELECT id, " + TRADE_COLUMNS + " FROM trades ORDER BY id")) {
			while (rs.next()) {
				Integer hours = rs.getInt(8);
				if (rs.wasNull()) {
					hours = null;
				}
				out.add(new Records.Trade(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
						getLong(rs, 5), getLong(rs, 6), getLong(rs, 7), hours, getLong(rs, 9), getLong(rs, 10),
						getLong(rs, 11), rs.getString(12), getLong(rs, 13), getLong(rs, 14), getLong(rs, 15), getLong(rs, 16),
						Records.TradeStatus.valueOf(rs.getString(17))));
			}
		}
		return out;
	}

	private static void bindTrade(PreparedStatement ps, Records.Trade t) throws SQLException {
		ps.setString(1, truncate(t.itemName(), 256));
		ps.setString(2, truncate(t.nameKey(), 256));
		ps.setObject(3, t.auctionUuid());
		setLong(ps, 4, t.boughtPrice());
		setLong(ps, 5, t.boughtMs());
		setLong(ps, 6, t.expectedListPrice());
		if (t.expectedListHours() == null) {
			ps.setNull(7, Types.INTEGER);
		} else {
			ps.setInt(7, t.expectedListHours());
		}
		setLong(ps, 8, t.expectedNet());
		setLong(ps, 9, t.listedMs());
		setLong(ps, 10, t.soldPrice());
		ps.setString(11, truncate(t.buyer(), 64));
		setLong(ps, 12, t.soldMs());
		setLong(ps, 13, t.collectedCoins());
		setLong(ps, 14, t.collectedMs());
		setLong(ps, 15, t.profit());
		ps.setString(16, t.status().name());
	}

	public synchronized boolean tryCreateAlert(Alert a) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("INSERT INTO alerts (auction_uuid, norm_key, status, created_ms,"
				+ " last_attempt_ms, attempts, delivered_ms, item_name, price, est_net_profit, detail)"
				+ " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
			ps.setObject(1, a.auctionUuid());
			ps.setString(2, truncate(a.normKey(), 2048));
			ps.setString(3, a.status().name());
			ps.setLong(4, a.createdMs());
			setLong(ps, 5, a.lastAttemptMs());
			ps.setInt(6, a.attempts());
			setLong(ps, 7, a.deliveredMs());
			ps.setString(8, truncate(a.itemName(), 256));
			ps.setLong(9, a.price());
			ps.setLong(10, a.estimatedNetProfit());
			ps.setString(11, truncate(a.detail(), 1024));
			return ps.executeUpdate() == 1;
		} catch (SQLException e) {
			if (DUPLICATE_KEY_STATE.equals(e.getSQLState())) {
				return false;
			}
			throw e;
		}
	}

	public synchronized void updateAlert(UUID auctionUuid, AlertStatus status, Long lastAttemptMs, int attempts,
			Long deliveredMs, String detail) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("UPDATE alerts SET status = ?, last_attempt_ms = ?, attempts = ?,"
				+ " delivered_ms = ?, detail = ? WHERE auction_uuid = ?")) {
			ps.setString(1, status.name());
			setLong(ps, 2, lastAttemptMs);
			ps.setInt(3, attempts);
			setLong(ps, 4, deliveredMs);
			ps.setString(5, truncate(detail, 1024));
			ps.setObject(6, auctionUuid);
			ps.executeUpdate();
		}
	}

	public synchronized Alert getAlert(UUID auctionUuid) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(ALERT_SELECT + " WHERE auction_uuid = ?")) {
			ps.setObject(1, auctionUuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? readAlert(rs) : null;
			}
		}
	}

	/** Latest time an alert for this normalized item was delivered or queued, for per-item cooldowns. */
	public synchronized Long lastAlertForKey(String normKey) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT MAX(created_ms) FROM alerts WHERE norm_key = ?"
				+ " AND status IN ('SENT', 'QUEUED')")) {
			ps.setString(1, truncate(normKey, 2048));
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? getLong(rs, 1) : null;
			}
		}
	}

	public synchronized int countDeliveredSince(long sinceMs) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM alerts WHERE status = 'SENT' AND delivered_ms >= ?")) {
			ps.setLong(1, sinceMs);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	/** Marks alerts left QUEUED by a previous session as EXPIRED so they are not resent after a restart. */
	public synchronized int expireQueuedAlerts(long nowMs) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("UPDATE alerts SET status = 'EXPIRED', detail = ? WHERE status = 'QUEUED'")) {
			ps.setString(1, "expired at startup (queued in a previous session; not resent)");
			return ps.executeUpdate();
		}
	}

	public synchronized void forEachAlert(long sinceMs, int limit, Consumer<Alert> consumer) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(ALERT_SELECT + " WHERE created_ms >= ? ORDER BY created_ms LIMIT ?")) {
			ps.setLong(1, sinceMs);
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					consumer.accept(readAlert(rs));
				}
			}
		}
	}

	private static final String ALERT_SELECT = "SELECT auction_uuid, norm_key, status, created_ms, last_attempt_ms,"
			+ " attempts, delivered_ms, item_name, price, est_net_profit, detail FROM alerts";

	private static Alert readAlert(ResultSet rs) throws SQLException {
		return new Alert(rs.getObject(1, UUID.class), rs.getString(2), AlertStatus.valueOf(rs.getString(3)),
				rs.getLong(4), getLong(rs, 5), rs.getInt(6), getLong(rs, 7), rs.getString(8), rs.getLong(9),
				rs.getLong(10), rs.getString(11));
	}

	// ---- sales -----------------------------------------------------------------------------------------------------

	public synchronized int insertSales(List<Sale> sales) throws SQLException {
		int inserted = 0;
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO sales (auction_id, norm_key, unit_price, ended_ms) VALUES (?,?,?,?)")) {
			for (Sale s : sales) {
				ps.setObject(1, s.auctionId());
				ps.setString(2, truncate(s.normKey(), 2048));
				ps.setLong(3, s.unitPrice());
				ps.setLong(4, s.endedMs());
				try {
					inserted += ps.executeUpdate();
				} catch (SQLException e) {
					if (!DUPLICATE_KEY_STATE.equals(e.getSQLState())) {
						throw e;
					}
				}
			}
		}
		return inserted;
	}

	/** Most recent recorded BIN sale unit prices for a key since {@code sinceMs}, newest first. */
	public synchronized List<Long> salePrices(String normKey, long sinceMs, int limit) throws SQLException {
		List<Long> out = new ArrayList<>();
		try (PreparedStatement ps = conn.prepareStatement("SELECT unit_price FROM sales WHERE norm_key = ? AND ended_ms >= ?"
				+ " ORDER BY ended_ms DESC LIMIT ?")) {
			ps.setString(1, truncate(normKey, 2048));
			ps.setLong(2, sinceMs);
			ps.setInt(3, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(rs.getLong(1));
				}
			}
		}
		return out;
	}

	public synchronized void forEachSale(long sinceMs, Consumer<Sale> consumer) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT auction_id, norm_key, unit_price, ended_ms FROM sales"
				+ " WHERE ended_ms >= ? ORDER BY ended_ms")) {
			ps.setLong(1, sinceMs);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					consumer.accept(new Sale(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4)));
				}
			}
		}
	}

	// ---- counts, retention -----------------------------------------------------------------------------------------

	public synchronized long count(String table) throws SQLException {
		if (!List.of("api_obs", "alerts", "sales", "trades").contains(table)) {
			throw new IllegalArgumentException(table);
		}
		try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	/** Deletes rows older than the retention windows and caps the API observation table. Returns rows deleted. */
	public synchronized int prune(long nowMs, int retentionDays, int salesRetentionDays, long maxApiRows) throws SQLException {
		long cutoff = nowMs - retentionDays * 86_400_000L;
		long salesCutoff = nowMs - salesRetentionDays * 86_400_000L;
		int deleted = 0;
		deleted += deleteBefore("DELETE FROM api_obs WHERE first_api_observed_ms < ?", cutoff);
		deleted += deleteBefore("DELETE FROM alerts WHERE created_ms < ?", cutoff);
		deleted += deleteBefore("DELETE FROM sales WHERE ended_ms < ?", salesCutoff);
		long rows = count("api_obs");
		if (rows > maxApiRows) {
			long excess = rows - maxApiRows;
			try (PreparedStatement ps = conn.prepareStatement("DELETE FROM api_obs WHERE auction_uuid IN"
					+ " (SELECT auction_uuid FROM api_obs ORDER BY first_api_observed_ms LIMIT ?)")) {
				ps.setLong(1, excess);
				deleted += ps.executeUpdate();
			}
		}
		return deleted;
	}

	private int deleteBefore(String sql, long cutoff) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, cutoff);
			return ps.executeUpdate();
		}
	}

	@Override
	public synchronized void close() {
		try (Statement s = conn.createStatement()) {
			s.execute("SHUTDOWN");
		} catch (SQLException e) {
			BwLog.throttled(BwLog.Level.WARN, "db-close", "Database shutdown reported: " + e.getMessage(), null);
		}
		try {
			conn.close();
		} catch (SQLException ignored) {
			// Already closed by SHUTDOWN.
		}
	}

	// ---- helpers ---------------------------------------------------------------------------------------------------

	private static void setLong(PreparedStatement ps, int idx, Long v) throws SQLException {
		if (v == null) {
			ps.setNull(idx, Types.BIGINT);
		} else {
			ps.setLong(idx, v);
		}
	}

	private static Long getLong(ResultSet rs, int idx) throws SQLException {
		long v = rs.getLong(idx);
		return rs.wasNull() ? null : v;
	}

	private static String truncate(String s, int max) {
		return s == null || s.length() <= max ? s : s.substring(0, max);
	}
}
