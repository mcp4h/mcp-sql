package app;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Config {
	public List<ConnectionConfig> connections;
	public String defaultConnection;
	public QueryDefaults query;
	public IntrospectDefaults introspect;

	public Config() {
		this.connections = new ArrayList<>();
		this.defaultConnection = null;
		this.query = QueryDefaults.defaultDefaults();
		this.introspect = IntrospectDefaults.defaultDefaults();
	}

	public Config(Config other) {
		this.connections = new ArrayList<>();
		for (ConnectionConfig connection : other.connections) {
			this.connections.add(new ConnectionConfig(connection));
		}
		this.defaultConnection = other.defaultConnection;
		this.query = new QueryDefaults(other.query);
		this.introspect = new IntrospectDefaults(other.introspect);
	}

	public static Config defaultConfig() {
		return new Config();
	}

	public Config applyOverride(JsonNode value) throws ConfigException {
		if (value == null || value.isNull()) {
			return this;
		}
		if (!value.isObject()) {
			throw new ConfigException("configuration must be an object");
		}
		Config next = new Config(this);
		Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> entry = fields.next();
			String key = entry.getKey();
			JsonNode node = entry.getValue();
			switch (key) {
				case "connections" -> next.connections = parseConnections(node);
				case "default_connection" -> next.defaultConnection = readOptionalString(node, "default_connection");
				case "query" -> next.query = next.query.applyOverride(node);
				case "introspect" -> next.introspect = next.introspect.applyOverride(node);
				default -> throw new ConfigException("unknown config key: " + key);
			}
		}
		validateDefaultConnection(next);
		return next;
	}

	private static void validateDefaultConnection(Config config) throws ConfigException {
		if (config.connections.isEmpty()) {
			config.defaultConnection = null;
			return;
		}
		if (config.defaultConnection == null || config.defaultConnection.isBlank()) {
			config.defaultConnection = config.connections.get(0).name;
			return;
		}
		for (ConnectionConfig connection : config.connections) {
			if (connection.name.equals(config.defaultConnection)) {
				return;
			}
		}
		throw new ConfigException("default_connection must match a configured connection name");
	}

	private static List<ConnectionConfig> parseConnections(JsonNode node) throws ConfigException {
		if (node == null || node.isNull()) {
			return Collections.emptyList();
		}
		if (!node.isArray()) {
			throw new ConfigException("connections must be an array");
		}
		List<ConnectionConfig> result = new ArrayList<>();
		Map<String, Boolean> seen = new HashMap<>();
		for (JsonNode item : node) {
			if (!item.isObject()) {
				throw new ConfigException("connection must be an object");
			}
			ConnectionConfig connection = ConnectionConfig.fromNode(item);
			if (seen.containsKey(connection.name)) {
				throw new ConfigException("duplicate connection name: " + connection.name);
			}
			seen.put(connection.name, true);
			result.add(connection);
		}
		return result;
	}

	public static final class ConnectionConfig {
		public String name;
		public String url;
		public String driver;
		public String username;
		public String password;
		public String passwordEnv;
		public String defaultSchema;
		public List<String> allowedSchemas;
		public String catalog;
		public PoolConfig pool;
		public SshConfig ssh;

		public ConnectionConfig() {
			this.name = null;
			this.url = null;
			this.driver = null;
			this.username = null;
			this.password = null;
			this.passwordEnv = null;
			this.defaultSchema = null;
			this.allowedSchemas = new ArrayList<>();
			this.catalog = null;
			this.pool = PoolConfig.defaultDefaults();
			this.ssh = null;
		}

		public ConnectionConfig(ConnectionConfig other) {
			this.name = other.name;
			this.url = other.url;
			this.driver = other.driver;
			this.username = other.username;
			this.password = other.password;
			this.passwordEnv = other.passwordEnv;
			this.defaultSchema = other.defaultSchema;
			this.allowedSchemas = new ArrayList<>(other.allowedSchemas);
			this.catalog = other.catalog;
			this.pool = new PoolConfig(other.pool);
			this.ssh = other.ssh == null ? null : new SshConfig(other.ssh);
		}

		public static ConnectionConfig fromNode(JsonNode node) throws ConfigException {
			ConnectionConfig connection = new ConnectionConfig();
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				String key = entry.getKey();
				JsonNode value = entry.getValue();
				switch (key) {
					case "name" -> connection.name = readRequiredString(value, "name");
					case "url" -> connection.url = readRequiredString(value, "url");
					case "driver" -> connection.driver = readRequiredString(value, "driver");
					case "username" -> connection.username = readOptionalString(value, "username");
					case "password" -> connection.password = readOptionalString(value, "password");
					case "password_env" -> connection.passwordEnv = readOptionalString(value, "password_env");
				case "default_schema" -> connection.defaultSchema = readOptionalString(value, "default_schema");
				case "allowed_schemas" -> connection.allowedSchemas = readStringList(value, "allowed_schemas");
				case "catalog" -> connection.catalog = readOptionalString(value, "catalog");
				case "pool" -> connection.pool = connection.pool.applyOverride(value);
				case "ssh" -> {
					SshConfig next = connection.ssh == null ? new SshConfig() : new SshConfig(connection.ssh);
					connection.ssh = next.applyOverride(value);
				}
				default -> throw new ConfigException("unknown connection key: " + key);
			}
		}
			if (connection.name == null || connection.url == null || connection.driver == null) {
				throw new ConfigException("connections require name, url, and driver");
			}
			Config.resolveDriver(connection.driver);
			if (connection.ssh != null) {
				connection.ssh.validate();
			}
			return connection;
		}
	}

	public static final class SshConfig {
		public String host;
		public int port;
		public String user;
		public String keyPath;
		public String keyPathEnv;
		public String keyPassphrase;
		public String keyPassphraseEnv;
		public String password;
		public String passwordEnv;
		public String remoteHost;
		public int remotePort;
		public Integer localPort;
		public String knownHostsPath;
		public boolean trustOnFirstUse;

		public SshConfig() {
			this.host = null;
			this.port = 22;
			this.user = null;
			this.keyPath = null;
			this.keyPathEnv = null;
			this.keyPassphrase = null;
			this.keyPassphraseEnv = null;
			this.password = null;
			this.passwordEnv = null;
			this.remoteHost = null;
			this.remotePort = 0;
			this.localPort = null;
			this.knownHostsPath = null;
			this.trustOnFirstUse = false;
		}

		public SshConfig(SshConfig other) {
			this.host = other.host;
			this.port = other.port;
			this.user = other.user;
			this.keyPath = other.keyPath;
			this.keyPathEnv = other.keyPathEnv;
			this.keyPassphrase = other.keyPassphrase;
			this.keyPassphraseEnv = other.keyPassphraseEnv;
			this.password = other.password;
			this.passwordEnv = other.passwordEnv;
			this.remoteHost = other.remoteHost;
			this.remotePort = other.remotePort;
			this.localPort = other.localPort;
			this.knownHostsPath = other.knownHostsPath;
			this.trustOnFirstUse = other.trustOnFirstUse;
		}

		public SshConfig applyOverride(JsonNode node) throws ConfigException {
			if (node == null || node.isNull()) {
				return this;
			}
			if (!node.isObject()) {
				throw new ConfigException("ssh must be an object");
			}
			SshConfig next = new SshConfig(this);
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				String key = entry.getKey();
				JsonNode value = entry.getValue();
				switch (key) {
					case "host" -> next.host = readOptionalString(value, "host");
					case "port" -> next.port = readPositiveInt(value, "port");
					case "user" -> next.user = readOptionalString(value, "user");
					case "key_path" -> next.keyPath = readOptionalString(value, "key_path");
					case "key_path_env" -> next.keyPathEnv = readOptionalString(value, "key_path_env");
					case "key_passphrase" -> next.keyPassphrase = readOptionalString(value, "key_passphrase");
					case "key_passphrase_env" -> next.keyPassphraseEnv = readOptionalString(value, "key_passphrase_env");
					case "password" -> next.password = readOptionalString(value, "password");
					case "password_env" -> next.passwordEnv = readOptionalString(value, "password_env");
				case "remote_host" -> next.remoteHost = readOptionalString(value, "remote_host");
				case "remote_port" -> next.remotePort = readPositiveInt(value, "remote_port");
					case "local_port" -> next.localPort = readOptionalNonNegativeInt(value, "local_port");
					case "known_hosts_path" -> next.knownHostsPath = readOptionalString(value, "known_hosts_path");
					case "trust_on_first_use" -> next.trustOnFirstUse = readBoolean(value, "trust_on_first_use");
					default -> throw new ConfigException("unknown ssh key: " + key);
				}
			}
			return next;
		}

		public void validate() throws ConfigException {
			if (host == null || host.isBlank()) {
				throw new ConfigException("ssh.host is required when ssh is enabled");
			}
			if (user == null || user.isBlank()) {
				throw new ConfigException("ssh.user is required when ssh is enabled");
			}
			if ((keyPath == null || keyPath.isBlank()) && (keyPathEnv == null || keyPathEnv.isBlank())
					&& (password == null || password.isBlank()) && (passwordEnv == null || passwordEnv.isBlank())) {
				throw new ConfigException("ssh requires key_path/key_path_env or password/password_env");
			}
		}
	}

	public static final class PoolConfig {
		public int maxPoolSize;
		public int minIdle;
		public long idleTimeoutMs;
		public long maxLifetimeMs;
		public long connectionTimeoutMs;

		public PoolConfig() {
			this.maxPoolSize = 10;
			this.minIdle = 1;
			this.idleTimeoutMs = 300000;
			this.maxLifetimeMs = 1800000;
			this.connectionTimeoutMs = 30000;
		}

		public PoolConfig(PoolConfig other) {
			this.maxPoolSize = other.maxPoolSize;
			this.minIdle = other.minIdle;
			this.idleTimeoutMs = other.idleTimeoutMs;
			this.maxLifetimeMs = other.maxLifetimeMs;
			this.connectionTimeoutMs = other.connectionTimeoutMs;
		}

		public static PoolConfig defaultDefaults() {
			return new PoolConfig();
		}

		public PoolConfig applyOverride(JsonNode node) throws ConfigException {
			if (node == null || node.isNull()) {
				return this;
			}
			if (!node.isObject()) {
				throw new ConfigException("pool must be an object");
			}
			PoolConfig next = new PoolConfig(this);
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				String key = entry.getKey();
				JsonNode value = entry.getValue();
				switch (key) {
					case "max_pool_size" -> next.maxPoolSize = readPositiveInt(value, "max_pool_size");
					case "min_idle" -> next.minIdle = readNonNegativeInt(value, "min_idle");
					case "idle_timeout_ms" -> next.idleTimeoutMs = readPositiveLong(value, "idle_timeout_ms");
					case "max_lifetime_ms" -> next.maxLifetimeMs = readPositiveLong(value, "max_lifetime_ms");
					case "connection_timeout_ms" -> next.connectionTimeoutMs = readPositiveLong(value, "connection_timeout_ms");
					default -> throw new ConfigException("unknown pool key: " + key);
				}
			}
			return next;
		}
	}

	public static final class QueryDefaults {
		public int maxRows;
		public int timeoutMs;
		public Integer fetchSize;
		public Integer previewMaxRows;
		public Integer previewDisplayRows;
		public boolean includeSelectReview;

		public QueryDefaults() {
			this.maxRows = 200;
			this.timeoutMs = 30000;
			this.fetchSize = null;
			this.previewMaxRows = null;
			this.previewDisplayRows = 10;
			this.includeSelectReview = true;
		}

		public QueryDefaults(QueryDefaults other) {
			this.maxRows = other.maxRows;
			this.timeoutMs = other.timeoutMs;
			this.fetchSize = other.fetchSize;
			this.previewMaxRows = other.previewMaxRows;
			this.previewDisplayRows = other.previewDisplayRows;
			this.includeSelectReview = other.includeSelectReview;
		}

		public static QueryDefaults defaultDefaults() {
			return new QueryDefaults();
		}

		public QueryDefaults applyOverride(JsonNode node) throws ConfigException {
			if (node == null || node.isNull()) {
				return this;
			}
			if (!node.isObject()) {
				throw new ConfigException("query must be an object");
			}
			QueryDefaults next = new QueryDefaults(this);
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				String key = entry.getKey();
				JsonNode value = entry.getValue();
				switch (key) {
				case "max_rows" -> next.maxRows = readPositiveInt(value, "max_rows");
				case "timeout_ms" -> next.timeoutMs = readPositiveInt(value, "timeout_ms");
				case "fetch_size" -> next.fetchSize = readOptionalPositiveInt(value, "fetch_size");
				case "preview_max_rows" -> next.previewMaxRows = readOptionalPositiveInt(value, "preview_max_rows");
				case "preview_display_rows" -> next.previewDisplayRows = readOptionalPositiveInt(value, "preview_display_rows");
				case "include_select_review" -> next.includeSelectReview = readBoolean(value, "include_select_review");
				default -> throw new ConfigException("unknown query key: " + key);
			}
			}
			return next;
		}
	}

	public static final class IntrospectDefaults {
		public int maxTokens;
		public int limit;
		public boolean includeViews;
		public boolean includeIndexes;
		public boolean includeProcedures;
		public boolean includeTables;
		public int relationDepth;

		public IntrospectDefaults() {
			this.maxTokens = 8000;
			this.limit = 200;
			this.includeViews = true;
			this.includeIndexes = false;
			this.includeProcedures = false;
			this.includeTables = true;
			this.relationDepth = 1;
		}

		public IntrospectDefaults(IntrospectDefaults other) {
			this.maxTokens = other.maxTokens;
			this.limit = other.limit;
			this.includeViews = other.includeViews;
			this.includeIndexes = other.includeIndexes;
			this.includeProcedures = other.includeProcedures;
			this.includeTables = other.includeTables;
			this.relationDepth = other.relationDepth;
		}

		public static IntrospectDefaults defaultDefaults() {
			return new IntrospectDefaults();
		}

		public IntrospectDefaults applyOverride(JsonNode node) throws ConfigException {
			if (node == null || node.isNull()) {
				return this;
			}
			if (!node.isObject()) {
				throw new ConfigException("introspect must be an object");
			}
			IntrospectDefaults next = new IntrospectDefaults(this);
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				String key = entry.getKey();
				JsonNode value = entry.getValue();
				switch (key) {
				case "max_tokens" -> next.maxTokens = readPositiveInt(value, "max_tokens");
				case "limit" -> next.limit = readPositiveInt(value, "limit");
				case "include_views" -> next.includeViews = readBoolean(value, "include_views");
				case "include_indexes" -> next.includeIndexes = readBoolean(value, "include_indexes");
				case "include_procedures" -> next.includeProcedures = readBoolean(value, "include_procedures");
				case "include_tables" -> next.includeTables = readBoolean(value, "include_tables");
				case "relation_depth" -> next.relationDepth = readPositiveInt(value, "relation_depth");
				default -> throw new ConfigException("unknown introspect key: " + key);
			}
		}
		return next;
	}
	}

	public static final class Policy {
		public String defaultConnection;
		public List<String> allowedConnections;
		public List<String> deniedConnections;
		public Integer maxRows;
		public Integer timeoutMs;
		public Integer introspectMaxTokens;
		public Integer relationDepth;
		public Boolean allowSelect;
		public Boolean allowWrite;
		public Boolean allowDdl;
		public Boolean allowExecute;
		public Boolean allowIntrospect;
		public List<String> allowSchemas;
		public List<String> denySchemas;
		public List<String> allowTables;
		public List<String> denyTables;
		public List<String> allowColumns;
		public List<String> denyColumns;

		public Policy() {
			this.defaultConnection = null;
			this.allowedConnections = Collections.emptyList();
			this.deniedConnections = Collections.emptyList();
			this.maxRows = null;
			this.timeoutMs = null;
			this.introspectMaxTokens = null;
			this.relationDepth = null;
			this.allowSelect = null;
			this.allowWrite = null;
			this.allowDdl = null;
			this.allowExecute = null;
			this.allowIntrospect = null;
			this.allowSchemas = Collections.emptyList();
			this.denySchemas = Collections.emptyList();
			this.allowTables = Collections.emptyList();
			this.denyTables = Collections.emptyList();
			this.allowColumns = Collections.emptyList();
			this.denyColumns = Collections.emptyList();
		}
	}

	public static Policy parsePolicy(JsonNode node) throws ConfigException {
		Policy policy = new Policy();
		if (node == null || node.isNull()) {
			return policy;
		}
		if (!node.isObject()) {
			throw new ConfigException("policy must be an object");
		}
		Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> entry = fields.next();
			String key = entry.getKey();
			JsonNode value = entry.getValue();
			switch (key) {
				case "default_connection" -> policy.defaultConnection = readOptionalString(value, "default_connection");
				case "allowed_connections" -> policy.allowedConnections = readStringList(value, "allowed_connections");
				case "denied_connections" -> policy.deniedConnections = readStringList(value, "denied_connections");
				case "max_rows" -> policy.maxRows = readOptionalPositiveInt(value, "max_rows");
				case "timeout_ms" -> policy.timeoutMs = readOptionalPositiveInt(value, "timeout_ms");
				case "introspect_max_tokens" -> policy.introspectMaxTokens = readOptionalPositiveInt(value, "introspect_max_tokens");
				case "relation_depth" -> policy.relationDepth = readOptionalPositiveInt(value, "relation_depth");
				case "allow_select" -> policy.allowSelect = readOptionalBoolean(value, "allow_select");
				case "allow_write" -> policy.allowWrite = readOptionalBoolean(value, "allow_write");
				case "allow_ddl" -> policy.allowDdl = readOptionalBoolean(value, "allow_ddl");
				case "allow_execute" -> policy.allowExecute = readOptionalBoolean(value, "allow_execute");
				case "allow_introspect" -> policy.allowIntrospect = readOptionalBoolean(value, "allow_introspect");
				case "allow_schemas" -> policy.allowSchemas = readStringList(value, "allow_schemas");
				case "deny_schemas" -> policy.denySchemas = readStringList(value, "deny_schemas");
				case "allow_tables" -> policy.allowTables = readStringList(value, "allow_tables");
				case "deny_tables" -> policy.denyTables = readStringList(value, "deny_tables");
				case "allow_columns" -> policy.allowColumns = readStringList(value, "allow_columns");
				case "deny_columns" -> policy.denyColumns = readStringList(value, "deny_columns");
				default -> throw new ConfigException("unknown policy key: " + key);
			}
		}
		return policy;
	}

	public static final class DriverInfo {
		public final String driverClass;
		public final String vendor;

		public DriverInfo(String driverClass, String vendor) {
			this.driverClass = driverClass;
			this.vendor = vendor;
		}
	}

	public static DriverInfo resolveDriver(String driverKey) throws ConfigException {
		if (driverKey == null || driverKey.isBlank()) {
			throw new ConfigException("driver must be a non-empty string");
		}
		String normalized = driverKey.trim().toLowerCase(Locale.ROOT);
		switch (normalized) {
			case "postgresql", "postgres" -> {
				return new DriverInfo("org.postgresql.Driver", "PostgreSQL");
			}
			case "mysql" -> {
				return new DriverInfo("com.mysql.cj.jdbc.Driver", "MySQL");
			}
			case "sqlserver", "mssql" -> {
				return new DriverInfo("com.microsoft.sqlserver.jdbc.SQLServerDriver", "SQL Server");
			}
			case "oracle" -> {
				return new DriverInfo("oracle.jdbc.OracleDriver", "Oracle");
			}
			case "h2" -> {
				return new DriverInfo("org.h2.Driver", "H2");
			}
			default -> {
				throw new ConfigException("unknown driver keyword: " + driverKey);
			}
		}
	}

	public static final class ConfigException extends Exception {
		public ConfigException(String message) {
			super(message);
		}
	}

	private static String readRequiredString(JsonNode node, String name) throws ConfigException {
		if (node == null || node.isNull()) {
			throw new ConfigException(name + " must be a non-empty string");
		}
		String text = node.asText();
		if (text == null || text.trim().isEmpty()) {
			throw new ConfigException(name + " must be a non-empty string");
		}
		return text.trim();
	}

	private static String readOptionalString(JsonNode node, String name) throws ConfigException {
		if (node == null || node.isNull()) {
			return null;
		}
		String text = node.asText();
		if (text == null || text.trim().isEmpty()) {
			return null;
		}
		return text.trim();
	}

	private static boolean readBoolean(JsonNode node, String name) throws ConfigException {
		if (node == null || node.isNull()) {
			throw new ConfigException(name + " must be a boolean");
		}
		if (!node.isBoolean()) {
			throw new ConfigException(name + " must be a boolean");
		}
		return node.asBoolean();
	}

	private static Boolean readOptionalBoolean(JsonNode node, String name) throws ConfigException {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isBoolean()) {
			throw new ConfigException(name + " must be a boolean");
		}
		return node.asBoolean();
	}

	private static int readPositiveInt(JsonNode node, String name) throws ConfigException {
		if (node == null || node.isNull() || !node.isNumber()) {
			throw new ConfigException(name + " must be a positive integer");
		}
		int value = node.asInt();
		if (value < 1) {
			throw new ConfigException(name + " must be a positive integer");
		}
		return value;
	}

	private static int readNonNegativeInt(JsonNode node, String name) throws ConfigException {
		if (node == null || node.isNull() || !node.isNumber()) {
			throw new ConfigException(name + " must be a non-negative integer");
		}
		int value = node.asInt();
		if (value < 0) {
			throw new ConfigException(name + " must be a non-negative integer");
		}
		return value;
	}

	private static long readPositiveLong(JsonNode node, String name) throws ConfigException {
		if (node == null || node.isNull() || !node.isNumber()) {
			throw new ConfigException(name + " must be a positive integer");
		}
		long value = node.asLong();
		if (value < 1) {
			throw new ConfigException(name + " must be a positive integer");
		}
		return value;
	}

	private static Integer readOptionalPositiveInt(JsonNode node, String name) throws ConfigException {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isNumber()) {
			throw new ConfigException(name + " must be a positive integer");
		}
		int value = node.asInt();
		if (value < 1) {
			throw new ConfigException(name + " must be a positive integer");
		}
		return value;
	}

	private static Integer readOptionalNonNegativeInt(JsonNode node, String name) throws ConfigException {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isNumber()) {
			throw new ConfigException(name + " must be a non-negative integer");
		}
		int value = node.asInt();
		if (value < 0) {
			throw new ConfigException(name + " must be a non-negative integer");
		}
		return value;
	}

	private static List<String> readStringList(JsonNode node, String name) throws ConfigException {
		if (node == null || node.isNull()) {
			return Collections.emptyList();
		}
		if (node.isArray()) {
			List<String> result = new ArrayList<>();
			for (JsonNode item : node) {
				String text = item.asText();
				if (text != null && !text.trim().isEmpty()) {
					result.add(text.trim());
				}
			}
			return result;
		}
		if (node.isTextual()) {
			String text = node.asText().trim();
			if (text.isEmpty()) {
				return Collections.emptyList();
			}
			return List.of(text);
		}
		throw new ConfigException(name + " must be a string or array of strings");
	}
}
