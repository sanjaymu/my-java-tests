# Sample MCP server

A small [Model Context Protocol](https://modelcontextprotocol.io) server for experimenting with MCP concepts.
It manages an in-memory to-do list (lost on restart) and is built with Spring AI 2.0's MCP annotations.

It is a separate app from the API server in the repository root.

## What it exposes

| MCP concept | Name | What it does |
|---|---|---|
| **Tool** | `add_task(title)` | Adds a task, returns it with its id |
| **Tool** | `list_tasks()` | Lists all tasks |
| **Tool** | `complete_task(id)` | Marks a task done. An unknown id returns an **error result** (`isError: true`) |
| **Tool** | `get_current_time(timezone?)` | Current time; `timezone` is an **optional** parameter (defaults to UTC) |
| **Resource** | `tasks://summary` | Read-only text summary of the task list |
| **Prompt** | `plan_my_day(focus?)` | A ready-made prompt asking the model to plan the day around the tasks |

- **Tools** are actions the model decides to call.
- **Resources** are data the client app chooses to load into the model's context.
- **Prompts** are templates the user picks (for example from a menu or a slash command).

The code is in `src/main/java/com/example/mcpserver/`: one class per concept (`TaskTools`, `TimeTools`, `TaskResources`, `TaskPrompts`).

## Run

From the repository root:

```bash
./mvnw -f mcp-server/pom.xml spring-boot:run
```

The MCP endpoint is `http://localhost:8081/mcp` (Streamable HTTP). The port comes from `PORT` if set.

## Explore it

**MCP Inspector (web UI):**
```bash
npx @modelcontextprotocol/inspector
```
Choose transport **Streamable HTTP**, URL `http://localhost:8081/mcp`, click **Connect**, then use the Tools, Resources and Prompts tabs.

**MCP Inspector (command line):**
```bash
npx @modelcontextprotocol/inspector --cli http://localhost:8081/mcp --transport http --method tools/list
npx @modelcontextprotocol/inspector --cli http://localhost:8081/mcp --transport http \
  --method tools/call --tool-name add_task --tool-arg title="Learn MCP"
```

**Raw JSON-RPC with curl:**
```bash
MCP=http://localhost:8081/mcp
H=(-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream")

SID=$(curl -s -D - -o /dev/null "${H[@]}" "$MCP" -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
  "params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}' \
  | grep -i '^mcp-session-id:' | awk '{print $2}' | tr -d '\r')
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" "$MCP" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" "$MCP" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" "$MCP" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"add_task","arguments":{"title":"Learn MCP"}}}'
```
Responses arrive as server-sent events; the JSON is on the line starting with `data:`.

**Claude Code:**
```bash
claude mcp add --transport http sample-tasks http://localhost:8081/mcp
```

## Test

```bash
./mvnw -f mcp-server/pom.xml verify
```

- Unit tests cover the task store, time tool, resource and prompt.
- `McpServerIT` starts the server and connects a real MCP client over Streamable HTTP. It lists tools and checks their JSON schemas, calls tools (including the error case), reads the resource and gets the prompt.

## Note

This server has **no authentication**. It's meant for running locally. Don't deploy it publicly as-is.
