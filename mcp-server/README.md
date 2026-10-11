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
| **MCP App** | `show_task_board()` + `ui://tasks/board` | An interactive task board the host shows in the chat (see below) |

- **Tools** are actions the model decides to call.
- **Resources** are data the client app chooses to load into the model's context.
- **Prompts** are templates the user picks (for example from a menu or a slash command).

- **MCP Apps** ([SEP-1865](https://github.com/modelcontextprotocol/ext-apps), extension `io.modelcontextprotocol/ui`) let a tool show interactive UI in the chat.

The code is in `src/main/java/com/example/mcpserver/`: one class per concept (`TaskTools`, `TimeTools`, `TaskResources`, `TaskPrompts`, `TaskBoardApp`).

## The task board MCP App

`show_task_board` is linked to a UI through its tool metadata:

```json
{ "name": "show_task_board", "_meta": { "ui": { "resourceUri": "ui://tasks/board" } } }
```

1. When the model calls `show_task_board`, a host that supports MCP Apps reads `ui://tasks/board`. That's an HTML page served with MIME type `text/html;profile=mcp-app`, from `src/main/resources/mcp-app/task-board.html`.
2. The host renders the page in a sandboxed iframe. The page and host do the `ui/initialize` and `ui/notifications/initialized` handshake.
3. The host sends the tool result as `ui/notifications/tool-result`. The board draws from its `structuredContent.tasks`.
4. The **Complete** and **Add** buttons send `tools/call` (`complete_task`, `add_task`) to the host, which forwards them to this server. The board then calls `show_task_board` again to refresh.
5. Hosts without MCP Apps support just show the tool's text content, the same summary as `tasks://summary`.

The page is plain HTML and JavaScript with no SDK and no external requests, so it works under the spec's default content security policy. It also follows the host's light or dark theme and reports its size with `ui/notifications/size-changed`.

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

## See the task board in an MCP Apps host

### Option 1: basic-host (tested; everything runs locally, no account needed)

[basic-host](https://github.com/modelcontextprotocol/ext-apps/tree/main/examples/basic-host) is the reference MCP Apps host from the MCP project. It has no AI model: you pick a tool and call it yourself, and it renders the tool's UI.

basic-host uses ports 8080 and 8081, so run this server on **3001** (basic-host's default server URL):

```bash
# Terminal 1, from this repository's root
PORT=3001 ./mvnw -f mcp-server/pom.xml spring-boot:run

# Terminal 2 (needs Node.js and Bun: https://bun.sh)
git clone https://github.com/modelcontextprotocol/ext-apps.git
cd ext-apps
npm install
cd examples/basic-host
SERVERS='["http://localhost:3001/mcp"]' npm run start
```

Open http://localhost:8080:
1. Pick tool `add_task`, enter `{"title": "Buy milk"}` and click **Call Tool**. Add a few tasks this way.
2. Pick `show_task_board` and click **Call Tool**. The board appears under the result.
3. Click **Complete** or add tasks on the board itself.

basic-host connects from the browser, so this server allows CORS from `http://localhost:8080`. To allow other browser hosts, set `MCP_CORS_ALLOWED_ORIGINS` (comma-separated).

### Option 2: an AI chat host (ask "show my task board")

These hosts list MCP Apps support. I haven't tested these steps, so check each host's documentation.

- **VS Code with GitHub Copilot.** Add `.vscode/mcp.json`:
  ```json
  { "servers": { "tasks": { "type": "http", "url": "http://localhost:8081/mcp" } } }
  ```
  Start the server, open Copilot Chat in **Agent** mode, and ask *"show my task board"*.
- **Claude Desktop.** Desktop config entries start local commands, so bridge to the HTTP server with [`mcp-remote`](https://www.npmjs.com/package/mcp-remote). Add this to `claude_desktop_config.json` (Settings → Developer → Edit Config) and restart Claude Desktop:
  ```json
  { "mcpServers": { "tasks": { "command": "npx", "args": ["-y", "mcp-remote", "http://localhost:8081/mcp"] } } }
  ```
- **Claude.ai and ChatGPT (web)** connect to MCP servers from their own cloud, so they can't reach `localhost`. They need a public HTTPS URL. Don't expose this server publicly as it is, because it has no authentication.

## Test

```bash
./mvnw -f mcp-server/pom.xml verify
```

- Unit tests cover the task store, time tool, resource and prompt.
- `McpServerIT` starts the server and connects a real MCP client over Streamable HTTP. It lists tools and checks their JSON schemas, calls tools (including the error case), reads the resources and gets the prompt. It also checks the task board's `_meta.ui.resourceUri`, its `structuredContent`, and the `ui://` resource's MIME type.

## Note

This server has **no authentication**. It's meant for running locally. Don't deploy it publicly as-is.
