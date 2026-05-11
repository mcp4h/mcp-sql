package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MetadataService {
	private final ObjectMapper mapper;
	private final Config config;

	public MetadataService(ObjectMapper mapper, Config config) {
		this.mapper = mapper;
		this.config = config;
	}

	public ObjectNode introspect(Connection connection, Config.ConnectionConfig connectionConfig,
			IntrospectRequest request, Config.Policy policy) throws Exception {
		if (policy.allowIntrospect != null && !policy.allowIntrospect) {
			throw new Config.ConfigException("introspection is disabled by policy");
		}
		DatabaseMetaData meta = connection.getMetaData();
		String vendor = meta.getDatabaseProductName();
		ObjectNode payload = mapper.createObjectNode();
		payload.put("vendor", vendor);
		payload.put("connection", connectionConfig.name);
		TokenBudget budget = resolveBudget(request, policy);
		payload.put("tokenBudget", budget.maxTokens);
		ArrayNode schemasNode = payload.putArray("schemas");
		boolean includeViews = request.includeViews == null ? config.introspect.includeViews : request.includeViews;
		boolean includeIndexes = request.includeIndexes == null ? config.introspect.includeIndexes : request.includeIndexes;
		boolean includeProcedures = request.includeProcedures == null ? config.introspect.includeProcedures : request.includeProcedures;
		boolean includeTables = request.includeTables == null ? config.introspect.includeTables : request.includeTables;
		boolean includeColumns = request.includeColumns != null && request.includeColumns;
		List<String> schemaPatterns = resolveSchemaPatterns(request.schema, connectionConfig, vendor);
		List<String> searchTerms = resolveSearchTerms(request.search);
		int limit = request.limit == null ? config.introspect.limit : request.limit;
		int offset = request.offset == null ? 0 : Math.max(0, request.offset);
		int relationDepth = resolveRelationDepth(policy);
		limit = Math.max(1, limit);
		Map<String, ObjectNode> schemaMap = new HashMap<>();
		Set<String> tableSeen = new HashSet<>();
		ResultCursor cursor = new ResultCursor(limit, offset);
		boolean truncated = false;
		String[] types = includeViews ? new String[] { "TABLE", "VIEW" } : new String[] { "TABLE" };
		for (String schemaPattern : schemaPatterns) {
			if (!includeTables && !includeViews) {
				break;
			}
			try (ResultSet tables = meta.getTables(connectionConfig.catalog, schemaPattern, null, types)) {
				while (tables.next()) {
					String schema = tables.getString("TABLE_SCHEM");
					String table = tables.getString("TABLE_NAME");
					String type = tables.getString("TABLE_TYPE");
					boolean isView = "VIEW".equalsIgnoreCase(type);
					if (isView && !includeViews) {
						continue;
					}
					if (!isView && !includeTables) {
						continue;
					}
					if (!isAllowedSchema(schema, connectionConfig, policy, vendor)) {
						continue;
					}
					if (isSystemTable(schema, table, vendor)) {
						continue;
					}
					if (!matchesPolicy(table, policy.allowTables, policy.denyTables)) {
						continue;
					}
					if (!matchesSearch(table, searchTerms)) {
						continue;
					}
					String tableKey = (schema == null ? "" : schema) + "." + table;
					if (tableSeen.contains(tableKey)) {
						continue;
					}
					ResultCursor.State state = cursor.next();
					if (state == ResultCursor.State.FULL) {
						truncated = true;
						break;
					}
					if (state == ResultCursor.State.SKIP) {
						continue;
					}
					ObjectNode schemaNode = schemaMap.computeIfAbsent(schema == null ? "" : schema, name -> {
						ObjectNode node = mapper.createObjectNode();
						node.put("name", name);
						node.putArray("objects");
						return node;
					});
					ArrayNode objects = (ArrayNode) schemaNode.get("objects");
					ObjectNode tableNode = objects.addObject();
					tableNode.put("name", table);
					tableNode.put("type", isView ? "view" : "table");
					if (!budget.consume(table)) {
						truncated = true;
						break;
					}
					if (includeColumns) {
						TableDetails details = buildTableDetails(meta, connectionConfig, schema, table, includeIndexes, budget, policy);
						tableNode.set("columns", details.columns);
						tableNode.set("primaryKeys", details.primaryKeys);
						tableNode.set("foreignKeys", details.foreignKeys);
						if (details.indexes != null) {
							tableNode.set("indexes", details.indexes);
						}
						if (details.truncated) {
							truncated = true;
							break;
						}
					}
					tableSeen.add(tableKey);
					if (budget.exhausted()) {
						truncated = true;
						break;
					}
				}
			}
			if (truncated) {
				break;
			}
		}
		if (includeColumns && relationDepth > 0) {
			boolean expanded = expandRelations(meta, connectionConfig, schemaMap, tableSeen, relationDepth,
					budget, policy, vendor);
			if (!expanded) {
				truncated = true;
			}
		}
		if (includeProcedures) {
			boolean proceduresAdded = addProcedures(meta, connectionConfig, schemaPatterns, schemaMap, budget, vendor,
					searchTerms, cursor);
			if (!proceduresAdded) {
				truncated = true;
			}
		}
		for (ObjectNode schemaNode : schemaMap.values()) {
			schemasNode.add(schemaNode);
		}
		payload.put("objectCount", cursor.count);
		if (offset > 0) {
			payload.put("offset", offset);
		}
		payload.put("truncated", truncated);
		payload.put("tokensUsed", budget.usedTokens);
		return payload;
	}

	private boolean addProcedures(DatabaseMetaData meta, Config.ConnectionConfig connectionConfig,
			List<String> schemaPatterns, Map<String, ObjectNode> schemaMap, TokenBudget budget, String vendor,
			List<String> searchTerms, ResultCursor cursor) throws SQLException {
		for (String schemaPattern : schemaPatterns) {
			try (ResultSet procedures = meta.getProcedures(connectionConfig.catalog, schemaPattern, null)) {
				while (procedures.next()) {
					String schema = procedures.getString("PROCEDURE_SCHEM");
					String name = procedures.getString("PROCEDURE_NAME");
					if (!matchesSearch(name, searchTerms)) {
						continue;
					}
					if (schema == null || isSystemSchema(schema, vendor)) {
						continue;
					}
					ResultCursor.State state = cursor.next();
					if (state == ResultCursor.State.FULL) {
						return false;
					}
					if (state == ResultCursor.State.SKIP) {
						continue;
					}
					ObjectNode schemaNode = schemaMap.computeIfAbsent(schema, key -> {
						ObjectNode node = mapper.createObjectNode();
						node.put("name", key);
						node.putArray("objects");
						return node;
					});
					ArrayNode list = (ArrayNode) schemaNode.get("objects");
					ObjectNode proc = list.addObject();
					proc.put("name", name);
					proc.put("type", "procedure");
					String remarks = procedures.getString("REMARKS");
					if (remarks != null && !remarks.isBlank()) {
						proc.put("remarks", remarks);
					}
					short type = procedures.getShort("PROCEDURE_TYPE");
					switch (type) {
						case DatabaseMetaData.procedureNoResult -> proc.put("procedureType", "no_result");
						case DatabaseMetaData.procedureReturnsResult -> proc.put("procedureType", "returns_result");
						case DatabaseMetaData.procedureResultUnknown -> proc.put("procedureType", "result_unknown");
						default -> {}
					}
					if (!budget.consume(name)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private TableDetails buildTableDetails(DatabaseMetaData meta, Config.ConnectionConfig connectionConfig,
			String schema, String table, boolean includeIndexes, TokenBudget budget, Config.Policy policy) throws SQLException {
		ArrayNode columnsNode = mapper.createArrayNode();
		ArrayNode primaryKeys = mapper.createArrayNode();
		ArrayNode foreignKeys = mapper.createArrayNode();
		Set<String> pkColumns = new HashSet<>();
		Set<String> fkColumns = new HashSet<>();
		boolean truncated = false;
		try (ResultSet pk = meta.getPrimaryKeys(connectionConfig.catalog, schema, table)) {
			while (pk.next()) {
				String column = pk.getString("COLUMN_NAME");
				ObjectNode item = primaryKeys.addObject();
				item.put("column", column);
				item.put("name", pk.getString("PK_NAME"));
				pkColumns.add(column);
				if (!budget.consume(column)) {
					truncated = true;
					break;
				}
			}
		}
		try (ResultSet fk = meta.getImportedKeys(connectionConfig.catalog, schema, table)) {
			while (fk.next()) {
				String column = fk.getString("FKCOLUMN_NAME");
				ObjectNode item = foreignKeys.addObject();
				item.put("column", column);
				item.put("refSchema", fk.getString("PKTABLE_SCHEM"));
				item.put("refTable", fk.getString("PKTABLE_NAME"));
				item.put("refColumn", fk.getString("PKCOLUMN_NAME"));
				item.put("name", fk.getString("FK_NAME"));
				fkColumns.add(column);
				if (!budget.consume(column)) {
					truncated = true;
					break;
				}
			}
		}
		Map<String, IndexInfo> indexMap = new HashMap<>();
		if (includeIndexes) {
			try (ResultSet indexes = meta.getIndexInfo(connectionConfig.catalog, schema, table, false, false)) {
				while (indexes.next()) {
					String name = indexes.getString("INDEX_NAME");
					String column = indexes.getString("COLUMN_NAME");
					if (name == null || column == null) {
						continue;
					}
					boolean unique = !indexes.getBoolean("NON_UNIQUE");
					IndexInfo info = indexMap.get(name);
					if (info == null) {
						info = new IndexInfo(name, unique);
						indexMap.put(name, info);
					}
					info.columns.add(column);
					if (!budget.consume(name)) {
						truncated = true;
						break;
					}
				}
			}
		}
		try (ResultSet cols = meta.getColumns(connectionConfig.catalog, schema, table, null)) {
			while (cols.next()) {
				String column = cols.getString("COLUMN_NAME");
				if (!matchesPolicy(column, policy.allowColumns, policy.denyColumns)) {
					continue;
				}
				ObjectNode col = columnsNode.addObject();
				col.put("name", column);
				col.put("type", cols.getString("TYPE_NAME"));
				col.put("nullable", "YES".equalsIgnoreCase(cols.getString("IS_NULLABLE")));
				String defaultValue = cols.getString("COLUMN_DEF");
				if (defaultValue != null) {
					col.put("default", defaultValue);
				}
				if (pkColumns.contains(column)) {
					col.put("primaryKey", true);
				}
				if (fkColumns.contains(column)) {
					col.put("foreignKey", true);
				}
				if (isIndexed(column, indexMap)) {
					col.put("indexed", true);
				}
				if (!budget.consume(column)) {
					truncated = true;
					break;
				}
				if (budget.exhausted()) {
					truncated = true;
					break;
				}
			}
		}
		ArrayNode indexesNode = null;
		if (includeIndexes && !indexMap.isEmpty()) {
			indexesNode = mapper.createArrayNode();
			for (IndexInfo info : indexMap.values()) {
				ObjectNode index = indexesNode.addObject();
				index.put("name", info.name);
				index.put("unique", info.unique);
				ArrayNode colsNode = index.putArray("columns");
				for (String column : info.columns) {
					colsNode.add(column);
				}
			}
		}
		return new TableDetails(columnsNode, primaryKeys, foreignKeys, indexesNode, truncated);
	}

	private TokenBudget resolveBudget(IntrospectRequest request, Config.Policy policy) {
		int maxTokens = config.introspect.maxTokens;
		if (policy.introspectMaxTokens != null) {
			maxTokens = Math.min(maxTokens, policy.introspectMaxTokens);
		}
		return new TokenBudget(maxTokens);
	}

	private List<String> resolveSchemaPatterns(String schema, Config.ConnectionConfig connectionConfig, String vendor) {
		List<String> patterns = new ArrayList<>();
		if (schema != null && !schema.isBlank()) {
			patterns.add(schema.trim());
			return patterns;
		}
		patterns.add(null);
		return patterns;
	}

	private List<String> resolveSearchTerms(List<String> search) {
		List<String> terms = new ArrayList<>();
		if (search != null) {
			for (String term : search) {
				if (term != null && !term.trim().isEmpty()) {
					terms.add(term.trim());
				}
			}
		}
		return terms;
	}

	private boolean matchesPolicy(String value, List<String> allow, List<String> deny) {
		if (value == null) {
			return allow == null || allow.isEmpty();
		}
		if (allow != null && !allow.isEmpty()) {
			boolean matched = false;
			for (String pattern : allow) {
				if (patternMatch(value, pattern)) {
					matched = true;
					break;
				}
			}
			if (!matched) {
				return false;
			}
		}
		if (deny != null && !deny.isEmpty()) {
			for (String pattern : deny) {
				if (patternMatch(value, pattern)) {
					return false;
				}
			}
		}
		return true;
	}

	private int resolveRelationDepth(Config.Policy policy) {
		int depth = config.introspect.relationDepth;
		if (policy.relationDepth != null) {
			depth = Math.min(depth, policy.relationDepth);
		}
		return depth;
	}

	private boolean matchesSearch(String value, List<String> searchTerms) {
		if (searchTerms == null || searchTerms.isEmpty()) {
			return true;
		}
		if (value == null) {
			return false;
		}
		for (String term : searchTerms) {
			if (patternMatch(value, term)) {
				return true;
			}
		}
		return false;
	}

	private boolean isIndexed(String column, Map<String, IndexInfo> indexMap) {
		for (IndexInfo info : indexMap.values()) {
			if (info.columns.contains(column)) {
				return true;
			}
		}
		return false;
	}

	private boolean expandRelations(DatabaseMetaData meta, Config.ConnectionConfig connectionConfig,
			Map<String, ObjectNode> schemaMap, Set<String> tableSeen, int relationDepth,
			TokenBudget budget, Config.Policy policy, String vendor) throws SQLException {
		Set<String> frontier = new HashSet<>(tableSeen);
		for (int depth = 0; depth < relationDepth; depth++) {
			Set<String> next = new HashSet<>();
			for (String tableKey : frontier) {
				String[] parts = splitKey(tableKey);
				String schema = parts[0];
				String table = parts[1];
				try (ResultSet fk = meta.getImportedKeys(connectionConfig.catalog, schema.isEmpty() ? null : schema, table)) {
					while (fk.next()) {
						String refSchema = fk.getString("PKTABLE_SCHEM");
						String refTable = fk.getString("PKTABLE_NAME");
						if (!isAllowedSchema(refSchema, connectionConfig, policy, vendor)) {
							continue;
						}
						if (isSystemTable(refSchema, refTable, vendor)) {
							continue;
						}
						String refKey = (refSchema == null ? "" : refSchema) + "." + refTable;
						if (tableSeen.contains(refKey)) {
							continue;
						}
						ObjectNode schemaNode = schemaMap.computeIfAbsent(refSchema == null ? "" : refSchema, name -> {
							ObjectNode node = mapper.createObjectNode();
							node.put("name", name);
							node.putArray("objects");
							return node;
						});
						ArrayNode objects = (ArrayNode) schemaNode.get("objects");
						ObjectNode tableNode = objects.addObject();
						tableNode.put("name", refTable);
						tableNode.put("type", "table");
						tableNode.put("relationDepth", depth + 1);
						if (!budget.consume(refTable)) {
							return false;
						}
						TableDetails details = buildTableDetails(meta, connectionConfig, refSchema, refTable, false, budget, policy);
						tableNode.set("columns", details.columns);
						tableNode.set("primaryKeys", details.primaryKeys);
						tableNode.set("foreignKeys", details.foreignKeys);
						if (details.truncated) {
							return false;
						}
						tableSeen.add(refKey);
						next.add(refKey);
						if (budget.exhausted()) {
							return false;
						}
					}
				}
			}
			frontier = next;
			if (frontier.isEmpty()) {
				break;
			}
		}
		return true;
	}

	private String[] splitKey(String tableKey) {
		int index = tableKey.indexOf('.');
		if (index < 0) {
			return new String[] { "", tableKey };
		}
		return new String[] { tableKey.substring(0, index), tableKey.substring(index + 1) };
	}

	private boolean isAllowedSchema(String schema, Config.ConnectionConfig connectionConfig,
			Config.Policy policy, String vendor) {
		if (!matchesPolicy(schema, policy.allowSchemas, policy.denySchemas)) {
			return false;
		}
		if (connectionConfig.allowedSchemas != null && !connectionConfig.allowedSchemas.isEmpty()) {
			if (schema == null) {
				return false;
			}
			boolean matched = false;
			for (String allowed : connectionConfig.allowedSchemas) {
				if (patternMatch(schema, allowed)) {
					matched = true;
					break;
				}
			}
			if (!matched) {
				return false;
			}
		}
		return !isSystemSchema(schema, vendor);
	}

	private boolean isSystemSchema(String schema, String vendor) {
		if (schema == null) {
			return false;
		}
		String name = schema.toLowerCase(Locale.ROOT);
		String db = vendor == null ? "" : vendor.toLowerCase(Locale.ROOT);
		if (db.contains("postgres")) {
			return name.equals("pg_catalog") || name.equals("information_schema") || name.startsWith("pg_toast");
		}
		if (db.contains("mysql")) {
			return name.equals("information_schema") || name.equals("mysql") || name.equals("performance_schema")
				|| name.equals("sys");
		}
		if (db.contains("sql server") || db.contains("microsoft")) {
			return name.equals("information_schema") || name.equals("sys");
		}
		if (db.contains("oracle")) {
			return name.equals("sys") || name.equals("system") || name.equals("xdb") || name.equals("outln");
		}
		if (db.contains("h2")) {
			return name.equals("information_schema");
		}
		return name.startsWith("pg_");
	}

	private boolean isSystemTable(String schema, String table, String vendor) {
		if (table == null) {
			return false;
		}
		String name = table.toLowerCase(Locale.ROOT);
		if (isSystemSchema(schema, vendor)) {
			return true;
		}
		return name.startsWith("pg_") || name.startsWith("sql_") || name.startsWith("sys");
	}

	private boolean patternMatch(String value, String pattern) {
		if (pattern == null || pattern.isBlank()) {
			return true;
		}
		String normalized = pattern.toLowerCase(Locale.ROOT);
		String text = value.toLowerCase(Locale.ROOT);
		if (normalized.contains("*") || normalized.contains("%")) {
			String regex = normalized.replace(".", "\\.").replace("*", ".*").replace("%", ".*");
			return text.matches(regex);
		}
		return text.equals(normalized);
	}

	public static final class IntrospectRequest {
		public String schema;
		public List<String> search;
		public Integer limit;
		public Integer offset;
		public Boolean includeTables;
		public Boolean includeViews;
		public Boolean includeProcedures;
		public Boolean includeColumns;
		public Boolean includeIndexes;
	}

	public static IntrospectRequest parseRequest(JsonNode node) throws Config.ConfigException {
		IntrospectRequest request = new IntrospectRequest();
		if (node == null || node.isNull()) {
			return request;
		}
		if (!node.isObject()) {
			throw new Config.ConfigException("arguments must be an object");
		}
		request.schema = readOptionalString(node.get("schema"));
		request.search = readOptionalStringArray(node.get("search"));
		request.limit = readOptionalPositiveInt(node.get("limit"));
		request.offset = readOptionalNonNegativeInt(node.get("offset"));
		request.includeTables = readOptionalBoolean(node.get("include_tables"));
		request.includeViews = readOptionalBoolean(node.get("include_views"));
		request.includeProcedures = readOptionalBoolean(node.get("include_procedures"));
		request.includeColumns = readOptionalBoolean(node.get("include_columns"));
		request.includeIndexes = readOptionalBoolean(node.get("include_indexes"));
		return request;
	}

	private static String readOptionalString(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		String text = node.asText();
		if (text == null || text.trim().isEmpty()) {
			return null;
		}
		return text.trim();
	}

	private static Boolean readOptionalBoolean(JsonNode node) throws Config.ConfigException {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isBoolean()) {
			throw new Config.ConfigException("value must be a boolean");
		}
		return node.asBoolean();
	}

	private static Integer readOptionalPositiveInt(JsonNode node) throws Config.ConfigException {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isNumber()) {
			throw new Config.ConfigException("value must be a positive integer");
		}
		int value = node.asInt();
		if (value < 1) {
			throw new Config.ConfigException("value must be a positive integer");
		}
		return value;
	}

	private static Integer readOptionalNonNegativeInt(JsonNode node) throws Config.ConfigException {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isNumber()) {
			throw new Config.ConfigException("value must be a non-negative integer");
		}
		int value = node.asInt();
		if (value < 0) {
			throw new Config.ConfigException("value must be a non-negative integer");
		}
		return value;
	}

	private static List<String> readOptionalStringList(JsonNode node) throws Config.ConfigException {
		if (node == null || node.isNull()) {
			return new ArrayList<>();
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
				return new ArrayList<>();
			}
			List<String> result = new ArrayList<>();
			result.add(text);
			return result;
		}
		throw new Config.ConfigException("value must be a string or array of strings");
	}

	private static List<String> readOptionalStringArray(JsonNode node) throws Config.ConfigException {
		if (node == null || node.isNull()) {
			return new ArrayList<>();
		}
		if (!node.isArray()) {
			throw new Config.ConfigException("value must be an array of strings");
		}
		List<String> result = new ArrayList<>();
		for (JsonNode item : node) {
			String text = item.asText();
			if (text != null && !text.trim().isEmpty()) {
				result.add(text.trim());
			}
		}
		return result;
	}

	private static final class TokenBudget {
		final int maxTokens;
		int usedTokens;

		TokenBudget(int maxTokens) {
			this.maxTokens = Math.max(1, maxTokens);
			this.usedTokens = 0;
		}

		boolean consume(String value) {
			if (value == null) {
				return true;
			}
			int cost = Math.max(1, (value.length() + 3) / 4);
			if (usedTokens + cost > maxTokens) {
				return false;
			}
			usedTokens += cost;
			return true;
		}

		boolean exhausted() {
			return usedTokens >= maxTokens;
		}
	}

	private static final class TableDetails {
		final ArrayNode columns;
		final ArrayNode primaryKeys;
		final ArrayNode foreignKeys;
		final ArrayNode indexes;
		final boolean truncated;

		TableDetails(ArrayNode columns, ArrayNode primaryKeys, ArrayNode foreignKeys, ArrayNode indexes, boolean truncated) {
			this.columns = columns;
			this.primaryKeys = primaryKeys;
			this.foreignKeys = foreignKeys;
			this.indexes = indexes;
			this.truncated = truncated;
		}
	}

	private static final class IndexInfo {
		final String name;
		final boolean unique;
		final List<String> columns = new ArrayList<>();

		IndexInfo(String name, boolean unique) {
			this.name = name;
			this.unique = unique;
		}
	}

	private static final class ResultCursor {
		final int limit;
		final int offset;
		int skipped;
		int count;

		ResultCursor(int limit, int offset) {
			this.limit = limit;
			this.offset = offset;
			this.skipped = 0;
			this.count = 0;
		}

		State next() {
			if (count >= limit) {
				return State.FULL;
			}
			if (skipped < offset) {
				skipped++;
				return State.SKIP;
			}
			count++;
			return State.INCLUDE;
		}

		enum State { INCLUDE, SKIP, FULL }
	}
}
