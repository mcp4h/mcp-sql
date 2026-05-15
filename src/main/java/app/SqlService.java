package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.sql.Connection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.security.PublicKey;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;

public final class SqlService {
	private final ObjectMapper mapper;
	private Config config;
	private final Map<String, HikariDataSource> pools = new HashMap<>();
	private final Map<String, SshTunnel> tunnels = new HashMap<>();

	public SqlService(ObjectMapper mapper, Config config) {
		this.mapper = mapper;
		this.config = config;
	}

	public synchronized void updateConfig(Config config) {
		this.config = config;
		closePools();
	}

	public ObjectNode executeSelect(String sql, JsonNode params, String connectionName,
			Integer maxRows, Integer offset, Config.Policy policy) throws Exception {
		validateSqlType(sql, SqlType.SELECT);
		if (policy.allowSelect != null && !policy.allowSelect) {
			throw new Config.ConfigException("SELECT statements are disabled by policy");
		}
		ResolvedRequest resolved = resolveRequest(connectionName, maxRows, null, policy);
		try (Connection conn = resolved.dataSource.getConnection()) {
			applyConnectionDefaults(conn, resolved.connectionConfig);
			conn.setReadOnly(true);
			PreparedStatement stmt = conn.prepareStatement(sql);
			applyParams(stmt, params);
			applyStatementLimits(stmt, resolved.maxRows + 1, resolved.timeoutMs, null);
			ResultSet resultSet = stmt.executeQuery();
			return readResultSet(resultSet, resolved.maxRows, offset);
		}
	}

	public ObjectNode executeUpdate(String sql, JsonNode params, String connectionName,
			Integer timeoutMs, Config.Policy policy) throws Exception {
		validateSqlType(sql, SqlType.DML);
		if (policy.allowWrite != null && !policy.allowWrite) {
			throw new Config.ConfigException("Write statements are disabled by policy");
		}
		ResolvedRequest resolved = resolveRequest(connectionName, null, timeoutMs, policy);
		try (Connection conn = resolved.dataSource.getConnection()) {
			applyConnectionDefaults(conn, resolved.connectionConfig);
			PreparedStatement stmt = conn.prepareStatement(sql);
			applyParams(stmt, params);
			applyStatementLimits(stmt, null, resolved.timeoutMs, null);
			boolean hasResultSet = stmt.execute();
			if (hasResultSet) {
				ResultSet resultSet = stmt.getResultSet();
				ObjectNode payload = readResultSet(resultSet, resolved.maxRows, null);
				Integer actualRowCount = findNextUpdateCount(stmt);
				if (actualRowCount != null) {
					payload.put("rowCount", actualRowCount);
				}
				return payload;
			}
			int count = stmt.getUpdateCount();
			ObjectNode payload = mapper.createObjectNode();
			if (count >= 0) {
				payload.put("rowCount", count);
			}
			return payload;
		}
	}

	public ObjectNode previewUpdate(String sql, JsonNode params, String connectionName,
			Integer maxRows, Config.Policy policy) throws Exception {
		validateSqlType(sql, SqlType.DML);
		if (policy.allowWrite != null && !policy.allowWrite) {
			throw new Config.ConfigException("Write statements are disabled by policy");
		}
		ResolvedRequest resolved = resolveRequest(connectionName, maxRows, null, policy);
		int previewLimit = resolved.maxRows;
		if (config.query.previewMaxRows != null) {
			previewLimit = config.query.previewMaxRows;
		}
		Connection conn = resolved.dataSource.getConnection();
		boolean autoCommit = conn.getAutoCommit();
		try {
			applyConnectionDefaults(conn, resolved.connectionConfig);
			conn.setAutoCommit(false);
			PreparedStatement stmt = conn.prepareStatement(sql);
			applyParams(stmt, params);
			applyStatementLimits(stmt, null, resolved.timeoutMs, null);
			boolean hasResultSet = stmt.execute();
			if (hasResultSet) {
				ResultSet resultSet = stmt.getResultSet();
				ObjectNode payload = readResultSet(resultSet, previewLimit, null);
				Integer actualRowCount = findNextUpdateCount(stmt);
				if (actualRowCount != null) {
					payload.put("rowCount", actualRowCount);
				}
				payload.put("previewLimit", previewLimit);
				return payload;
			}
			int count = stmt.getUpdateCount();
			ObjectNode payload = mapper.createObjectNode();
			if (count >= 0) {
				payload.put("rowCount", count);
			}
			payload.put("previewLimit", previewLimit);
			return payload;
		} finally {
			try {
				conn.rollback();
			} catch (SQLException ignored) {
				// ignore rollback failures
			}
			try {
				conn.setAutoCommit(autoCommit);
			} catch (SQLException ignored) {
				// ignore
			}
			conn.close();
		}
	}

