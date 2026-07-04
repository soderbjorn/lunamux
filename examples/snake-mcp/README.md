# snake-mcp — playable Snake driven entirely over MCP

A standalone MCP client that plays Snake inside a Termtastic
**agent-console** pane. It exercises the whole screen-mode stack end to
end: the driver opens a screen console (`open_console`), paints frames
(`screen_draw` + `screen_present`), and reads the *player's* cursor keys
back from the pane (`console_poll_input`). You play with the arrow keys
inside any Termtastic client — web, Electron, Android, or iOS — while
this process just renders and reacts.

The driver uses **only public MCP tools** over plain JSON-RPC
(streamable HTTP, `java.net.http`) — no SDK and no backdoor into server
internals. The identical server endpoint also drives Node/TS MCP clients
(e.g. Claude Code) unchanged; this driver is kept in Kotlin so it builds
and tests in this repo's CI.

## Play it

1. Start Termtastic (the packaged app, or `./gradlew :server:run` for
   the dev server on port **8444**).
2. In Termtastic's settings dialog (server-side window), open the
   **MCP server** section, press **Generate MCP token**, and flip the
   new token to **Allow writes** (the console tools need `read+write`).
3. Run the driver (from the repo root):

   ```sh
   ./gradlew :examples:snake-mcp:run --args="--token <your-token> --insecure"
   ```

   - Default endpoint is `https://localhost:8443/mcp`; pass
     `--url https://localhost:8444/mcp` for the dev server.
   - `--insecure` accepts the server's self-signed localhost TLS
     certificate. To verify instead, import the PEM shown in the MCP
     settings section (`server-leaf.pem`) into a Java truststore.
   - `--fps 12` adjusts the frame rate (default 10).

4. A "Snake" pane appears in your active tab. Click into it and steer
   with the **arrow keys**; **q** quits. On game over, any key restarts
   and `q` exits.

## Test it (no human required)

```sh
./gradlew :examples:snake-mcp:test
```

The headless test boots the real MCP routes on a local port, mints a
token, connects this driver, and injects synthetic arrow-key bytes into
the agent session's input channel — asserting the snake turns, grows on
food, dies on wall/self collision, and that frames actually land on the
console's screen grid.
