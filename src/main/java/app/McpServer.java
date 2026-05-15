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
import java.util.ArrayList;
import java.util.List;

public final class McpServer {
	private final ObjectMapper mapper = new ObjectMapper();
	private Config config = Config.defaultConfig();
	private final SqlService sqlService = new SqlService(mapper, config);

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
			if (e.resourceUri != null) {
				meta.putObject("ui").put("resourceUri", e.resourceUri);
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
				throw new PreviewScopesRequiredException(scopeCheck.requestedScopes, staticPreviewResourceUri());
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
			String resourceUri = null;
			boolean isDml = sqlType == SqlService.SqlType.DML;
			boolean includeSelectReview = sqlType == SqlService.SqlType.SELECT && config.query.includeSelectReview;
			if (preview || isDml || includeSelectReview) {
				resourceUri = staticPreviewResourceUri();
			}
			ObjectNode metaPayload = buildPreviewMeta(preview, scopeCheck.requestedScopes, resourceUri, actionLabel, structured, runtimeMs);
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

	private ObjectNode toolSuccess(String name, ObjectNode structured, String actionLabel, ObjectNode meta) throws JsonProcessingException {
		ObjectNode payload = mapper.createObjectNode();
		ArrayNode content = payload.putArray("content");
		ObjectNode text = content.addObject();
		text.put("type", "text");
		text.put("text", mapper.writeValueAsString(structured));
		payload.set("structuredContent", structured);
		ObjectNode payloadMeta = meta == null ? mapper.createObjectNode() : meta.deepCopy();
		payloadMeta.put("displayMessage", buildDisplayMessage(name, structured, actionLabel));
		if (payloadMeta.size() > 0) {
			payload.set("_meta", payloadMeta);
		}
		return payload;
	}

	private ObjectNode buildPreviewMeta(boolean preview, List<String> requestedScopes, String resourceUri, String actionLabel, ObjectNode structured, long runtimeMs) {
		ObjectNode meta = mapper.createObjectNode();
		if (preview) {
			meta.put("preview", true);
		}
		if (resourceUri != null) {
			meta.putObject("ui").put("resourceUri", resourceUri);
			ObjectNode uiData = meta.putObject("uiData");
			uiData.put("actionLabel", actionLabel == null ? "Affected" : actionLabel);
			uiData.put("runtimeMs", runtimeMs);
			uiData.put("displayRows", previewDisplayRows());
			uiData.set("structuredContent", structured);
		}
		if (requestedScopes != null && !requestedScopes.isEmpty()) {
			meta.set("requested_scopes", mapper.valueToTree(requestedScopes));
		}
		return meta.size() == 0 ? null : meta;
	}

	private String buildDisplayMessage(String name, ObjectNode structured, String actionLabel) {
		if (structured.has("rowCount")) {
			String label = actionLabel == null ? "Affected" : actionLabel;
			return label + " " + structured.path("rowCount").asInt() + " row(s)";
		}
		if (structured.has("objectCount")) {
			return "Found " + structured.path("objectCount").asInt() + " object(s)";
		}
		return name + " completed";
	}

	private String staticPreviewResourceUri() {
		return "ui://sql_query/static";
	}

	private String buildPreviewHtml() {
		StringBuilder html = new StringBuilder();
		html.append("<!doctype html><html><head><meta charset=\"utf-8\"/>");
		html.append("<style>");
		html.append("body{font-family:var(--font-sans, 'Anthropic Sans', sans-serif);background:var(--color-background-tertiary,#faf9f5);color:var(--color-text-primary,#141413);margin:0;padding:24px;}" );
		html.append(".card{background:var(--color-background-primary,#fff);border:1px solid var(--color-border-primary,rgba(31,30,29,0.4));border-radius:var(--border-radius-xl,12px);padding:18px;box-shadow:var(--shadow-lg,0 10px 15px -3px rgba(0,0,0,0.1), 0 4px 6px -4px rgba(0,0,0,0.1));}" );
		html.append(".headline{font-family:var(--font-sans, 'Anthropic Sans', sans-serif);font-size:var(--font-heading-lg-size,20px);line-height:var(--font-heading-lg-line-height,1.25);margin:0 0 6px;}" );
		html.append(".metric{font-weight:var(--font-weight-semibold,600);color:var(--color-text-primary,#141413);}" );
		html.append(".meta{font-size:var(--font-text-xs-size,12px);line-height:var(--font-text-xs-line-height,1.4);color:var(--color-text-secondary,#3d3d3a);margin-bottom:12px;}" );
		html.append(".filter{display:flex;align-items:center;gap:8px;margin:4px 0 10px;}" );
		html.append(".filter input{flex:1;padding:6px 8px;border-radius:var(--border-radius-sm,6px);border:1px solid var(--color-border-primary,rgba(31,30,29,0.4));background:var(--color-background-secondary,#f5f4ed);color:var(--color-text-primary,#141413);font-size:var(--font-text-xs-size,12px);line-height:var(--font-text-xs-line-height,1.4);}" );
		html.append("table{width:100%;border-collapse:collapse;font-size:var(--font-text-sm-size,14px);line-height:var(--font-text-sm-line-height,1.4);}" );
		html.append("th,td{border-bottom:1px solid var(--color-border-tertiary,rgba(31,30,29,0.15));padding:6px 8px;text-align:left;vertical-align:top;}" );
		html.append("th{background:var(--color-background-secondary,#f5f4ed);font-weight:var(--font-weight-semibold,600);color:var(--color-text-primary,#141413);}" );
		html.append(".warn{margin-top:10px;color:var(--color-text-warning,#5a4815);font-size:var(--font-text-xs-size,12px);line-height:var(--font-text-xs-line-height,1.4);}" );
		html.append("tr.ctx-row{display:none;}" );
		html.append("tr.ctx-row.ctx-open{display:table-row;}" );
		html.append("tr.ctx-toggle td{padding:4px 6px;background:color-mix(in srgb, var(--color-background-secondary,#f5f4ed) 92%, var(--color-border-primary,rgba(31,30,29,0.4)) 8%);border-bottom:1px dashed var(--color-border-primary,rgba(31,30,29,0.4));}" );
		html.append(".ctx-cell{text-align:center;}" );
		html.append(".ctx-btn{font:inherit;font-size:var(--font-text-xs-size,12px);line-height:var(--font-text-xs-line-height,1.4);color:var(--color-text-info,#3266ad);background:var(--color-background-primary,#fff);border:1px solid var(--color-border-primary,rgba(31,30,29,0.4));border-radius:var(--border-radius-sm,6px);padding:2px 8px;cursor:pointer;}" );
		html.append("</style></head><body>");
		html.append("<div class=\"card\">");
		html.append("<div class=\"headline\"><span class=\"metric\" data-headline></span></div>");
		html.append("<div class=\"meta\" data-meta></div>");
		html.append("<div class=\"filter\" data-filter-wrap style=\"display:none\"><input type=\"text\" placeholder=\"Filter rows\" data-filter /></div>");
		html.append("<table data-table style=\"display:none\"><thead><tr data-columns>");
		html.append("</tr></thead><tbody data-rows></tbody></table>");
		html.append("<div class=\"warn\" data-empty style=\"display:none\"></div>");
		html.append("</div>");
		html.append("<script>");
		html.append("(function(){function esc(v){return String(v==null?'':v).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\\\"/g,'&quot;').replace(/'/g,'&#39;');}function update(btn,open){btn.textContent=open?btn.getAttribute('data-expanded'):btn.getAttribute('data-collapsed');btn.setAttribute('aria-expanded',open?'true':'false');}function bindToggles(){document.querySelectorAll('button.ctx-btn').forEach(function(btn){update(btn,false);btn.addEventListener('click',function(){var group=btn.getAttribute('data-group');var rows=document.querySelectorAll('tr.ctx-row[data-group=\\\"'+group+'\\\"]');var open=btn.getAttribute('aria-expanded')!=='true';rows.forEach(function(row){row.classList.toggle('ctx-open',open);});update(btn,open);});});}function bindFilter(){var input=document.querySelector('[data-filter]');var table=document.querySelector('[data-table]');if(!input||!table||table.style.display==='none'){return;}input.addEventListener('input',function(){var query=input.value.toLowerCase();var dataRows=table.querySelectorAll('tr.data-row');var toggleRow=table.querySelector('tr.ctx-toggle');if(query){dataRows.forEach(function(row){row.classList.add('ctx-open');var text=row.textContent.toLowerCase();row.style.display=text.indexOf(query)>=0?'':'none';});if(toggleRow){toggleRow.style.display='none';}}else{dataRows.forEach(function(row){row.style.display='';if(row.classList.contains('ctx-row')){row.classList.remove('ctx-open');}});if(toggleRow){toggleRow.style.display='';}document.querySelectorAll('button.ctx-btn').forEach(function(btn){update(btn,false);});}});}function sortRows(th,idx){var table=th.closest('table');var tbody=table.querySelector('tbody');var toggleRow=tbody.querySelector('tr.ctx-toggle');var hiddenRows=Array.from(tbody.querySelectorAll('tr.ctx-row'));var rows=Array.from(tbody.querySelectorAll('tr.data-row:not(.ctx-row)'));var dir=th.getAttribute('data-dir')==='asc'?'desc':'asc';table.querySelectorAll('th').forEach(function(h){h.removeAttribute('data-dir');});th.setAttribute('data-dir',dir);rows.sort(function(a,b){var av=a.children[idx]&&a.children[idx].textContent||'';var bv=b.children[idx]&&b.children[idx].textContent||'';return dir==='asc'?av.localeCompare(bv):bv.localeCompare(av);});rows.forEach(function(r){tbody.appendChild(r);});if(toggleRow){tbody.appendChild(toggleRow);}hiddenRows.forEach(function(r){tbody.appendChild(r);});}function bindSort(){document.querySelectorAll('[data-table] thead th').forEach(function(th,idx){th.style.cursor='pointer';th.addEventListener('click',function(){sortRows(th,idx);});});}function clearTable(columnsRow,rowsBody){columnsRow.innerHTML='';rowsBody.innerHTML='';}function render(result){var headline=document.querySelector('[data-headline]');var meta=document.querySelector('[data-meta]');var filterWrap=document.querySelector('[data-filter-wrap]');var table=document.querySelector('[data-table]');var columnsRow=document.querySelector('[data-columns]');var rowsBody=document.querySelector('[data-rows]');var empty=document.querySelector('[data-empty]');var uiData=result&&result._meta&&result._meta.uiData||null;var structured=result&&result.structuredContent||uiData&&uiData.structuredContent||null;var actionLabel=uiData&&uiData.actionLabel||'Affected';var runtimeMs=uiData&&typeof uiData.runtimeMs==='number'?uiData.runtimeMs:0;var displayRows=Math.max(1, uiData&&uiData.displayRows||10);clearTable(columnsRow,rowsBody);filterWrap.style.display='none';table.style.display='none';empty.style.display='none';if(!structured){headline.textContent='';meta.textContent='';empty.style.display='block';empty.textContent='No structuredContent available';return;}var rowCount=typeof structured.rowCount==='number'?structured.rowCount:0;headline.textContent=actionLabel+' Rows: '+rowCount;var metaText='Runtime: '+runtimeMs+' ms';if(typeof structured.offset==='number'){metaText+=' | Offset: '+structured.offset;}meta.textContent=metaText;if(Array.isArray(structured.columns)){table.style.display='table';filterWrap.style.display='flex';structured.columns.forEach(function(col){var th=document.createElement('th');th.innerHTML=esc(col&&col.name||'');columnsRow.appendChild(th);});var rows=Array.isArray(structured.rows)?structured.rows:[];var cutoff=Math.min(displayRows, rows.length);for(var i=0;i<cutoff;i++){var tr=document.createElement('tr');tr.className='data-row';(rows[i]||[]).forEach(function(cell){var td=document.createElement('td');td.innerHTML=esc(cell===null?'null':cell);tr.appendChild(td);});rowsBody.appendChild(tr);}if(rows.length>cutoff){var remaining=rows.length-cutoff;var toggle=document.createElement('tr');toggle.className='ctx-toggle';toggle.setAttribute('data-group','preview-extra');var td=document.createElement('td');td.className='ctx-cell';td.colSpan=structured.columns.length;var btn=document.createElement('button');btn.className='ctx-btn';btn.setAttribute('data-group','preview-extra');btn.setAttribute('data-collapsed','Show remaining '+remaining+' rows');btn.setAttribute('data-expanded','Hide remaining '+remaining+' rows');btn.setAttribute('aria-expanded','false');btn.textContent='Show remaining '+remaining+' rows';td.appendChild(btn);toggle.appendChild(td);rowsBody.appendChild(toggle);for(var j=cutoff;j<rows.length;j++){var hidden=document.createElement('tr');hidden.className='ctx-row data-row';hidden.setAttribute('data-group','preview-extra');(rows[j]||[]).forEach(function(cell){var cellTd=document.createElement('td');cellTd.innerHTML=esc(cell===null?'null':cell);hidden.appendChild(cellTd);});rowsBody.appendChild(hidden);}}if(structured.truncated){var limit=typeof structured.maxRows==='number'?structured.maxRows:(typeof structured.previewLimit==='number'?structured.previewLimit:(typeof structured.rowLimit==='number'?structured.rowLimit:0));var warn=document.createElement('tr');var warnTd=document.createElement('td');warnTd.colSpan=structured.columns.length;warnTd.className='warn';warnTd.textContent='Showing first '+limit+' rows';warn.appendChild(warnTd);rowsBody.appendChild(warn);}bindToggles();bindFilter();bindSort();return;}empty.style.display='block';empty.textContent=JSON.stringify(structured,null,2);}var app=new App({name:'sql-preview',version:'1.0.0'});app.ontoolresult=function(result){render(result);};app.connect().catch(function(error){var empty=document.querySelector('[data-empty]');if(empty){empty.style.display='block';empty.textContent=error&&error.message?error.message:'Failed to connect';}});})();");
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
		ObjectNode item = resources.addObject();
		item.put("uri", staticPreviewResourceUri());
		item.put("mimeType", "text/html");
		item.put("title", "SQL Preview");
		return result;
	}

	private ObjectNode resourcesRead(Request request) throws Config.ConfigException {
		JsonNode params = request.params == null ? mapper.createObjectNode() : request.params;
		String uri = params.path("uri").asText();
		if (uri == null || uri.isBlank()) {
			throw new Config.ConfigException("uri is required");
		}
		if (!staticPreviewResourceUri().equals(uri)) {
			throw new Config.ConfigException("resource not found");
		}
		ObjectNode result = mapper.createObjectNode();
		ArrayNode contents = result.putArray("contents");
		ObjectNode content = contents.addObject();
		content.put("uri", uri);
		content.put("mimeType", "text/html");
		content.put("text", buildPreviewHtml());
		return result;
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
		final String resourceUri;

		PreviewScopesRequiredException(List<String> requestedScopes, String resourceUri) {
			this.requestedScopes = requestedScopes;
			this.resourceUri = resourceUri;
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
