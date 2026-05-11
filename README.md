# mcp-sql

MCP server for JDBC-backed SQL access. It provides scoped tools for SQL execution and schema introspection.

## Features
- MCP tools: `sql_query`, `sql_inspect_schema`
- Multiple named JDBC connections with pooling
- Per-call `_meta.policy` restrictions and default-connection overrides
- Schema introspection with token budget safeguards

## Configuration
Configuration is provided via MCP initialize `capabilities.experimental.configuration`.

Key fields:
- `connections`: list of JDBC connection definitions
- `connections[].default_schema`: default schema for unqualified queries
- `connections[].allowed_schemas`: schema allowlist for the connection
- `default_connection`: connection name used when a tool call omits it
- `query.max_rows`: default max SELECT rows (default 200)
- `query.timeout_ms`: default statement timeout (default 30000)
- `query.preview_max_rows`: default max preview rows (defaults to `query.max_rows`)
- `query.preview_display_rows`: rows shown by default in preview HTML (default 10)
- `query.include_select_review`: include review HTML for SELECT results (default false)
- `introspect.max_tokens`: approximate token budget for metadata (default 8000)
- `introspect.limit`: maximum objects returned (default 200)
- `introspect.include_tables`: include tables (default true)
- `introspect.include_procedures`: include stored procedures (default false)
- `introspect.relation_depth`: related-table expansion depth via foreign keys (default 1)

Driver keywords:
- `postgresql`, `mysql`, `sqlserver`, `oracle`, `h2`

SSH tunneling:
- If `ssh.remote_host` or `ssh.remote_port` are omitted, the server infers them from the JDBC URL when possible.
- When missing, ports default to the driver default (e.g., 5432 for Postgres).

Example initialize payload:
```json
{
  "method": "initialize",
  "params": {
    "capabilities": {
      "experimental": {
        "configuration": {
          "default_connection": "primary",
          "connections": [
            {
              "name": "primary",
              "driver": "postgresql",
              "url": "jdbc:postgresql://localhost:5432/app",
              "username": "app",
              "password_env": "APP_DB_PASSWORD",
              "ssh": {
                "host": "bastion.example.com",
                "port": 22,
                "user": "deploy",
                "key_path_env": "MCP_SSH_KEY",
                "key_passphrase_env": "MCP_SSH_KEY_PASSPHRASE",
                "remote_host": "db.internal",
                "remote_port": 5432,
                "local_port": 0,
                "trust_on_first_use": true
              },
              "default_schema": "public",
              "allowed_schemas": ["public"],
              "pool": {
                "max_pool_size": 10,
                "min_idle": 1
              }
            }
          ],
          "query": {
            "max_rows": 200,
            "timeout_ms": 30000
          },
          "introspect": {
            "max_tokens": 8000,
            "limit": 200
          }
        }
      }
    }
  }
}
```

## Tools

### sql_query
Run SQL statements (SELECT/DML/DDL).

Input:
- `sql` (required): SQL statement
- `params` (optional): positional parameters for `?` placeholders
- `max_rows` (optional): override max rows for SELECT
- `offset` (optional): row offset for pagination (SELECT only)

Preview mode:
- Use `_meta.preview: true` for DML to run in a transaction and roll back.
- Preview is not supported for SELECT/DDL/procedure calls.
- When preview is not supported, the server returns JSON-RPC error code `-32050`.

Preview resources:
- When preview is enabled, responses include `_meta.reviewUri` pointing to a `ui://sql_query/<id>` resource.
- Read the HTML via `resources/read` using that URI.

### sql_inspect_schema
Inspect schemas, tables, columns, and relationships. Returns the database vendor.

Input:
- `schema` (optional): single schema filter
- `search` (optional): list of name terms (wildcards supported, any term can match)
- `limit` (optional): max objects to return
- `offset` (optional): object offset for pagination
- `include_tables`, `include_views`, `include_procedures`
- `include_columns` (default false), `include_indexes`

If `search` is omitted, the tool only lists object names without columns.

## Runtime policy
Calls can be restricted via `_meta.policy` in `tools/call`. Policy is restrictive, but can override the default connection name.

```json
{
  "name": "sql_query",
  "arguments": { "sql": "select * from users limit 5" },
  "_meta": {
    "policy": {
      "default_connection": "reporting",
      "allowed_connections": ["reporting"],
      "max_rows": 50,
      "timeout_ms": 5000,
      "allow_select": true
    }
  }
}
```

## Development
Build the server:
```
mvn -q -DskipTests package
```

Build a native executable (requires GraalVM):
```
mvn -q -DskipTests -Pnative package
```
The binary is written to `target/mcp-sql` (or `target/mcp-sql.exe` on Windows).

Print the config schema:
```
java -jar target/mcp-sql-0.1.0-SNAPSHOT.jar --print-config-schema
```
