# Security Policy

## Supported versions

There are no long-term-support branches yet - security fixes are made only
against the latest code on `main`. If you use `scripts/init-template.sh` to
start your own service from this template, the same policy applies to your
fork: only your latest code is covered by whatever you promise your users.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Preferred: use GitHub's private vulnerability reporting for this repository:

<https://github.com/egkristi/zulu25-service/security/advisories/new>

This opens a private draft security advisory visible only to the maintainer
and you - no need to publish details or a contact email publicly. If you
cannot access that page, open a regular issue that only says you have a
security report to make (no details, no PoC) and a private channel will be
arranged from there.

Please include, where relevant:

- The affected file(s), endpoint(s), or container image
- The exact request/input that triggers the issue and the observed vs.
  expected behavior
- The commit SHA or version you tested against
- The impact you'd expect (e.g. denial of service, information disclosure)

## Scope

- In scope: this repository's own code (`src/`, `Dockerfile*`, GitHub Actions
  workflows, Kubernetes manifests under `deploy/`).
- Out of scope: vulnerabilities in upstream base images (Azul Zulu, Debian) or
  the JDK itself - these are tracked automatically via Dependabot and the
  Trivy scan in `.github/workflows/ci.yml` and should be reported upstream
  unless this project is misusing them.

See `AGENTS.md`'s "Security posture" section for the current hardening
measures (request-body size caps, malformed-input handling, JSON escaping,
security headers) covered by `SecurityTest`.

## Response

This is a personal/template project without a dedicated security team, so
there's no fixed SLA. Reports are triaged as they come in; confirmed
vulnerabilities are fixed and disclosed via a GitHub Security Advisory once a
patch is available.