	public ObjectNode executeDdl(String sql, JsonNode params, String connectionName,
			Integer timeoutMs, Config.Policy policy) throws Exception {
		validateSqlType(sql, SqlType.DDL);
		if (policy.allowDdl != null && !policy.allowDdl) {
			throw new Config.ConfigException("DDL statements are disabled by policy");
		}
		ResolvedRequest resolved = resolveRequest(connectionName, null, timeoutMs, policy);
		try (Connection conn = resolved.dataSource.getConnection()) {
			applyConnectionDefaults(conn, resolved.connectionConfig);
			PreparedStatement stmt = conn.prepareStatement(sql);
			applyParams(stmt, params);
			applyStatementLimits(stmt, null, resolved.timeoutMs, null);
			boolean hasResultSet = stmt.execute();
			ObjectNode payload = mapper.createObjectNode();
			payload.put("success", true);
			payload.put("hasResultSet", hasResultSet);
			int count = stmt.getUpdateCount();
			if (count >= 0) {
				payload.put("rowCount", count);
			}
			return payload;
		}
	}

	public ObjectNode executeProcedure(String sql, JsonNode params, String connectionName,
			Config.Policy policy) throws Exception {
		ResolvedRequest resolved = resolveRequest(connectionName, null, null, policy);
		try (Connection conn = resolved.dataSource.getConnection()) {
			applyConnectionDefaults(conn, resolved.connectionConfig);
			PreparedStatement stmt = conn.prepareStatement(sql);
			applyParams(stmt, params);
			applyStatementLimits(stmt, null, resolved.timeoutMs, null);
			boolean hasResultSet = stmt.execute();
			if (hasResultSet) {
				ResultSet resultSet = stmt.getResultSet();
				return readResultSet(resultSet, resolved.maxRows, null);
			}
			ObjectNode payload = mapper.createObjectNode();
			payload.put("success", true);
			int count = stmt.getUpdateCount();
			if (count >= 0) {
				payload.put("rowCount", count);
			}
			return payload;
		}
	}

	public HikariDataSource dataSourceFor(String connectionName, Config.Policy policy) throws Exception {
		ResolvedRequest resolved = resolveRequest(connectionName, null, null, policy);
		return resolved.dataSource;
	}

	public String vendorFor(String connectionName, Config.Policy policy) throws Exception {
		ResolvedRequest resolved = resolveRequest(connectionName, null, null, policy);
		try (Connection conn = resolved.dataSource.getConnection()) {
			applyConnectionDefaults(conn, resolved.connectionConfig);
			DatabaseMetaData meta = conn.getMetaData();
			return meta.getDatabaseProductName();
		}
	}

	private ObjectNode readResultSet(ResultSet resultSet, int maxRows, Integer offset) throws SQLException {
		ObjectNode payload = mapper.createObjectNode();
		ArrayNode columns = payload.putArray("columns");
		ResultSetMetaData meta = resultSet.getMetaData();
		int columnCount = meta.getColumnCount();
		for (int i = 1; i <= columnCount; i++) {
			ObjectNode col = columns.addObject();
			col.put("name", meta.getColumnLabel(i));
			col.put("type", meta.getColumnTypeName(i));
			col.put("nullable", meta.isNullable(i) != ResultSetMetaData.columnNoNulls);
		}
		ArrayNode rows = payload.putArray("rows");
		int rowCount = 0;
		int skipped = 0;
		int targetOffset = offset == null ? 0 : Math.max(0, offset);
		while (resultSet.next()) {
			if (skipped < targetOffset) {
				skipped++;
				continue;
			}
			ArrayNode row = rows.addArray();
			for (int i = 1; i <= columnCount; i++) {
				Object value = resultSet.getObject(i);
				row.add(mapper.valueToTree(normalizeValue(value)));
			}
			rowCount++;
			if (rowCount >= maxRows) {
				break;
			}
		}
		boolean truncated = false;
		if (rowCount >= maxRows && resultSet.next()) {
			truncated = true;
		}
		payload.put("rowCount", rowCount);
		if (targetOffset > 0) {
			payload.put("offset", targetOffset);
		}
		if (truncated) {
			payload.put("truncated", true);
			payload.put("maxRows", maxRows);
		}
		return payload;
	}

