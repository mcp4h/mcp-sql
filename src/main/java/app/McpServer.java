package app;

import app.Protocol.Request;
import app.Protocol.Response;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;

public final class McpServer {
	private final ObjectMapper mapper = new ObjectMapper();
	private Config config = Config.defaultConfig();
	private final SqlService sqlService = new SqlService(mapper, config);
	private final PreviewCache previewCache = new PreviewCache(100);

	public void run() throws Exception {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String trimmed = line.trim();
				if (trimmed.isEmpty()) {
					continue;
				}
				JsonNode input;
				try {
					input = mapper.readTree(trimmed);
				} catch (JsonProcessingException e) {
					Response error = Response.err(mapper.nullNode(), -32700, e.getMessage());
					writeResponse(writer, error);
					continue;
				}
				if (input.isArray()) {
					ArrayNode responses = mapper.createArrayNode();
					for (JsonNode node : input) {
						responses.add(mapper.valueToTree(processNode(node)));
					}
					writeRaw(writer, responses);
					continue;
				}
				Response response = processNode(input);
				writeResponse(writer, response);
			}
		}
	}

	private Response processNode(JsonNode node) {
		Request request;
		try {
			request = mapper.treeToValue(node, Request.class);
		} catch (JsonProcessingException e) {
			return Response.err(mapper.nullNode(), -32600, e.getMessage());
		}
		if (request.method == null || request.method.isBlank()) {
			return Response.err(request.id, -32600, "method is required");
		}
		try {
			if ("tools/call".equals(request.method)) {
				ToolCallResult result = handleToolCall(request);
				return Response.ok(request.id, result.result);
			}
			JsonNode result = handle(request);
			return Response.ok(request.id, result);
		} catch (PreviewNotSupportedException e) {
			return Response.err(request.id, -32050, "preview is not supported for that query");
		} catch (PreviewScopesRequiredException e) {
			Response resp = Response.err(request.id, -32601, "missing required scopes");
			ObjectNode meta = mapper.createObjectNode();
			meta.set("requested_scopes", mapper.valueToTree(e.requestedScopes));
			meta.put("preview", true);
			if (e.reviewUri != null) {
				meta.put("reviewUri", e.reviewUri);
			}
			resp.error._meta = meta;
			return resp;
		} catch (ScopeRequiredException e) {
			Response resp = Response.err(request.id, -32601, "missing required scopes");
			ObjectNode meta = mapper.createObjectNode();
			meta.set("requested_scopes", mapper.valueToTree(e.requestedScopes));
			resp.error._meta = meta;
			return resp;
		} catch (Config.ConfigException e) {
			return Response.err(request.id, -32602, e.getMessage());
		} catch (IllegalArgumentException e) {
			return Response.err(request.id, -32601, e.getMessage());
		} catch (Exception e) {
			return Response.err(request.id, -32000, e.getMessage(), errorData(e));
		} catch (Throwable t) {
			String message = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
			return Response.err(request.id, -32000, message, errorData(t));
		}
	}

	private ObjectNode errorData(Throwable t) {
		ObjectNode data = mapper.createObjectNode();
		data.put("type", t.getClass().getName());
		if (t.getMessage() != null) {
			data.put("message", t.getMessage());
		}
		StringWriter buffer = new StringWriter();
		t.printStackTrace(new PrintWriter(buffer));
		data.put("stack", buffer.toString());
		Throwable cause = t.getCause();
		if (cause != null && cause != t) {
			ObjectNode causeNode = mapper.createObjectNode();
			causeNode.put("type", cause.getClass().getName());
			if (cause.getMessage() != null) {
				causeNode.put("message", cause.getMessage());
			}
			data.set("cause", causeNode);
		}
		return data;
	}

	private JsonNode handle(Request request) throws Exception {
		switch (request.method) {
			case "initialize" -> {
				applyInitializeConfig(request);
				return initializeResponse();
			}
		case "tools/list" -> {
			ObjectNode result = mapper.createObjectNode();
			result.set("tools", toolDefinitions());
			return result;
		}
		case "resources/list" -> {
			return resourcesList();
		}
		case "resources/read" -> {
			return resourcesRead(request);
		}
			case "tools/call" -> throw new IllegalArgumentException("method not handled");
			default -> throw new IllegalArgumentException("method not found");
		}
	}

	private void applyInitializeConfig(Request request) throws Config.ConfigException {
		if (request.params == null || request.params.isNull()) {
			return;
		}
		JsonNode configNode = request.params
			.path("capabilities")
			.path("experimental")
			.path("configuration");
		if (configNode.isMissingNode() || configNode.isNull()) {
			return;
		}
		config = config.applyOverride(configNode);
		sqlService.updateConfig(config);
	}

	private JsonNode initializeResponse() {
		ObjectNode root = mapper.createObjectNode();
		ObjectNode serverInfo = root.putObject("serverInfo");
		serverInfo.put("name", "mcp-sql");
		serverInfo.put("version", "0.1.0");
		root.set("configSchema", ConfigSchema.build(mapper));
		ObjectNode capabilities = root.putObject("capabilities");
		capabilities.putObject("resources").put("read", true).put("list", true);
		capabilities.putObject("tools").put("list", true).put("call", true);
		ObjectNode experimental = capabilities.putObject("experimental");
		experimental.put("policy", true);
		root.putObject("_meta").put("server", "mcp-sql").put("vendor", "celerex");
		return root;
	}

	private ArrayNode toolDefinitions() {
		ArrayNode tools = mapper.createArrayNode();
		tools.add(sqlQueryDefinition());
		tools.add(sqlInspectSchemaDefinition());
		return tools;
	}

	private ObjectNode sqlQueryDefinition() {
		ObjectNode tool = mapper.createObjectNode();
		tool.put("name", "sql_query");
		tool.put("description", "Run a SQL statement (SELECT, DML, or DDL)." );
		ObjectNode annotations = tool.putObject("annotations");
		annotations.put("group", "database");
		annotations.put("preview", true);
		annotations.put("intentTemplate", "Run sql statement");
		annotations.put("inputTemplate", "```sql\n{sql}\n```\n[Params: {params}]");
		ArrayNode dynamicScopes = annotations.putArray("dynamic_scopes");
		dynamicScopes.add("read:database:*");
		dynamicScopes.add("write:database:*");
		dynamicScopes.add("execute:database:*");
		ObjectNode schema = tool.putObject("inputSchema");
		schema.put("type", "object");
		schema.put("additionalProperties", false);
		ObjectNode props = schema.putObject("properties");
		props.putObject("sql").put("type", "string").put("description", "SQL statement (SELECT/DML/DDL/CALL/EXEC)." );
		props.set("params", arrayAnySchema("Positional parameters matching '?' placeholders, left-to-right. Example: sql '... where id = ? and active = ?' with params [42, true]."));
		props.putObject("max_rows").put("type", "integer").put("minimum", 1).put("description", "Maximum rows to return for SELECT.");
		ArrayNode required = schema.putArray("required");
		required.add("sql");
		ObjectNode outputSchema = tool.putObject("outputSchema");
		outputSchema.put("type", "object");
		ObjectNode outputProps = outputSchema.putObject("properties");
		outputProps.set("structuredContent", buildSqlQueryStructuredSchema());
		outputProps.putObject("content").put("type", "array");
		return tool;
	}

	private ObjectNode sqlInspectSchemaDefinition() {
		ObjectNode tool = mapper.createObjectNode();
		tool.put("name", "sql_inspect_schema");
		tool.put("description", "Inspect database metadata (schemas, tables, columns, relationships)." );
		tool.put("intentTemplate", "Inspect database [schema {schema}] [search {search}] [limit {limit}] [offset {offset}]");
		ObjectNode annotations = tool.putObject("annotations");
		annotations.put("group", "database");
		ArrayNode dynamicScopes = annotations.putArray("dynamic_scopes");
		dynamicScopes.add("read:database:*");
		ObjectNode schema = tool.putObject("inputSchema");
		schema.put("type", "object");
		schema.put("additionalProperties", false);
		ObjectNode props = schema.putObject("properties");
		props.putObject("schema").put("type", "string").put("description", "Schema filter (single schema)." );
		props.set("search", arrayStringSchema("Name search terms (wildcards supported). Matches any term across tables, views, or procedures."));
		props.putObject("limit").put("type", "integer").put("minimum", 1).put("description", "Maximum objects to return.");
		props.putObject("offset").put("type", "integer").put("minimum", 0).put("description", "Object offset for pagination.");
		props.putObject("include_tables").put("type", "boolean").put("description", "Include tables in the results.");
		props.putObject("include_views").put("type", "boolean").put("description", "Include views in the results.");
		props.putObject("include_procedures").put("type", "boolean").put("description", "Include stored procedures in the results.");
		props.putObject("include_columns").put("type", "boolean").put("description", "Include columns and key relationships for matched tables/views.");
		props.putObject("include_indexes").put("type", "boolean").put("description", "Include index details for matched tables/views.");
		ObjectNode outputSchema = tool.putObject("outputSchema");
		outputSchema.put("type", "object");
		ObjectNode outputProps = outputSchema.putObject("properties");
		outputProps.set("structuredContent", buildInspectStructuredSchema());
		outputProps.putObject("content").put("type", "array");
		return tool;
	}

	private ToolCallResult handleToolCall(Request request) throws Exception {
		JsonNode params = request.params == null ? mapper.createObjectNode() : request.params;
		JsonNode nameNode = params.get("name");
		if (nameNode == null || nameNode.isNull()) {
			throw new Config.ConfigException("name is required");
		}
		String name = nameNode.asText();
		JsonNode arguments = params.get("arguments");
		JsonNode meta = params.get("_meta");
		JsonNode metaNode = meta == null ? mapper.createObjectNode() : meta;
		Config.Policy policy = Config.parsePolicy(meta == null ? null : meta.get("policy"));
		if ("sql_query".equals(name)) {
			String sql = readRequiredString(arguments, "sql");
			Integer maxRows = readOptionalPositiveInt(arguments, "max_rows");
			Integer offset = readOptionalNonNegativeInt(arguments, "offset");
			boolean preview = metaNode.path("preview").asBoolean(false);
			SqlService.SqlType sqlType = sqlService.classifySql(sql);
			if (preview && sqlType != SqlService.SqlType.DML) {
				throw new PreviewNotSupportedException();
			}
			String requiredScope = scopeForSqlType(sqlType);
			ScopeCheck scopeCheck = checkScopes(requiredScope, metaNode);
			if (!scopeCheck.allowed && !scopeCheck.dynamicScopes && preview) {
				long started = System.nanoTime();
				ObjectNode structured = executeByType(sqlType, sql, arguments, null, maxRows, offset, policy, true);
				long runtimeMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
				String actionLabel = actionLabel(sqlType, sql);
				String previewUri = null;
				String html = buildPreviewHtml(structured, actionLabel, runtimeMs, previewDisplayRows());
				previewUri = previewCache.store(html);
				throw new PreviewScopesRequiredException(scopeCheck.requestedScopes, previewUri);
			}
			if (!scopeCheck.allowed && !scopeCheck.dynamicScopes) {
				throw new ScopeRequiredException(scopeCheck.requestedScopes);
			}
			if (sqlType == SqlService.SqlType.PROC && policy.allowExecute != null && !policy.allowExecute) {
				throw new Config.ConfigException("Execute statements are disabled by policy");
			}
			long started = System.nanoTime();
			ObjectNode structured = executeByType(sqlType, sql, arguments, null, maxRows, offset, policy, preview);
			long runtimeMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
			String actionLabel = actionLabel(sqlType, sql);
			String previewUri = null;
			boolean isDml = sqlType == SqlService.SqlType.DML;
			boolean includeSelectReview = sqlType == SqlService.SqlType.SELECT && config.query.includeSelectReview;
			if (preview || isDml || includeSelectReview) {
				String html = buildPreviewHtml(structured, actionLabel, runtimeMs, previewDisplayRows());
				previewUri = previewCache.store(html);
			}
			ObjectNode metaPayload = buildPreviewMeta(preview, scopeCheck.requestedScopes, previewUri);
			return new ToolCallResult(toolSuccess(name, structured, actionLabel, metaPayload), null);
		}
		if ("sql_inspect_schema".equals(name)) {
			MetadataService.IntrospectRequest introspectRequest = MetadataService.parseRequest(arguments);
			HikariDataSource dataSource = sqlService.dataSourceFor(null, policy);
			Config.ConnectionConfig connConfig = findConnectionConfig(null, policy);
			try (Connection conn = dataSource.getConnection()) {
				MetadataService metadataService = new MetadataService(mapper, config);
				ObjectNode structured = metadataService.introspect(conn, connConfig, introspectRequest, policy);
				return new ToolCallResult(toolSuccess(name, structured, null, null), null);
			}
		}
		return new ToolCallResult(toolError(name, "unknown tool"), null);
	}

	private ObjectNode executeByType(SqlService.SqlType sqlType, String sql, JsonNode arguments,
			String connectionName, Integer maxRows, Integer offset, Config.Policy policy, boolean preview) throws Exception {
		JsonNode params = arguments == null ? null : arguments.get("params");
		switch (sqlType) {
			case SELECT -> {
				return sqlService.executeSelect(sql, params, connectionName, maxRows, offset, policy);
			}
			case DML -> {
				if (preview) {
					return sqlService.previewUpdate(sql, params, connectionName, maxRows, policy);
				}
				return sqlService.executeUpdate(sql, params, connectionName, null, policy);
			}
			case DDL -> {
				return sqlService.executeDdl(sql, params, connectionName, null, policy);
			}
			case PROC -> {
				return sqlService.executeProcedure(sql, params, connectionName, policy);
			}
			default -> throw new Config.ConfigException("sql type is not supported for this tool");
		}
	}

	private String scopeForSqlType(SqlService.SqlType type) {
		return switch (type) {
			case SELECT -> "read:database:dml";
			case DML -> "write:database:dml";
			case DDL -> "write:database:ddl";
			case PROC -> "execute:database:procedure";
			default -> "write:database:ddl";
		};
	}

	private ScopeCheck checkScopes(String requiredScope, JsonNode meta) {
		List<String> allowed = readScopeList(meta.get("allowed_scopes"));
		List<String> denied = readScopeList(meta.get("denied_scopes"));
		boolean dynamic = meta.path("dynamic_scopes").asBoolean(false);
		boolean allowedMatch = scopeAllowed(requiredScope, allowed, denied);
		List<String> requested = new ArrayList<>();
		if (!allowedMatch && !denied.isEmpty()) {
			if (!scopeDenied(requiredScope, denied)) {
				requested.add(requiredScope);
			}
		} else if (!allowedMatch) {
			requested.add(requiredScope);
		}
		return new ScopeCheck(allowedMatch, dynamic, requested);
	}

	private boolean scopeAllowed(String scope, List<String> allowed, List<String> denied) {
		if (scopeDenied(scope, denied)) {
			return false;
		}
		if (allowed.isEmpty()) {
			return false;
		}
		for (String entry : allowed) {
			if (scopeMatches(scope, entry)) {
				return true;
			}
		}
		return false;
	}

	private boolean scopeDenied(String scope, List<String> denied) {
		for (String entry : denied) {
			if (scopeMatches(scope, entry)) {
				return true;
			}
		}
		return false;
	}

	private boolean scopeMatches(String scope, String entry) {
		if (entry == null || entry.isBlank()) {
			return false;
		}
		String normalized = entry.trim();
		if (normalized.endsWith(":*")) {
			String prefix = normalized.substring(0, normalized.length() - 2);
			return scope.equals(prefix) || scope.startsWith(prefix + ":");
		}
		return scope.equals(normalized) || scope.startsWith(normalized + ":");
	}

	private List<String> readScopeList(JsonNode node) {
		List<String> list = new ArrayList<>();
		if (node == null || node.isNull()) {
			return list;
		}
		if (node.isTextual()) {
			String text = node.asText().trim();
			if (!text.isEmpty()) {
				list.add(text);
			}
			return list;
		}
		if (node.isArray()) {
			for (JsonNode item : node) {
				if (item.isTextual()) {
					String text = item.asText().trim();
					if (!text.isEmpty()) {
						list.add(text);
					}
				}
			}
		}
		return list;
	}

	private Config.ConnectionConfig findConnectionConfig(String connectionName, Config.Policy policy) throws Config.ConfigException {
		String resolved = connectionName;
		if (resolved == null || resolved.isBlank()) {
			resolved = policy.defaultConnection != null && !policy.defaultConnection.isBlank()
				? policy.defaultConnection
				: config.defaultConnection;
		}
		for (Config.ConnectionConfig connection : config.connections) {
			if (connection.name.equals(resolved)) {
				return connection;
			}
		}
		throw new Config.ConfigException("connection not found: " + resolved);
	}

	private int previewDisplayRows() {
		if (config.query.previewDisplayRows != null && config.query.previewDisplayRows > 0) {
			return config.query.previewDisplayRows;
		}
		return 10;
	}

	private ObjectNode toolSuccess(String name, ObjectNode structured, String actionLabel, ObjectNode meta) {
		ObjectNode payload = mapper.createObjectNode();
		ArrayNode content = payload.putArray("content");
		ObjectNode text = content.addObject();
		text.put("type", "text");
		if (structured.has("rowCount")) {
			String label = actionLabel == null ? "Affected" : actionLabel;
			text.put("text", label + " " + structured.path("rowCount").asInt() + " row(s)");
		} else if (structured.has("objectCount")) {
			text.put("text", "Found " + structured.path("objectCount").asInt() + " object(s)");
		} else {
			text.put("text", name + " completed");
		}
		payload.set("structuredContent", structured);
		if (meta != null && meta.size() > 0) {
			payload.set("_meta", meta);
		}
		return payload;
	}

	private ObjectNode buildPreviewMeta(boolean preview, List<String> requestedScopes, String previewUri) {
		ObjectNode meta = mapper.createObjectNode();
		if (preview) {
			meta.put("preview", true);
		}
		if (previewUri != null) {
			meta.put("reviewUri", previewUri);
		}
		if (requestedScopes != null && !requestedScopes.isEmpty()) {
			meta.set("requested_scopes", mapper.valueToTree(requestedScopes));
		}
		return meta.size() == 0 ? null : meta;
	}

	private String buildPreviewHtml(ObjectNode structured, String actionLabel, long runtimeMs, int displayRows) {
		StringBuilder html = new StringBuilder();
		html.append("<!doctype html><html><head><meta charset=\"utf-8\"/>");
		html.append("<style>");
		html.append("body{font-family:var(--mcp-font-sans, 'IBM Plex Sans', 'Source Sans 3', sans-serif);background:var(--mcp-color-bg,#f7f7f5);color:var(--mcp-color-fg,#1f1f1b);margin:0;padding:24px;}" );
		html.append(".card{background:var(--mcp-surface,#fff);border:1px solid var(--mcp-color-border,#d1d0ca);border-radius:12px;padding:18px;box-shadow:0 12px 24px rgba(0,0,0,0.08);}" );
		html.append(".headline{font-family:var(--mcp-font-serif, 'IBM Plex Serif', 'Source Serif 4', serif);font-size:18px;margin:0 0 6px;}" );
		html.append(".metric{font-weight:600;color:var(--mcp-color-fg,#1f1f1b);}" );
		html.append(".meta{font-size:12px;color:var(--mcp-color-muted-fg,#4b4a44);margin-bottom:12px;}" );
		html.append(".filter{display:flex;align-items:center;gap:8px;margin:4px 0 10px;}" );
		html.append(".filter input{flex:1;padding:6px 8px;border-radius:6px;border:1px solid var(--mcp-color-border,#d1d0ca);background:var(--mcp-color-bg,#f7f7f5);font-size:12px;}" );
		html.append("table{width:100%;border-collapse:collapse;font-size:13px;}" );
		html.append("th,td{border-bottom:1px solid var(--mcp-color-border,#d1d0ca);padding:6px 8px;text-align:left;vertical-align:top;}" );
		html.append("th{background:var(--mcp-color-bg,#f7f7f5);font-weight:600;}" );
		html.append(".warn{margin-top:10px;color:var(--mcp-color-warning,#b45309);font-size:12px;}" );
		html.append("tr.ctx-row{display:none;}" );
		html.append("tr.ctx-row.ctx-open{display:table-row;}" );
		html.append("tr.ctx-toggle td{padding:4px 6px;background:color-mix(in srgb, var(--mcp-color-bg,#f7f7f5) 92%, var(--mcp-color-border,#d1d0ca) 8%);border-bottom:1px dashed var(--mcp-color-border,#d1d0ca);}" );
		html.append(".ctx-cell{text-align:center;}" );
		html.append(".ctx-btn{font:inherit;font-size:12px;color:var(--mcp-color-primary,#3b5ccc);background:var(--mcp-color-bg,#f7f7f5);border:1px solid var(--mcp-color-border,#d1d0ca);border-radius:var(--mcp-radius-s,.35rem);padding:2px 8px;cursor:pointer;}" );
		html.append("</style></head><body>");
		html.append("<div class=\"card\">");
		int rowCount = structured.path("rowCount").asInt();
		String headline = (actionLabel == null ? "Affected" : actionLabel) + " Rows: " + rowCount;
		html.append("<div class=\"headline\"><span class=\"metric\">")
			.append(escapeHtml(headline))
			.append("</span></div>");
		StringBuilder meta = new StringBuilder();
		meta.append("Runtime: ").append(runtimeMs).append(" ms");
		if (structured.has("offset")) {
			meta.append(" | Offset: ").append(structured.path("offset").asInt());
		}
		html.append("<div class=\"meta\">").append(escapeHtml(meta.toString())).append("</div>");
		if (structured.has("columns")) {
			ArrayNode columns = (ArrayNode) structured.get("columns");
			ArrayNode rows = (ArrayNode) structured.get("rows");
			html.append("<div class=\"filter\"><input type=\"text\" placeholder=\"Filter rows\" data-filter /></div>");
			html.append("<table data-table><thead><tr>");
			for (JsonNode col : columns) {
				html.append("<th>").append(escapeHtml(col.path("name").asText())).append("</th>");
			}
			html.append("</tr></thead><tbody>");
			if (rows != null) {
				int show = Math.max(1, displayRows);
				int total = rows.size();
				int cutoff = Math.min(show, total);
				for (int i = 0; i < cutoff; i++) {
					JsonNode row = rows.get(i);
					html.append("<tr class=\"data-row\">");
					for (JsonNode cell : row) {
						html.append("<td>").append(escapeHtml(cell.isNull() ? "null" : cell.asText())).append("</td>");
					}
					html.append("</tr>");
				}
				if (total > cutoff) {
					int remaining = total - cutoff;
					html.append("<tr class=\"ctx-toggle\" data-group=\"preview-extra\"><td class=\"ctx-cell\" colspan=\"")
						.append(columns.size())
						.append("\"><button class=\"ctx-btn\" data-group=\"preview-extra\" data-collapsed=\"")
						.append(escapeHtml("Show remaining " + remaining + " rows"))
						.append("\" data-expanded=\"")
						.append(escapeHtml("Hide remaining " + remaining + " rows"))
						.append("\" aria-expanded=\"false\">")
						.append(escapeHtml("Show remaining " + remaining + " rows"))
						.append("</button></td></tr>");
					for (int i = cutoff; i < total; i++) {
						JsonNode row = rows.get(i);
						html.append("<tr class=\"ctx-row data-row\" data-group=\"preview-extra\">");
						for (JsonNode cell : row) {
							html.append("<td>").append(escapeHtml(cell.isNull() ? "null" : cell.asText())).append("</td>");
						}
						html.append("</tr>");
					}
				}
				if (structured.path("truncated").asBoolean(false)) {
					int limit = structured.has("previewLimit")
						? structured.path("previewLimit").asInt()
						: structured.path("rowLimit").asInt(0);
					String message = "Showing first " + limit + " rows";
					html.append("<tr><td colspan=\"")
						.append(columns.size())
						.append("\" class=\"warn\">")
						.append(escapeHtml(message))
						.append("</td></tr>");
				}
			}
			html.append("</tbody></table>");
		}
		html.append("</div>");
		html.append("<script>");
		html.append("(function(){function update(btn,open){btn.textContent=open?btn.getAttribute('data-expanded'):btn.getAttribute('data-collapsed');btn.setAttribute('aria-expanded',open?'true':'false');}function bindToggles(){document.querySelectorAll('button.ctx-btn').forEach(function(btn){update(btn,false);btn.addEventListener('click',function(){var group=btn.getAttribute('data-group');var rows=document.querySelectorAll('tr.ctx-row[data-group=\"'+group+'\"]');var open=btn.getAttribute('aria-expanded')!=='true';rows.forEach(function(row){row.classList.toggle('ctx-open',open);});update(btn,open);});});}function bindFilter(){var input=document.querySelector('[data-filter]');var table=document.querySelector('[data-table]');if(!input||!table){return;}input.addEventListener('input',function(){var query=input.value.toLowerCase();var dataRows=table.querySelectorAll('tr.data-row');var toggleRow=table.querySelector('tr.ctx-toggle');if(query){dataRows.forEach(function(row){row.classList.add('ctx-open');var text=row.textContent.toLowerCase();row.style.display=text.indexOf(query)>=0?'':'none';});if(toggleRow){toggleRow.style.display='none';}}else{dataRows.forEach(function(row){row.style.display='';if(row.classList.contains('ctx-row')){row.classList.remove('ctx-open');}});if(toggleRow){toggleRow.style.display='';}document.querySelectorAll('button.ctx-btn').forEach(function(btn){update(btn,false);});}});}function sortRows(th,idx){var table=th.closest('table');var tbody=table.querySelector('tbody');var toggleRow=tbody.querySelector('tr.ctx-toggle');var hiddenRows=Array.from(tbody.querySelectorAll('tr.ctx-row'));var rows=Array.from(tbody.querySelectorAll('tr.data-row:not(.ctx-row)'));var dir=th.getAttribute('data-dir')==='asc'?'desc':'asc';table.querySelectorAll('th').forEach(function(h){h.removeAttribute('data-dir');});th.setAttribute('data-dir',dir);rows.sort(function(a,b){var av=a.children[idx]?.textContent||'';var bv=b.children[idx]?.textContent||'';return dir==='asc'?av.localeCompare(bv):bv.localeCompare(av);});rows.forEach(function(r){tbody.appendChild(r);});if(toggleRow){tbody.appendChild(toggleRow);}hiddenRows.forEach(function(r){tbody.appendChild(r);});}function bindSort(){document.querySelectorAll('[data-table] thead th').forEach(function(th,idx){th.style.cursor='pointer';th.addEventListener('click',function(){sortRows(th,idx);});});}bindToggles();bindFilter();bindSort();})();");
		html.append("</script>");
		html.append("</body></html>");
		return html.toString();
	}

	private String escapeHtml(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;");
	}

	private ObjectNode resourcesList() {
		ObjectNode result = mapper.createObjectNode();
		ArrayNode resources = result.putArray("resources");
		for (PreviewCache.Entry entry : previewCache.list()) {
			ObjectNode item = resources.addObject();
			item.put("uri", entry.uri);
			item.put("mimeType", "text/html");
			item.put("title", "SQL Preview");
		}
		return result;
	}

	private ObjectNode resourcesRead(Request request) throws Config.ConfigException {
		JsonNode params = request.params == null ? mapper.createObjectNode() : request.params;
		String uri = params.path("uri").asText();
		if (uri == null || uri.isBlank()) {
			throw new Config.ConfigException("uri is required");
		}
		PreviewCache.Entry entry = previewCache.get(uri);
		if (entry == null) {
			throw new Config.ConfigException("resource not found");
		}
		ObjectNode result = mapper.createObjectNode();
		ArrayNode contents = result.putArray("contents");
		ObjectNode content = contents.addObject();
		content.put("uri", entry.uri);
		content.put("mimeType", "text/html");
		content.put("text", entry.html);
		return result;
	}

	private static final class PreviewCache {
		private final int capacity;
		private final LinkedHashMap<String, Entry> entries;

		PreviewCache(int capacity) {
			this.capacity = capacity;
			this.entries = new LinkedHashMap<>(16, 0.75f, true);
		}

		synchronized String store(String html) {
			String id = java.util.UUID.randomUUID().toString();
			String uri = "ui://sql_query/" + id;
			entries.put(uri, new Entry(uri, html));
			trim();
			return uri;
		}

		synchronized Entry get(String uri) {
			return entries.get(uri);
		}

		synchronized List<Entry> list() {
			return new ArrayList<>(entries.values());
		}

		private void trim() {
			if (capacity <= 0) {
				return;
			}
			while (entries.size() > capacity) {
				String first = entries.keySet().iterator().next();
				entries.remove(first);
			}
		}

		static final class Entry {
			final String uri;
			final String html;

			Entry(String uri, String html) {
				this.uri = uri;
				this.html = html;
			}
		}
	}

	private String actionLabel(SqlService.SqlType sqlType, String sql) {
		return switch (sqlType) {
			case SELECT -> "Selected";
			case DML -> dmlLabel(sql);
			case DDL -> "Executed";
			case PROC -> "Executed procedure";
			default -> "Affected";
		};
	}

	private String dmlLabel(String sql) {
		if (sql == null) {
			return "Affected";
		}
		String normalized = sql.trim().toLowerCase();
		int index = normalized.indexOf(' ');
		String keyword = index < 0 ? normalized : normalized.substring(0, index);
		return switch (keyword) {
			case "insert" -> "Inserted";
			case "update" -> "Updated";
			case "delete" -> "Deleted";
			case "merge" -> "Merged";
			default -> "Affected";
		};
	}

	private ObjectNode toolError(String name, String message) {
		ObjectNode payload = mapper.createObjectNode();
		payload.put("tool", name);
		payload.put("error", message);
		return payload;
	}

	private static final class ScopeCheck {
		final boolean allowed;
		final boolean dynamicScopes;
		final List<String> requestedScopes;

		ScopeCheck(boolean allowed, boolean dynamicScopes, List<String> requestedScopes) {
			this.allowed = allowed;
			this.dynamicScopes = dynamicScopes;
			this.requestedScopes = requestedScopes;
		}
	}

	private static final class ToolCallResult {
		final ObjectNode result;
		final JsonNode meta;

		ToolCallResult(ObjectNode result, JsonNode meta) {
			this.result = result;
			this.meta = meta;
		}
	}

	private static final class ScopeRequiredException extends Exception {
		final List<String> requestedScopes;

		ScopeRequiredException(List<String> requestedScopes) {
			this.requestedScopes = requestedScopes;
		}
	}

	private static final class PreviewNotSupportedException extends Exception {
		PreviewNotSupportedException() {}
	}

	private static final class PreviewScopesRequiredException extends Exception {
		final List<String> requestedScopes;
		final String reviewUri;

		PreviewScopesRequiredException(List<String> requestedScopes, String reviewUri) {
			this.requestedScopes = requestedScopes;
			this.reviewUri = reviewUri;
		}
	}

	private ObjectNode stringListSchema(String description) {
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

	private ObjectNode arrayAnySchema(String description) {
		ObjectNode schema = mapper.createObjectNode();
		schema.put("type", "array");
		schema.put("description", description);
		return schema;
	}

	private ObjectNode arrayStringSchema(String description) {
		ObjectNode schema = mapper.createObjectNode();
		schema.put("type", "array");
		schema.putObject("items").put("type", "string");
		schema.put("description", description);
		return schema;
	}

	private ObjectNode buildSqlQueryStructuredSchema() {
		ObjectNode schema = mapper.createObjectNode();
		schema.put("type", "object");
		ArrayNode anyOf = schema.putArray("anyOf");
		anyOf.add(buildSelectStructuredSchema());
		anyOf.add(buildDmlStructuredSchema());
		anyOf.add(buildDdlStructuredSchema());
		return schema;
	}

	private ObjectNode buildSelectStructuredSchema() {
		ObjectNode schema = mapper.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		ObjectNode columns = props.putObject("columns");
		columns.put("type", "array");
		ObjectNode columnItem = columns.putObject("items");
		columnItem.put("type", "object");
		ObjectNode columnProps = columnItem.putObject("properties");
		columnProps.putObject("name").put("type", "string");
		columnProps.putObject("type").put("type", "string");
		columnProps.putObject("nullable").put("type", "boolean");
		ArrayNode columnRequired = columnItem.putArray("required");
		columnRequired.add("name");
		columnRequired.add("type");
		ObjectNode rows = props.putObject("rows");
		rows.put("type", "array");
		ObjectNode rowItems = rows.putObject("items");
		rowItems.put("type", "array");
		rowItems.putObject("items");
		props.putObject("rowCount").put("type", "integer");
		props.putObject("offset").put("type", "integer");
		props.putObject("truncated").put("type", "boolean");
		ArrayNode required = schema.putArray("required");
		required.add("columns");
		required.add("rows");
		required.add("rowCount");
		return schema;
	}

	private ObjectNode buildDmlStructuredSchema() {
		ObjectNode schema = mapper.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("rowCount").put("type", "integer");
		ArrayNode required = schema.putArray("required");
		required.add("rowCount");
		return schema;
	}

	private ObjectNode buildDdlStructuredSchema() {
		ObjectNode schema = mapper.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("success").put("type", "boolean");
		props.putObject("rowCount").put("type", "integer");
		props.putObject("hasResultSet").put("type", "boolean");
		return schema;
	}

	private ObjectNode buildInspectStructuredSchema() {
		ObjectNode schema = mapper.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("vendor").put("type", "string");
		props.putObject("connection").put("type", "string");
		props.putObject("objectCount").put("type", "integer");
		props.putObject("truncated").put("type", "boolean");
		props.putObject("offset").put("type", "integer");
		ObjectNode schemas = props.putObject("schemas");
		schemas.put("type", "array");
		ObjectNode schemaItem = schemas.putObject("items");
		schemaItem.put("type", "object");
		ObjectNode schemaItemProps = schemaItem.putObject("properties");
		schemaItemProps.putObject("name").put("type", "string");
		ObjectNode objects = schemaItemProps.putObject("objects");
		objects.put("type", "array");
		ObjectNode objectItem = objects.putObject("items");
		objectItem.put("type", "object");
		ObjectNode objectProps = objectItem.putObject("properties");
		objectProps.putObject("name").put("type", "string");
		objectProps.putObject("type").put("type", "string");
		objectProps.putObject("columns").put("type", "array");
		objectProps.putObject("primaryKeys").put("type", "array");
		objectProps.putObject("foreignKeys").put("type", "array");
		objectProps.putObject("indexes").put("type", "array");
		objectProps.putObject("remarks").put("type", "string");
		objectProps.putObject("procedureType").put("type", "string");
		return schema;
	}

	private String readRequiredString(JsonNode node, String name) throws Config.ConfigException {
		if (node == null || node.isNull()) {
			throw new Config.ConfigException(name + " is required");
		}
		JsonNode value = node.get(name);
		if (value == null || value.isNull() || value.asText().trim().isEmpty()) {
			throw new Config.ConfigException(name + " is required");
		}
		return value.asText().trim();
	}

	private String readOptionalString(JsonNode node, String name) throws Config.ConfigException {
		if (node == null || node.isNull()) {
			return null;
		}
		JsonNode value = node.get(name);
		if (value == null || value.isNull()) {
			return null;
		}
		String text = value.asText();
		if (text == null || text.trim().isEmpty()) {
			return null;
		}
		return text.trim();
	}

	private Integer readOptionalPositiveInt(JsonNode node, String name) throws Config.ConfigException {
		if (node == null || node.isNull()) {
			return null;
		}
		JsonNode value = node.get(name);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isNumber()) {
			throw new Config.ConfigException(name + " must be a positive integer");
		}
		int result = value.asInt();
		if (result < 1) {
			throw new Config.ConfigException(name + " must be a positive integer");
		}
		return result;
	}

	private Integer readOptionalNonNegativeInt(JsonNode node, String name) throws Config.ConfigException {
		if (node == null || node.isNull()) {
			return null;
		}
		JsonNode value = node.get(name);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isNumber()) {
			throw new Config.ConfigException(name + " must be a non-negative integer");
		}
		int result = value.asInt();
		if (result < 0) {
			throw new Config.ConfigException(name + " must be a non-negative integer");
		}
		return result;
	}

	private void writeResponse(BufferedWriter writer, Response response) throws Exception {
		writeRaw(writer, mapper.valueToTree(response));
	}

	private void writeRaw(BufferedWriter writer, JsonNode node) throws Exception {
		writer.write(mapper.writeValueAsString(node));
		writer.write("\n");
		writer.flush();
	}
}
