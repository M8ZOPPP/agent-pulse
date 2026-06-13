# Agent Pulse

A native Kotlin Android app that notifies you when Codex or Claude Code:

- needs command or tool permission
- asks a question or becomes idle waiting for input
- finishes a turn
- reports an error

Agent Pulse uses Firebase Cloud Messaging (FCM) for immediate background delivery and a durable relay event log for catch-up. If Android or FCM delays a push, opening the app retrieves events missed since the last local event.

## Components

- `android/`: native Kotlin Android app with a Material developer dashboard
- `relay/server.mjs`: authenticated Node.js FCM relay with a 500-event ledger
- `hooks/agent-pulse-hook.mjs`: secret-redacting Codex and Claude hook adapter
- `install.mjs`: interactive machine/VPS installer that preserves existing hooks
- `install.sh`: local installer and GitHub one-line bootstrap

The Firebase service account stays on your server. The APK receives only Firebase's public Android client settings during pairing.

## Install the APK

Download [`dist/agent-pulse-debug.apk`](dist/agent-pulse-debug.apk) or build it:

```bash
cd android
./gradlew assembleDebug
```

The package ID is `dev.agentpulse.app`. Android 13+ asks for notification permission when you pair the phone.

## Firebase setup

1. Create a Firebase project.
2. Add an Android app with package name `dev.agentpulse.app`.
3. Download the Android `google-services.json`.
4. In Firebase project settings, create a service-account private key JSON.
5. Keep both JSON files on the VPS/computer where the relay will run. Never commit the service-account file.

FCM's HTTP v1 API must be enabled for the Firebase project.

## Machine/VPS installer

Clone and run:

```bash
git clone https://github.com/M8ZOPPP/agent-pulse.git
cd agent-pulse
node install.mjs
```

After publication, the one-line installer is:

```bash
curl -fsSL https://raw.githubusercontent.com/M8ZOPPP/agent-pulse/main/install.sh | bash
```

The installer asks for:

- the public HTTPS relay URL, such as `https://pulse.example.com`
- the Firebase service-account JSON path
- the Android `google-services.json` path

It then:

- installs the relay under `~/.local/share/agent-pulse`
- writes credentials with mode `0600` under `~/.config/agent-pulse`
- installs dependencies
- configures and starts `agent-pulse.service` as a user systemd service
- merges Agent Pulse into existing `~/.codex/hooks.json`
- merges Agent Pulse into existing `~/.claude/settings.json`
- prints the relay URL and generated pairing token for the APK

Non-interactive example:

```bash
node install.mjs --yes \
  --relay-url https://pulse.example.com \
  --firebase-service-account /secure/firebase-admin.json \
  --firebase-client-config /secure/google-services.json
```

Use `--no-systemd` to install files and hooks without starting a service.

## HTTPS reverse proxy

The relay binds to `127.0.0.1:8787`. Put Caddy, Nginx, Cloudflare Tunnel, or another TLS proxy in front of it. Caddy example:

```caddy
pulse.example.com {
    reverse_proxy 127.0.0.1:8787
}
```

Do not expose port `8787` directly to the internet. The Android app intentionally rejects plain HTTP relay URLs.

## Pair the phone

1. Open Agent Pulse.
2. Enter the HTTPS relay URL and pairing token printed by the installer.
3. Tap **Pair this phone** and allow notifications.
4. Tap **Send test**.
5. For Codex, run `/hooks` once and trust the new Agent Pulse hooks.

The dashboard lets you independently disable permission, question, completion, or error notifications. **Sync missed** fetches the durable event ledger manually; the app also syncs whenever it opens.

## Hook coverage

### Codex

The installer configures `PermissionRequest` and `Stop`. A stop event whose final message looks like a question is classified as a question; otherwise it is a completion.

### Claude Code

The installer configures `PermissionRequest`, `Notification` (`permission_prompt|idle_prompt`), and `Stop`.

The hook adapter redacts common API keys, bearer tokens, passwords, secrets, and private-key blocks before sending summaries. It exits successfully when the relay is unavailable so it cannot block an agent session.

## Security notes

- Use a dedicated random pairing token and HTTPS.
- The relay accepts at most 64 KiB per event and clips notification fields.
- Firebase service-account credentials never leave the server.
- FCM data messages contain only the redacted event summary.
- This release sends notifications only. It does not remotely approve terminal commands or inject keystrokes into a live session.

Remote approval should be implemented later with explicit, expiring action IDs and a controlled agent-session protocol. Direct terminal keystroke forwarding would be unsafe and unreliable.

## Development

Requirements: JDK 17, Android SDK 35, Node.js 20+.

```bash
node --check relay/server.mjs
node --check hooks/agent-pulse-hook.mjs
node --check install.mjs
cd android && ./gradlew assembleDebug
```

Useful service commands:

```bash
systemctl --user status agent-pulse
journalctl --user -u agent-pulse -f
systemctl --user restart agent-pulse
```

## References

- [Firebase: receive messages on Android](https://firebase.google.com/docs/cloud-messaging/android/receive-messages)
- [Firebase: send messages with HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api)
- [Codex hooks](https://developers.openai.com/codex/hooks)
- [Claude Code hooks](https://code.claude.com/docs/en/hooks)
