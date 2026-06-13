#!/usr/bin/env bash
set -euo pipefail

REPO="M8ZOPPP/agent-pulse"
if [[ -f "$(dirname "$0")/install.mjs" ]]; then
  exec node "$(dirname "$0")/install.mjs" "$@"
fi

command -v node >/dev/null || { echo "Node.js 20+ is required." >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required." >&2; exit 1; }
command -v tar >/dev/null || { echo "tar is required." >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL "https://github.com/${REPO}/archive/refs/heads/main.tar.gz" | tar -xz -C "$tmp"
exec node "$tmp/agent-pulse-main/install.mjs" "$@"
