package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class ConfigSchema {
	public static JsonNode build(ObjectMapper mapper) {
		ObjectNode root = mapper.createObjectNode();
		root.put("$schema", "http://json-schema.org/draft-07/schema#");
		root.put("title", "mcp-sql configuration");
		root.put("type", "object");
		root.put("additionalProperties", false);
		ObjectNode props = root.putObject("properties");

		ObjectNode connections = props.putObject("connections");
		connections.put("type", "array");
		connections.put("minItems", 0);
		connections.put("description", "Named JDBC connections available to the server.");
		ObjectNode connectionItem = connections.putObject("items");
		connectionItem.put("type", "object");
		connectionItem.put("additionalProperties", false);
		ObjectNode connectionProps = connectionItem.putObject("properties");
		connectionProps.putObject("name").put("type", "string").put("description", "Connection name.");
		connectionProps.putObject("url").put("type", "string").put("description", "JDBC URL.");
		ObjectNode driver = connectionProps.putObject("driver");
		driver.put("type", "string");
		ArrayNode driverEnum = driver.putArray("enum");
		driverEnum.add("postgresql");
		driverEnum.add("mysql");
		driverEnum.add("sqlserver");
		driverEnum.add("oracle");
		driverEnum.add("h2");
		driver.put("description", "Driver keyword.");
		connectionProps.putObject("username").put("type", "string").put("description", "Database username.");
		connectionProps.putObject("password").put("type", "string").put("description", "Database password.").put("audience", "human");
		connectionProps.putObject("password_env").put("type", "string").put("description", "Environment variable containing the password.").put("audience", "human");
		connectionProps.putObject("default_schema").put("type", "string").put("description", "Default schema for the connection.");
		connectionProps.set("allowed_schemas", stringListSchema(mapper, "Schema allowlist for this connection."));
		connectionProps.putObject("catalog").put("type", "string").put("description", "Default catalog for the connection.");
		ObjectNode ssh = connectionProps.putObject("ssh");
		ssh.put("type", "object");
		ssh.put("additionalProperties", false);
		ObjectNode sshProps = ssh.putObject("properties");
		sshProps.putObject("host").put("type", "string").put("description", "SSH host.");
		sshProps.putObject("port").put("type", "integer").put("minimum", 1).put("default", 22).put("description", "SSH port.");
		sshProps.putObject("user").put("type", "string").put("description", "SSH username.");
		sshProps.putObject("key_path").put("type", "string").put("description", "Path to private key file.").put("audience", "human");
		sshProps.putObject("key_path_env").put("type", "string").put("description", "Env var containing private key path.").put("audience", "human");
		sshProps.putObject("key_passphrase").put("type", "string").put("description", "Private key passphrase.").put("audience", "human");
		sshProps.putObject("key_passphrase_env").put("type", "string").put("description", "Env var containing key passphrase.").put("audience", "human");
		sshProps.putObject("password").put("type", "string").put("description", "SSH password.").put("audience", "human");
		sshProps.putObject("password_env").put("type", "string").put("description", "Env var containing SSH password.").put("audience", "human");
		sshProps.putObject("remote_host").put("type", "string").put("description", "Remote database host from the SSH host (optional, inferred from JDBC URL when omitted).");
		sshProps.putObject("remote_port").put("type", "integer").put("minimum", 1).put("description", "Remote database port from the SSH host (optional, inferred from JDBC URL when omitted).");
		sshProps.putObject("local_port").put("type", "integer").put("minimum", 0).put("description", "Local port to bind (0 for auto)." );
		sshProps.putObject("known_hosts_path").put("type", "string").put("description", "Known hosts file path (optional)." );
		sshProps.putObject("trust_on_first_use").put("type", "boolean").put("description", "Trust unknown host keys on first use." );
		ObjectNode pool = connectionProps.putObject("pool");
		pool.put("type", "object");
		pool.put("additionalProperties", false);
		ObjectNode poolProps = pool.putObject("properties");
		poolProps.putObject("max_pool_size").put("type", "integer").put("minimum", 1).put("default", 10).put("description", "Maximum pool size.");
		poolProps.putObject("min_idle").put("type", "integer").put("minimum", 0).put("default", 1).put("description", "Minimum idle connections.");
		poolProps.putObject("idle_timeout_ms").put("type", "integer").put("minimum", 1).put("default", 300000).put("description", "Idle timeout in milliseconds.");
		poolProps.putObject("max_lifetime_ms").put("type", "integer").put("minimum", 1).put("default", 1800000).put("description", "Max connection lifetime in milliseconds.");
		poolProps.putObject("connection_timeout_ms").put("type", "integer").put("minimum", 1).put("default", 30000).put("description", "Connection acquisition timeout in milliseconds.");
		ArrayNode connectionRequired = connectionItem.putArray("required");
		connectionRequired.add("name");
		connectionRequired.add("url");
		connectionRequired.add("driver");

		ObjectNode defaultConn = props.putObject("default_connection");
		defaultConn.put("type", "string");
		defaultConn.put("description", "Default connection name used when a tool call omits it.");

		ObjectNode query = props.putObject("query");
		query.put("type", "object");
		query.put("additionalProperties", false);
		query.put("description", "Default query execution limits.");
		ObjectNode queryProps = query.putObject("properties");
		queryProps.putObject("max_rows").put("type", "integer").put("minimum", 1).put("default", 200).put("description", "Maximum rows returned by SELECT.");
		queryProps.putObject("timeout_ms").put("type", "integer").put("minimum", 1).put("default", 30000).put("description", "Statement timeout in milliseconds.");
		queryProps.putObject("fetch_size").put("type", "integer").put("minimum", 1).put("description", "Optional JDBC fetch size.");
		queryProps.putObject("preview_max_rows").put("type", "integer").put("minimum", 1).put("description", "Maximum rows returned by preview DML.");
		queryProps.putObject("preview_display_rows").put("type", "integer").put("minimum", 1).put("description", "Rows shown by default in preview HTML.");
		queryProps.putObject("include_select_review").put("type", "boolean").put("default", false).put("description", "Include review HTML for SELECT results.");

		ObjectNode introspect = props.putObject("introspect");
		introspect.put("type", "object");
		introspect.put("additionalProperties", false);
		introspect.put("description", "Default limits for schema introspection.");
		ObjectNode introspectProps = introspect.putObject("properties");
		introspectProps.putObject("max_tokens").put("type", "integer").put("minimum", 1).put("default", 8000).put("description", "Approximate token budget for metadata responses.");
		introspectProps.putObject("limit").put("type", "integer").put("minimum", 1).put("default", 200).put("description", "Maximum objects to include.");
		introspectProps.putObject("include_views").put("type", "boolean").put("default", true).put("description", "Include views in table listing.");
		introspectProps.putObject("include_indexes").put("type", "boolean").put("default", false).put("description", "Include indexes in metadata.");
		introspectProps.putObject("include_procedures").put("type", "boolean").put("default", false).put("description", "Include stored procedures in metadata.");
		introspectProps.putObject("include_tables").put("type", "boolean").put("default", true).put("description", "Include tables in metadata.");
		introspectProps.putObject("relation_depth").put("type", "integer").put("minimum", 1).put("default", 1).put("description", "Depth of related tables to include via foreign keys.");

		return root;
	}

	private static ObjectNode stringListSchema(ObjectMapper mapper, String description) {
		ObjectNode schema = mapper.createObjectNode();
		ArrayNode anyOf = schema.putArray("anyOf");
		ObjectNode arraySchema = mapper.createObjectNode();
		arraySchema.put("type", "array");
		arraySchema.putObject("items").put("type", "string");
		ObjectNode stringSchema = mapper.createObjectNode();
		stringSchema.put("type", "string");
		anyOf.add(arraySchema);
		anyOf.add(stringSchema);
		schema.put("description", description);
		return schema;
	}

	private ConfigSchema() {}
}
