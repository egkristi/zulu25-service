#!/usr/bin/env bash
#
# Runs on the HOST (devcontainer.json "initializeCommand"), before the dev
# container is built/started. Points a repo-local, gitignored symlink at the
# host's rootless Podman API socket so devcontainer.json can bind-mount it
# with a path that doesn't depend on the host's UID or OS.
#
# Missing Podman/socket is not fatal: the dev container still starts, `podman`
# inside it just fails to connect until this is fixed (see docs/PODMAN-VSCODE.md).

set -euo pipefail

link_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.podman-socket"
link_path="$link_dir/podman.sock"
mkdir -p "$link_dir"

socket=""
if command -v podman >/dev/null 2>&1; then
    if [[ "$(uname -s)" == "Darwin" ]]; then
        socket="$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}' 2>/dev/null || true)"
    else
        socket="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"
    fi
fi

if [[ -n "$socket" && -S "$socket" ]]; then
    ln -sf "$socket" "$link_path"
    echo "devcontainer: linked host Podman socket ($socket)."
else
    # Placeholder file so the bind mount in devcontainer.json has a stable,
    # existing source (a missing path would otherwise be auto-created as a
    # directory, which breaks the socket bind mount).
    rm -f "$link_path"
    touch "$link_path"
    echo "devcontainer: no host Podman socket found." >&2
    echo "  Linux : systemctl --user enable --now podman.socket" >&2
    echo "  macOS : podman machine start" >&2
    echo "  then reopen the dev container." >&2
fi
