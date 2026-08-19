#!/usr/bin/env bash
#
# Runs on the HOST (devcontainer.json "initializeCommand"), before the dev
# container is built/started. Finds whatever Docker-API-compatible socket the
# host exposes - Docker Engine/Desktop, rootless Docker, Podman, Colima, ... -
# and points a repo-local, gitignored symlink at it. devcontainer.json binds
# that symlink to the path the docker-outside-of-docker feature expects
# (/var/run/docker-host.sock), so the container never needs to know which
# engine is actually behind it.
#
# Missing socket is not fatal: the dev container still starts, `docker` inside
# it just fails to connect until this is fixed (see docs/PODMAN-VSCODE.md).

set -euo pipefail

link_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.container-socket"
link_path="$link_dir/docker.sock"
mkdir -p "$link_dir"

candidates=()
if [[ -n "${DOCKER_HOST:-}" ]]; then
    candidates+=("${DOCKER_HOST#unix://}")
fi
candidates+=(
    "/var/run/docker.sock"
    "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/docker.sock"
    "${HOME}/.docker/run/docker.sock"
    "${HOME}/.colima/default/docker.sock"
    "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"
)
if [[ "$(uname -s)" == "Darwin" ]] && command -v podman >/dev/null 2>&1; then
    candidates+=("$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}' 2>/dev/null || true)")
fi

socket=""
for candidate in "${candidates[@]}"; do
    if [[ -n "$candidate" && -S "$candidate" ]]; then
        socket="$candidate"
        break
    fi
done

if [[ -n "$socket" ]]; then
    ln -sf "$socket" "$link_path"
    echo "devcontainer: linked container engine socket ($socket)."
else
    # Placeholder file so the bind mount in devcontainer.json has a stable,
    # existing source (a missing path would otherwise be auto-created as a
    # directory, which breaks the socket bind mount).
    rm -f "$link_path"
    touch "$link_path"
    echo "devcontainer: no container engine socket found among:" >&2
    printf '  %s\n' "${candidates[@]}" >&2
    echo "  Start Docker/Podman/Colima on the host, then reopen the dev container." >&2
fi
