# AGENTS.md

Instructions for AI coding agents (Copilot, Codex, Claude, Cursor, etc.) working in this repository.

## Project overview

`zulu25-service` is a container-ready Java 25 HTTP service built on Azul Zulu,
with **zero runtime dependencies** — the HTTP layer is the JDK's own
`com.sun.net.httpserver`, requests are served on virtual threads. It also
doubles as a reusable template: `scripts/init-template.sh` renames the
package/groupId/artifactId/image name in one step for anyone starting a new
service from it.

- Package: `no.egk.demo`. Entry point: `no.egk.demo.Application`.
- Prose docs (`README.md`, `docs/PODMAN-VSCODE.md`) are written in Norwegian
  (Bokmål). Code, comments, commit messages, and this file are in English.

## Build, test, run

```bash
mvn clean verify                 # build + full test suite (needs JDK 25 locally)
mvn -Dtest=ServerTest test        # run a single test class
java -jar target/app.jar          # run the jar, listens on :8080
docker build -t zulu25-service:dev .   # or podman build; make image also works
```

- Coverage report after `verify`: `target/site/jacoco/index.html` (JaCoCo, no
  minimum threshold enforced — reporting only).
- Always run `mvn clean verify` before considering a change to `src/` done —
  the `Dockerfile` itself runs the full test suite during `docker build` and
  refuses to produce an image from code that doesn't pass.
- No Maven wrapper is checked in; a system Maven + JDK 25 (Azul Zulu) is
  assumed locally. The container builds (and CI) pin their own JDK.

## Architecture

| Class | Responsibility |
|---|---|
| `Application` | Entry point, env-var config (`PORT`, `SHUTDOWN_DRAIN_SECONDS`, `SHUTDOWN_GRACE_SECONDS`), graceful shutdown (SIGTERM → fail readiness → drain → close listener) |
| `Server` | HTTP routing on `com.sun.net.httpserver`, virtual-thread executor, request size/validation guards |
| `Json` | Deliberately tiny **flat** JSON writer, no library. Non-primitive values are stringified, never recursed into — don't add nested-object support without discussing it first; swap in Jackson instead if real (de)serialization is needed |
| `BuildInfo` | name/version/buildTime injected via Maven resource filtering of `build-info.properties` |
| `Log` | One-line stdout/stderr logging, no framework |

Routes: `GET/HEAD /`, `/healthz`, `/readyz`, `GET/HEAD /api/greet?name=`.
Everything else is 404; wrong methods on `/` and `/api/greet` are 405.

## Testing conventions

One test class per production class, black-box over real HTTP wherever
possible (`ServerTest` starts a real `Server` on an ephemeral port and talks to
it with `java.net.http.HttpClient`). Current suite: `ServerTest`, `JsonTest`,
`BuildInfoTest`, `LogTest`, `SecurityTest` (44 tests total).

`Application` is **intentionally not unit-tested**: `main()` blocks on a
shutdown-hook latch and reads env vars that can't be changed for an
already-running JVM. Don't bolt on reflection-based tests for it — a
subprocess-based integration test (spawn `java -jar app.jar`, send SIGTERM,
assert drain timing) is the right tool if this is ever needed.

**Gotcha:** `java.net.URI`/`URI.create()` reject malformed percent-encoding
(`%zz`, a trailing `%2`, a lone `%`) at construction time, before
`java.net.http.HttpClient` ever sends a byte — and separately, the JDK's own
`com.sun.net.httpserver` rejects such request lines with a clean
`400 Bad Request` before any handler runs. To exercise how the server itself
behaves with genuinely malformed bytes on the wire, write directly to a
`java.net.Socket` (see `SecurityTest.rawGet`) instead of going through
`HttpClient`.

## Security posture

- No runtime dependencies, on purpose (minimal CVE surface).
- `Server.MAX_REQUEST_BODY_BYTES` (64 KiB) caps request-body draining — never
  go back to an unbounded `readAllBytes()` on an untrusted body.
- All query-param decoding goes through `Server.decode()`, which swallows
  `IllegalArgumentException` from malformed percent-encoding instead of
  letting it propagate out of a handler.
- User input only ever reaches the JSON response **body** (via
  `Json.escape()`), never a response header — keep it that way.
- `SecurityTest` is the regression suite for all of the above; extend it
  rather than deleting assertions when touching `Server`.

## Container tooling: stay engine-agnostic

This project deliberately does not force Docker vs. Podman. Do **not**
reintroduce Podman-specific config — e.g. `--userns=keep-id` in
`devcontainer.json`'s `runArgs`, or `containers.containerClient` /
`docker.dockerPath` overrides in `.vscode/settings.json` — without an explicit
request; both break under a plain Docker host. The devcontainer uses the
official `ghcr.io/devcontainers/features/docker-outside-of-docker` feature
plus `.devcontainer/link-container-socket.sh`, which probes several known
engine socket paths (Docker, rootless Docker, Podman, Colima) generically.
`docs/PODMAN-VSCODE.md` is an optional how-to guide, not the default path.

## CI/CD

- `.github/workflows/ci.yml`: `test` (verify + JaCoCo artifact) → `image`
  (multi-arch build/push to GHCR, SBOM, Trivy scan → Security tab, cosign
  keyless signing — all skipped on `pull_request`, since no image is pushed
  then) → `release` (GitHub Release on `v*` tags).
- `.github/workflows/codeql.yml`: Java SAST on push/PR/weekly schedule.
- `.github/workflows/deploy.yml`: **manual only** (`workflow_dispatch`) CD
  scaffolding — patches the image tag and runs `kubectl apply -k deploy/k8s`.
  Not wired to a real cluster by default; needs a `KUBE_CONFIG` secret and a
  `production` environment configured before it does anything.
- `.github/dependabot.yml`: weekly updates for maven / github-actions / docker
  (root + `.devcontainer`) ecosystems.

## Repository conventions

- Plugin and GitHub Action versions are pinned intentionally for reproducible
  builds — verify a version actually exists (check the project's own
  releases/changelog) before bumping it; don't guess version numbers,
  especially for CI-critical actions.
- `.gitignore` must keep `.vscode/` tracked — it's shared team config
  (tasks, launch config, recommended extensions), not personal settings. This
  was a real bug once; don't reintroduce it.
- Comments state what the code can't show on its own, kept to one line; this
  codebase does not use multi-paragraph doc comments.
- To rebrand this template for a new project, use
  `bash scripts/init-template.sh <groupId> <artifactId> [<registry>]` rather
  than manually hunting down every occurrence of `no.egk.demo` / `zulu25-service`.