	private Integer findNextUpdateCount(PreparedStatement stmt) throws SQLException {
		while (true) {
			boolean hasMoreResults = stmt.getMoreResults();
			int updateCount = stmt.getUpdateCount();
			if (updateCount >= 0) {
				return updateCount;
			}
			if (!hasMoreResults && updateCount == -1) {
				return null;
			}
		}
	}

	private Object normalizeValue(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Timestamp timestamp) {
			return timestamp.toInstant().toString();
		}
		if (value instanceof java.sql.Date date) {
			return date.toLocalDate().toString();
		}
		if (value instanceof Time time) {
			return time.toLocalTime().toString();
		}
		if (value instanceof Instant instant) {
			return instant.toString();
		}
		if (value instanceof LocalDate date) {
			return date.toString();
		}
		if (value instanceof LocalDateTime dateTime) {
			return dateTime.toString();
		}
		if (value instanceof OffsetDateTime dateTime) {
			return dateTime.toString();
		}
		if (value instanceof ZonedDateTime dateTime) {
			return dateTime.toString();
		}
		if (value instanceof Date date) {
			return date.toInstant().toString();
		}
		return value;
	}

	private void applyParams(PreparedStatement stmt, JsonNode params) throws SQLException, Config.ConfigException {
		if (params == null || params.isNull()) {
			return;
		}
		if (!params.isArray()) {
			throw new Config.ConfigException("params must be an array");
		}
		int index = 1;
		for (JsonNode param : params) {
			if (param.isNull()) {
				stmt.setObject(index, null);
			} else if (param.isNumber()) {
				stmt.setObject(index, param.numberValue());
			} else if (param.isBoolean()) {
				stmt.setObject(index, param.asBoolean());
			} else {
				stmt.setObject(index, param.asText());
			}
			index++;
		}
	}

	private void applyStatementLimits(PreparedStatement stmt, Integer maxRows, Integer timeoutMs, Integer fetchSize) throws SQLException {
		if (maxRows != null && maxRows > 0) {
			stmt.setMaxRows(maxRows);
		}
		if (timeoutMs != null && timeoutMs > 0) {
			stmt.setQueryTimeout(Math.max(1, timeoutMs / 1000));
		}
		if (fetchSize != null && fetchSize > 0) {
			stmt.setFetchSize(fetchSize);
		}
	}

	private ResolvedRequest resolveRequest(String connectionName, Integer maxRows,
			Integer timeoutMs, Config.Policy policy) throws Config.ConfigException {
		String resolvedConnection = resolveConnectionName(connectionName, policy);
		HikariDataSource dataSource = ensurePool(resolvedConnection);
		Config.ConnectionConfig connectionConfig = findConnectionConfig(resolvedConnection);
		if (connectionConfig == null) {
			throw new Config.ConfigException("connection not found: " + resolvedConnection);
		}
		int resolvedMaxRows = maxRows == null ? config.query.maxRows : maxRows;
		if (policy.maxRows != null) {
			resolvedMaxRows = Math.min(resolvedMaxRows, policy.maxRows);
		}
		int resolvedTimeout = timeoutMs == null ? config.query.timeoutMs : timeoutMs;
		if (policy.timeoutMs != null) {
			resolvedTimeout = Math.min(resolvedTimeout, policy.timeoutMs);
		}
		return new ResolvedRequest(resolvedConnection, dataSource, connectionConfig, resolvedMaxRows, resolvedTimeout);
	}

	private String resolveConnectionName(String connectionName, Config.Policy policy) throws Config.ConfigException {
		String resolved = connectionName;
		if (resolved == null || resolved.isBlank()) {
			resolved = policy.defaultConnection != null && !policy.defaultConnection.isBlank()
				? policy.defaultConnection
				: config.defaultConnection;
		}
		if (resolved == null || resolved.isBlank()) {
			throw new Config.ConfigException("connection is required");
		}
		if (!policy.allowedConnections.isEmpty()) {
			boolean allowed = false;
			for (String allowedName : policy.allowedConnections) {
				if (allowedName.equalsIgnoreCase(resolved)) {
					allowed = true;
					break;
				}
			}
			if (!allowed) {
				throw new Config.ConfigException("connection not allowed by policy: " + resolved);
			}
		}
		for (String denied : policy.deniedConnections) {
			if (denied.equalsIgnoreCase(resolved)) {
				throw new Config.ConfigException("connection denied by policy: " + resolved);
			}
		}
		return resolved;
	}

	private Config.ConnectionConfig findConnectionConfig(String name) {
		for (Config.ConnectionConfig connection : config.connections) {
			if (connection.name.equals(name)) {
				return connection;
			}
		}
		return null;
	}

	private void applyConnectionDefaults(Connection conn, Config.ConnectionConfig connectionConfig) throws SQLException {
		String schema = resolveDefaultSchema(connectionConfig);
		if (schema != null && !schema.isBlank()) {
			try {
				conn.setSchema(schema);
			} catch (SQLException ignored) {
				// ignore if driver does not support setSchema
			}
		}
		if (connectionConfig.catalog != null && !connectionConfig.catalog.isBlank()) {
			conn.setCatalog(connectionConfig.catalog);
		}
	}

	private String resolveDefaultSchema(Config.ConnectionConfig connectionConfig) throws SQLException {
		if (connectionConfig.defaultSchema != null && !connectionConfig.defaultSchema.isBlank()) {
			return connectionConfig.defaultSchema;
		}
		String driver = connectionConfig.driver == null ? "" : connectionConfig.driver.trim().toLowerCase(Locale.ROOT);
		switch (driver) {
			case "postgresql", "postgres" -> {
				return "public";
			}
			case "oracle" -> {
				if (connectionConfig.username != null && !connectionConfig.username.isBlank()) {
					return connectionConfig.username.toUpperCase(Locale.ROOT);
				}
				return null;
			}
			case "sqlserver", "mssql" -> {
				return "dbo";
			}
			case "h2" -> {
				return "PUBLIC";
			}
			case "mysql" -> {
				return null;
			}
			default -> {
				return null;
			}
		}
	}

	private void validateSqlType(String sql, SqlType expected) throws Config.ConfigException {
		String normalized = sql == null ? "" : sql.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			throw new Config.ConfigException("sql is required");
		}
		SqlType actual = SqlType.fromSql(normalized);
		if (actual == SqlType.UNKNOWN) {
			throw new Config.ConfigException("sql type is not supported for this tool");
		}
		if (actual != expected) {
			throw new Config.ConfigException("sql must be " + expected.label);
		}
	}

	public SqlType classifySql(String sql) throws Config.ConfigException {
		String normalized = sql == null ? "" : sql.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			throw new Config.ConfigException("sql is required");
		}
		return SqlType.fromSql(normalized);
	}

	private boolean isReturningQuery(String sql) {
		if (sql == null) {
			return false;
		}
		String normalized = sql.toLowerCase(Locale.ROOT);
		return normalized.contains(" returning ");
	}

	private synchronized void closePools() {
		for (HikariDataSource pool : pools.values()) {
			pool.close();
		}
		pools.clear();
		for (SshTunnel tunnel : tunnels.values()) {
			tunnel.close();
		}
		tunnels.clear();
	}

	private synchronized HikariDataSource ensurePool(String connectionName) throws Config.ConfigException {
		HikariDataSource existing = pools.get(connectionName);
		if (existing != null) {
			return existing;
		}
		Config.ConnectionConfig connection = findConnectionConfig(connectionName);
		if (connection == null) {
			throw new Config.ConfigException("connection not found: " + connectionName);
		}
		HikariConfig hikari = new HikariConfig();
		Config.DriverInfo driverInfo = Config.resolveDriver(connection.driver);
		hikari.setPoolName("mcp-sql-" + connection.name);
		String jdbcUrl = connection.url;
		if (connection.ssh != null) {
			SshTunnel tunnel = openTunnel(connection);
			tunnels.put(connection.name, tunnel);
			jdbcUrl = rewriteJdbcUrl(connection.driver, connection.url, tunnel.localPort);
		}
		hikari.setJdbcUrl(jdbcUrl);
		hikari.setDriverClassName(driverInfo.driverClass);
		String password = resolvePassword(connection);
		if (connection.username != null) {
			hikari.setUsername(connection.username);
		}
		if (password != null) {
			hikari.setPassword(password);
		}
		if (connection.catalog != null) {
			hikari.setCatalog(connection.catalog);
		}
		hikari.setMaximumPoolSize(connection.pool.maxPoolSize);
		hikari.setMinimumIdle(connection.pool.minIdle);
		hikari.setIdleTimeout(connection.pool.idleTimeoutMs);
		hikari.setMaxLifetime(connection.pool.maxLifetimeMs);
		hikari.setConnectionTimeout(connection.pool.connectionTimeoutMs);
		HikariDataSource dataSource = new HikariDataSource(hikari);
		pools.put(connection.name, dataSource);
		return dataSource;
	}

	private String resolvePassword(Config.ConnectionConfig connection) {
		if (connection.password != null && !connection.password.isBlank()) {
			return connection.password;
		}
		if (connection.passwordEnv != null && !connection.passwordEnv.isBlank()) {
			return System.getenv(connection.passwordEnv);
		}
		return null;
	}

	private SshTunnel openTunnel(Config.ConnectionConfig connection) throws IllegalStateException {
		Config.SshConfig ssh = connection.ssh;
		if (ssh == null) {
			throw new IllegalStateException("ssh not configured for connection: " + connection.name);
		}
		try {
			SSHClient client = new SSHClient();
			OpenSSHKnownHosts knownHosts = buildKnownHosts(ssh);
			client.addHostKeyVerifier(knownHosts);
			client.connect(ssh.host, ssh.port);
			String keyPath = resolveValue(ssh.keyPath, ssh.keyPathEnv);
			String password = resolveValue(ssh.password, ssh.passwordEnv);
			String passphrase = resolveValue(ssh.keyPassphrase, ssh.keyPassphraseEnv);
			if (keyPath != null) {
				if (passphrase != null) {
					client.authPublickey(ssh.user, client.loadKeys(keyPath, passphrase));
				} else {
					client.authPublickey(ssh.user, client.loadKeys(keyPath));
				}
			} else if (password != null) {
				client.authPassword(ssh.user, password);
			} else {
				throw new IllegalStateException("ssh auth requires key_path or password");
			}
			SshTarget target = inferTarget(connection, ssh);
			int localPort = (ssh.localPort == null || ssh.localPort == 0)
				? pickAvailablePort()
				: ssh.localPort;
			ServerSocket socket = new ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"));
			Parameters params = new Parameters("127.0.0.1", localPort, target.host, target.port);
			LocalPortForwarder forwarder = client.newLocalPortForwarder(params, socket);
			Thread thread = new Thread(() -> {
				try {
					forwarder.listen();
				} catch (Exception ignored) {
				}
			}, "mcp-sql-ssh-" + connection.name);
			thread.setDaemon(true);
			thread.start();
			return new SshTunnel(client, forwarder, socket, localPort);
		} catch (Exception e) {
			throw new IllegalStateException("failed to start ssh tunnel: " + e.getMessage(), e);
		}
	}

	private SshTarget inferTarget(Config.ConnectionConfig connection, Config.SshConfig ssh) {
		String host = ssh.remoteHost;
		Integer port = ssh.remotePort > 0 ? ssh.remotePort : null;
		if (host == null || host.isBlank() || port == null) {
			SshTarget parsed = parseJdbcTarget(connection.driver, connection.url);
			if (host == null || host.isBlank()) {
				host = parsed.host;
			}
			if (port == null) {
				port = parsed.port;
			}
		}
		if (host == null || host.isBlank()) {
			throw new IllegalStateException("ssh.remote_host is required or must be inferred from jdbc url");
		}
		if (port == null || port < 1) {
			throw new IllegalStateException("ssh.remote_port is required or must be inferred from jdbc url");
		}
		return new SshTarget(host, port);
	}

	private SshTarget parseJdbcTarget(String driver, String jdbcUrl) {
		String normalized = driver == null ? "" : driver.trim().toLowerCase(Locale.ROOT);
		if (normalized.equals("postgresql") || normalized.equals("postgres")) {
			return parseHostPort(jdbcUrl, "jdbc:postgresql://", 5432);
		}
		if (normalized.equals("mysql")) {
			return parseHostPort(jdbcUrl, "jdbc:mysql://", 3306);
		}
		if (normalized.equals("sqlserver") || normalized.equals("mssql")) {
			return parseHostPort(jdbcUrl, "jdbc:sqlserver://", 1433);
		}
		if (normalized.equals("oracle")) {
			SshTarget target = parseOracleHostPort(jdbcUrl);
			if (target != null) {
				return target;
			}
			return new SshTarget(null, 1521);
		}
		if (normalized.equals("h2")) {
			return parseHostPort(jdbcUrl, "jdbc:h2:tcp://", 9092);
		}
		return new SshTarget(null, null);
	}

	private SshTarget parseHostPort(String jdbcUrl, String prefix, int defaultPort) {
		if (jdbcUrl == null || !jdbcUrl.startsWith(prefix)) {
			return new SshTarget(null, defaultPort);
		}
		Pattern pattern = Pattern.compile(Pattern.quote(prefix) + "([^/:;]+)(?::(\\d+))?");
		Matcher matcher = pattern.matcher(jdbcUrl);
		if (matcher.find()) {
			String host = matcher.group(1);
			Integer port = matcher.group(2) == null ? defaultPort : Integer.parseInt(matcher.group(2));
			return new SshTarget(host, port);
		}
		return new SshTarget(null, defaultPort);
	}

	private SshTarget parseOracleHostPort(String jdbcUrl) {
		if (jdbcUrl == null) {
			return null;
		}
		Pattern slashPattern = Pattern.compile("@//([^/:]+)(?::(\\d+))?/");
		Matcher slashMatcher = slashPattern.matcher(jdbcUrl);
		if (slashMatcher.find()) {
			String host = slashMatcher.group(1);
			Integer port = slashMatcher.group(2) == null ? 1521 : Integer.parseInt(slashMatcher.group(2));
			return new SshTarget(host, port);
		}
		Pattern sidPattern = Pattern.compile("@([^:/]+)(?::(\\d+))?:");
		Matcher sidMatcher = sidPattern.matcher(jdbcUrl);
		if (sidMatcher.find()) {
			String host = sidMatcher.group(1);
			Integer port = sidMatcher.group(2) == null ? 1521 : Integer.parseInt(sidMatcher.group(2));
			return new SshTarget(host, port);
		}
		return null;
	}

	private OpenSSHKnownHosts buildKnownHosts(Config.SshConfig ssh) throws IOException {
		File file;
		if (ssh.knownHostsPath != null && !ssh.knownHostsPath.isBlank()) {
			file = new File(ssh.knownHostsPath);
		} else {
			File sshDir = OpenSSHKnownHosts.detectSSHDir();
			file = new File(sshDir, "known_hosts");
		}
		if (!file.exists()) {
			File parent = file.getParentFile();
			if (parent != null && !parent.exists()) {
				parent.mkdirs();
			}
			file.createNewFile();
		}
		if (!ssh.trustOnFirstUse) {
			return new OpenSSHKnownHosts(file);
		}
		return new OpenSSHKnownHosts(file) {
			@Override
			protected boolean hostKeyUnverifiableAction(String hostname, PublicKey key) {
				try {
					KeyType type = KeyType.fromKey(key);
					OpenSSHKnownHosts.HostEntry entry = new OpenSSHKnownHosts.HostEntry(
							null, hostname, type, key);
					write(entry);
					write();
					return true;
				} catch (Exception e) {
					return false;
				}
			}
		};
	}

	private int pickAvailablePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private String resolveValue(String direct, String env) {
		if (direct != null && !direct.isBlank()) {
			return direct;
		}
		if (env != null && !env.isBlank()) {
			return System.getenv(env);
		}
		return null;
	}

	private String rewriteJdbcUrl(String driver, String jdbcUrl, int localPort) {
		String normalized = driver == null ? "" : driver.trim().toLowerCase(Locale.ROOT);
		if (normalized.equals("postgresql") || normalized.equals("postgres")) {
			return jdbcUrl.replaceFirst("jdbc:postgresql://[^/:]+(?::\\d+)?/", "jdbc:postgresql://localhost:" + localPort + "/");
		}
		if (normalized.equals("mysql")) {
			return jdbcUrl.replaceFirst("jdbc:mysql://[^/:]+(?::\\d+)?/", "jdbc:mysql://localhost:" + localPort + "/");
		}
		if (normalized.equals("sqlserver") || normalized.equals("mssql")) {
			return jdbcUrl.replaceFirst("jdbc:sqlserver://[^;:]+(?::\\d+)?", "jdbc:sqlserver://localhost:" + localPort);
		}
		if (normalized.equals("oracle")) {
			if (jdbcUrl.contains("@//")) {
				return jdbcUrl.replaceFirst("@//[^/:]+(?::\\d+)?/", "@//localhost:" + localPort + "/");
			}
			return jdbcUrl.replaceFirst("@[^:/]+(?::\\d+)?[:/]", "@localhost:" + localPort + ":");
		}
		if (normalized.equals("h2")) {
			return jdbcUrl.replaceFirst("jdbc:h2:tcp://[^/:]+(?::\\d+)?/", "jdbc:h2:tcp://localhost:" + localPort + "/");
		}
		throw new IllegalStateException("ssh tunneling is not supported for driver: " + driver);
	}

	private static final class SshTunnel {
		final SSHClient client;
		final LocalPortForwarder forwarder;
		final ServerSocket socket;
		final int localPort;

		SshTunnel(SSHClient client, LocalPortForwarder forwarder, ServerSocket socket, int localPort) {
			this.client = client;
			this.forwarder = forwarder;
			this.socket = socket;
			this.localPort = localPort;
		}

		void close() {
			try {
				forwarder.close();
			} catch (Exception ignored) {
			}
			try {
				socket.close();
			} catch (Exception ignored) {
			}
			try {
				client.disconnect();
			} catch (Exception ignored) {
			}
			try {
				client.close();
			} catch (Exception ignored) {
			}
		}
	}

	private static final class SshTarget {
		final String host;
		final Integer port;

		SshTarget(String host, Integer port) {
			this.host = host;
			this.port = port;
		}
	}

	private static final class ResolvedRequest {
		final String connectionName;
		final HikariDataSource dataSource;
		final Config.ConnectionConfig connectionConfig;
		final int maxRows;
		final int timeoutMs;

		ResolvedRequest(String connectionName, HikariDataSource dataSource, Config.ConnectionConfig connectionConfig,
				int maxRows, int timeoutMs) {
			this.connectionName = connectionName;
			this.dataSource = dataSource;
			this.connectionConfig = connectionConfig;
			this.maxRows = maxRows;
			this.timeoutMs = timeoutMs;
		}
	}

	public enum SqlType {
		SELECT("select"),
		DML("insert/update/delete"),
		DDL("ddl"),
		PROC("procedure"),
		UNKNOWN("unknown");

		final String label;

		SqlType(String label) {
			this.label = label;
		}

		static SqlType fromSql(String normalized) {
			SqlScanner scanner = new SqlScanner(normalized);
			String keyword = scanner.nextKeyword();
			if (keyword == null) {
				return UNKNOWN;
			}
			if (keyword.equals("with")) {
				keyword = scanner.keywordAfterWith();
				if (keyword == null) {
					return UNKNOWN;
				}
			}
			return classifyKeyword(keyword);
		}

		private static SqlType classifyKeyword(String keyword) {
			return switch (keyword) {
				case "select", "values" -> SELECT;
				case "insert", "update", "delete", "merge" -> DML;
				case "create", "alter", "drop", "truncate" -> DDL;
				case "call", "exec", "execute" -> PROC;
				default -> UNKNOWN;
			};
		}
	}

	private static final class SqlScanner {
		private final String text;
		private int index;

		SqlScanner(String text) {
			this.text = text;
			this.index = 0;
		}

		String nextKeyword() {
			skipSpaceAndComments();
			return readWord();
		}

		String keywordAfterWith() {
			skipSpaceAndComments();
			String keyword = readWord();
			if (keyword == null) {
				return null;
			}
			if (keyword.equals("recursive")) {
				skipSpaceAndComments();
				keyword = readWord();
			}
			while (keyword != null) {
				skipSpaceAndComments();
				String next = readWord();
				if (next == null) {
					return null;
				}
				if (next.equals("as")) {
					skipSpaceAndComments();
					if (!consumeChar('(')) {
						return null;
					}
					if (!skipBalanced('(', ')')) {
						return null;
					}
					skipSpaceAndComments();
					if (consumeChar(',')) {
						skipSpaceAndComments();
						keyword = readWord();
						continue;
					}
					return readWord();
				}
				if (consumeChar('(')) {
					if (!skipBalanced('(', ')')) {
						return null;
					}
					skipSpaceAndComments();
					next = readWord();
					if (next == null) {
						return null;
					}
					if (next.equals("as")) {
						skipSpaceAndComments();
						if (!consumeChar('(')) {
							return null;
						}
						if (!skipBalanced('(', ')')) {
							return null;
						}
						skipSpaceAndComments();
						if (consumeChar(',')) {
							keyword = readWord();
							continue;
						}
						return readWord();
					}
				}
				keyword = next;
			}
			return null;
		}

		private void skipSpaceAndComments() {
			while (index < text.length()) {
				char ch = text.charAt(index);
				if (Character.isWhitespace(ch)) {
					index++;
					continue;
				}
				if (ch == '-' && peekNext() == '-') {
					skipLineComment();
					continue;
				}
				if (ch == '/' && peekNext() == '*') {
					skipBlockComment();
					continue;
				}
				if (ch == '\'') {
					skipSingleQuote();
					continue;
				}
				if (ch == '"') {
					skipDoubleQuote();
					continue;
				}
				if (ch == '`') {
					skipBacktick();
					continue;
				}
				if (ch == '$' && peekNext() == '$') {
					skipDollarQuote();
					continue;
				}
				break;
			}
		}

		private String readWord() {
			skipSpaceAndComments();
			if (index >= text.length()) {
				return null;
			}
			char ch = text.charAt(index);
			if (!Character.isLetter(ch)) {
				return null;
			}
			int start = index;
			index++;
			while (index < text.length()) {
				char next = text.charAt(index);
				if (!Character.isLetterOrDigit(next) && next != '_') {
					break;
				}
				index++;
			}
			return text.substring(start, index);
		}

		private boolean consumeChar(char expected) {
			skipSpaceAndComments();
			if (index < text.length() && text.charAt(index) == expected) {
				index++;
				return true;
			}
			return false;
		}

		private boolean skipBalanced(char open, char close) {
			int depth = 1;
			while (index < text.length()) {
				char ch = text.charAt(index);
				if (ch == '\'') {
					skipSingleQuote();
					continue;
				}
				if (ch == '"') {
					skipDoubleQuote();
					continue;
				}
				if (ch == '`') {
					skipBacktick();
					continue;
				}
				if (ch == '$' && peekNext() == '$') {
					skipDollarQuote();
					continue;
				}
				if (ch == '-' && peekNext() == '-') {
					skipLineComment();
					continue;
				}
				if (ch == '/' && peekNext() == '*') {
					skipBlockComment();
					continue;
				}
				if (ch == open) {
					depth++;
					index++;
					continue;
				}
				if (ch == close) {
					depth--;
					index++;
					if (depth == 0) {
						return true;
					}
					continue;
				}
				index++;
			}
			return false;
		}

		private void skipLineComment() {
			index += 2;
			while (index < text.length() && text.charAt(index) != '\n') {
				index++;
			}
		}

		private void skipBlockComment() {
			index += 2;
			while (index + 1 < text.length()) {
				if (text.charAt(index) == '*' && text.charAt(index + 1) == '/') {
					index += 2;
					return;
				}
				index++;
			}
		}

		private void skipSingleQuote() {
			index++;
			while (index < text.length()) {
				char ch = text.charAt(index);
				if (ch == '\'') {
					if (peekNext() == '\'') {
						index += 2;
						continue;
					}
					index++;
					return;
				}
				index++;
			}
		}

		private void skipDoubleQuote() {
			index++;
			while (index < text.length()) {
				char ch = text.charAt(index);
				if (ch == '"') {
					index++;
					return;
				}
				index++;
			}
		}

		private void skipBacktick() {
			index++;
			while (index < text.length()) {
				char ch = text.charAt(index);
				if (ch == '`') {
					index++;
					return;
				}
				index++;
			}
		}

		private void skipDollarQuote() {
			index += 2;
			while (index + 1 < text.length()) {
				if (text.charAt(index) == '$' && text.charAt(index + 1) == '$') {
					index += 2;
					return;
				}
				index++;
			}
		}

		private char peekNext() {
			if (index + 1 >= text.length()) {
				return '\0';
			}
			return text.charAt(index + 1);
		}
	}
}
