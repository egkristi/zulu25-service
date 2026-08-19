#!/usr/bin/env bash
#
# Rewrites this repository's placeholder identity (package/groupId, artifactId,
# image name) so it can be used as a template for a new service.
#
# Usage:
#   bash scripts/init-template.sh <new-group-id> <new-artifact-id> [<new-registry>]
#
# Example:
#   bash scripts/init-template.sh com.acme.widgets widget-service ghcr.io/acme
#
# Safe to re-run: it becomes a no-op once the old identity is gone.

set -euo pipefail

OLD_GROUP_ID="no.egk.demo"
OLD_ARTIFACT_ID="zulu25-service"
OLD_REGISTRY="ghcr.io/egkristi"

NEW_GROUP_ID="${1:-}"
NEW_ARTIFACT_ID="${2:-}"
NEW_REGISTRY="${3:-}"

if [[ -z "$NEW_GROUP_ID" || -z "$NEW_ARTIFACT_ID" ]]; then
    echo "Usage: bash scripts/init-template.sh <new-group-id> <new-artifact-id> [<new-registry>]" >&2
    echo "Example: bash scripts/init-template.sh com.acme.widgets widget-service ghcr.io/acme" >&2
    exit 1
fi

if ! [[ "$NEW_GROUP_ID" =~ ^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$ ]]; then
    echo "error: '$NEW_GROUP_ID' does not look like a Java package (expected e.g. com.acme.widgets)" >&2
    exit 1
fi
if ! [[ "$NEW_ARTIFACT_ID" =~ ^[a-z][a-z0-9-]*$ ]]; then
    echo "error: '$NEW_ARTIFACT_ID' does not look like a Maven artifactId / image name (expected e.g. widget-service)" >&2
    exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if ! grep -rq -e "$OLD_ARTIFACT_ID" -e "$OLD_GROUP_ID" src pom.xml 2>/dev/null; then
    echo "Nothing to rename - '$OLD_ARTIFACT_ID' / '$OLD_GROUP_ID' were not found under src/ or pom.xml."
    exit 0
fi

is_git_repo=false
if git rev-parse --git-dir >/dev/null 2>&1; then
    is_git_repo=true
fi

move() {
    if [[ "$is_git_repo" == true ]]; then
        git mv "$1" "$2"
    else
        mv "$1" "$2"
    fi
}

echo "Renaming:"
echo "  groupId/package : $OLD_GROUP_ID -> $NEW_GROUP_ID"
echo "  artifactId/image: $OLD_ARTIFACT_ID -> $NEW_ARTIFACT_ID"
[[ -n "$NEW_REGISTRY" ]] && echo "  registry        : $OLD_REGISTRY -> $NEW_REGISTRY"
echo

# --- 1. move the Java package directories ------------------------------------
old_pkg_path="${OLD_GROUP_ID//./\/}"
new_pkg_path="${NEW_GROUP_ID//./\/}"

for base in src/main/java src/test/java; do
    old_dir="$base/$old_pkg_path"
    new_dir="$base/$new_pkg_path"
    [[ -d "$old_dir" ]] || continue
    mkdir -p "$(dirname "$new_dir")"
    move "$old_dir" "$new_dir"

    # Clean up now-empty ancestor package directories (e.g. src/main/java/no/egk).
    old_parent="$(dirname "$old_dir")"
    while [[ "$old_parent" != "$base" && -d "$old_parent" && -z "$(ls -A "$old_parent")" ]]; do
        rmdir "$old_parent"
        old_parent="$(dirname "$old_parent")"
    done
done

# --- 2. rename the systemd Quadlet unit ---------------------------------------
old_unit="deploy/systemd/${OLD_ARTIFACT_ID}.container"
new_unit="deploy/systemd/${NEW_ARTIFACT_ID}.container"
[[ -f "$old_unit" ]] && move "$old_unit" "$new_unit"

# --- 3. rewrite file contents --------------------------------------------------
if [[ "$is_git_repo" == true ]]; then
    mapfile -t files < <(git grep -lI -e "$OLD_ARTIFACT_ID" -e "$OLD_GROUP_ID" -e "$OLD_REGISTRY" -- . ':!scripts/init-template.sh' 2>/dev/null || true)
else
    mapfile -t files < <(grep -rlI -e "$OLD_ARTIFACT_ID" -e "$OLD_GROUP_ID" -e "$OLD_REGISTRY" \
        --exclude-dir=.git --exclude-dir=target . 2>/dev/null | grep -v 'scripts/init-template.sh' || true)
fi

for f in "${files[@]}"; do
    [[ -f "$f" ]] || continue
    sed -i.bak \
        -e "s/${OLD_GROUP_ID}/${NEW_GROUP_ID}/g" \
        -e "s/${OLD_ARTIFACT_ID}/${NEW_ARTIFACT_ID}/g" \
        "$f"
    if [[ -n "$NEW_REGISTRY" ]]; then
        sed -i.bak -e "s#${OLD_REGISTRY}#${NEW_REGISTRY}#g" "$f"
    fi
    rm -f "${f}.bak"
    echo "updated $f"
done

echo
echo "Done. Review with 'git diff', then:"
echo "  mvn -B -ntp clean verify"
[[ "$new_pkg_path" != "$old_pkg_path" ]] && echo "  reopen the devcontainer / IDE so the language server picks up the moved package"
[[ -z "$NEW_REGISTRY" ]] && echo "  registry left as '$OLD_REGISTRY' - pass a 3rd argument to update Makefile/k8s/systemd too"
exit 0
