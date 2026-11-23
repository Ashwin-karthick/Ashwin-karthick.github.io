# Hermes Mobile

A phone-first web app for chatting with your self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent). It's static HTML, CSS and JS with no build step, and you can install it to your home screen.

Once this branch is merged and GitHub Pages publishes it, the app lives at `https://ashwinkarthick.me/hermes/`. Add `?demo=1` to look around with canned data and no server.

## What it does

- Streams replies from `POST /v1/chat/completions`, with markdown, code blocks and a copy button.
- Shows each tool call as a step on the reply's trail (`hermes.tool.progress` events), with live running and finished states and how long each took.
- Lets you answer **approval requests** (`approval.request`) inline: allow once, for this chat, always, or deny. Answers go to `POST /v1/runs/{id}/approval`.
- Has a stop button. It calls `POST /v1/runs/{id}/stop` and drops the stream, which the server also treats as an interrupt.
- Shows reasoning when the model streams it, and server status lines (`hermes.status`).
- Lets you browse **Skills** (`/v1/skills`), **Tools** (`/v1/toolsets`) and **Scheduled jobs** (`/api/jobs`), and run, pause or resume jobs.
- Keeps a list of conversations on the device, with rename, delete and retry.
- Has light and dark themes, follows the safe area, keeps the composer above the keyboard, and works offline as an app shell.

## Server setup

1. Enable the API server on the machine running Hermes, for example in `~/.hermes/.env`:

   ```bash
   API_SERVER_ENABLED=true
   API_SERVER_KEY=choose-a-long-random-key
   API_SERVER_CORS_ORIGINS=https://ashwinkarthick.me
   ```

   `API_SERVER_CORS_ORIGINS` must be the exact origin the app is served from: scheme and host, no path, no trailing slash.

2. Start the gateway with `hermes gateway`. The API listens on `127.0.0.1:8642` by default.

3. **Give it an HTTPS address your phone can reach.** The app is served over HTTPS, so browsers block calls to plain `http://` servers. Some options:
   - Tailscale Serve: `tailscale serve --bg 8642`, then use `https://<machine>.<tailnet>.ts.net`.
   - Cloudflare Tunnel: `cloudflared tunnel --url http://localhost:8642`.
   - A reverse proxy such as Caddy or nginx with a certificate. Turn off response buffering and allow long-lived responses (for nginx, `proxy_buffering off;` and a long `proxy_read_timeout`) or streaming will stall.

4. In the app, open **Settings**, enter the address and key, tap **Test connection**, then **Save**.

## Notes

- **Where data lives.** The API key and conversations are stored in this browser's `localStorage` on your device. Hermes also keeps its own session history on the server. Model output is escaped before rendering and the page sets a strict Content Security Policy, so replies can't inject HTML or script. Images from remote URLs in replies show as links instead of loading automatically, so a web page the agent reads can't use them to send data out.
- **How conversations map to Hermes sessions.** Each turn sends the full transcript, which is the same mode Open WebUI uses. Hermes derives a stable session from the system prompt and first message. The app adds a short per-conversation tag to the system message so that two chats with the same opening line don't share a session.
- **Notifications** fire only while the app is open in the background. True push notifications would need a server-side component.
- **Known limits.** Only the text of earlier replies is re-sent each turn, so the agent doesn't see earlier tool output unless Hermes recalls it from its own memory. Changing the instructions setting partway through a chat starts a new Hermes session for that chat.
