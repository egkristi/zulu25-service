# Kom i gang med Podman og VS Code

Denne guiden tar deg fra tomt skrivebord til et kjørende `azul-zulu:25`-image
bygget med rootless Podman, og et VS Code-oppsett som debugger både lokalt og
inn i containeren.

Alt her fungerer like godt med Docker – bytt `podman` mot `docker`, eller kjør
`make image ENGINE=docker`. Podman er default i eksemplene fordi det er rootless
og daemonløst, som gjør det til et naturlig valg på en jobb-laptop med
sikkerhetspolicy.

---

## 1. Installer Podman

**macOS**

```bash
brew install podman
podman machine init --cpus 4 --memory 4096 --disk-size 60
podman machine start
podman info | head -20
```

Podman på macOS kjører en Linux-VM (`podman machine`). Alle bygg skjer inne i
den, så CPU/minne du gir maskinen er det byggene faktisk har.

**Fedora / RHEL / AlmaLinux**

```bash
sudo dnf install -y podman podman-compose
```

**Debian / Ubuntu**

```bash
sudo apt-get install -y podman podman-compose
```

**Windows**

```powershell
winget install RedHat.Podman
podman machine init
podman machine start
```

Sjekk at rootless faktisk er rootless:

```bash
podman info --format '{{.Host.Security.Rootless}}'   # -> true
```

---

## 2. Bygg og kjør prosjektet

```bash
cd zulu25-service

podman build -t zulu25-service:1.0.0 .
podman run --rm -p 8080:8080 --name zulu25-service zulu25-service:1.0.0
```

I et annet vindu:

```bash
curl localhost:8080/
curl localhost:8080/healthz
curl "localhost:8080/api/greet?name=Erling"
```

Via `make`:

```bash
make image ENGINE=podman      # bygg med podman
make run-image ENGINE=podman  # bygg + kjør på :8080
make smoke                    # treff endepunktene
```

Podman leter etter `Containerfile` først og faller tilbake på `Dockerfile`, så
filen i repoet brukes uendret. Multi-stage, `ARG`-er og
`RUN --mount=type=cache` støttes av Buildah-backenden.

### Fire ting som pleier å overraske folk på Podman

**Korte image-navn.** Podman nekter å gjette registry. Derfor er base-imagene i
`Dockerfile` fullkvalifiserte (`docker.io/library/azul-zulu:25`). Skriver du
egne Containerfiles med korte navn får du en interaktiv `short-name resolution`-prompt,
som stopper CI-bygg. Fiks: bruk fullt navn, eller legg inn alias i
`/etc/containers/registries.conf`.

**Porter under 1024.** Rootless kan ikke binde privilegerte porter uten
`sysctl net.ipv4.ip_unprivileged_port_start=80`. Tjenesten lytter på 8080, så
dette er ikke et problem her – men det er derfor du ikke skal endre den til 80.

**SELinux på Fedora/RHEL.** Bind mounts må merkes, ellers får containeren
`Permission denied`:

```bash
podman run --rm -v "$PWD":/workspace:Z docker.io/library/azul-zulu:25 ls /workspace
```

`:z` deler labelen mellom flere containere, `:Z` gir eksklusiv label.

**UID-mapping.** Imaget kjører som UID 10001. Rootless mapper den inn i ditt
eget user namespace, så filer skrevet til en mountet katalog eies av en høy
subuid på hosten. Bruk `--userns=keep-id` når du trenger at container-brukeren
matcher din egen UID.

### Compose

`docker-compose.yml` fungerer med begge:

```bash
podman compose up --build     # Podman 5.x, delegerer til compose-provider
podman-compose up --build     # eldre standalone-verktøy
make up                       # bruker $(COMPOSE), default "docker compose"
make up COMPOSE="podman compose"
```

### Kjør manifestene lokalt

Podman kan spille av Kubernetes-YAML direkte – nyttig for å sjekke at probes og
`securityContext` faktisk holder før du treffer et ekte cluster:

```bash
podman kube play deploy/k8s/deployment.yaml
podman kube play --down deploy/k8s/deployment.yaml
```

Merk at manifestet peker på `ghcr.io/egkristi/zulu25-service`. Skal du kjøre et
lokalt bygget image, tagg det likt og bruk `imagePullPolicy: IfNotPresent`
(allerede satt), eller pek `image:` mot `localhost/zulu25-service:1.0.0`.
PodDisruptionBudget og Kustomize-overlay ignoreres av `kube play`.

### Kjør som systemd-tjeneste (Quadlet)

Skal tjenesten leve på en boks uten Kubernetes, er Quadlet den moderne veien –
ingen `podman generate systemd`, bare en unit-fil:

```bash
mkdir -p ~/.config/containers/systemd
cp deploy/systemd/zulu25-service.container ~/.config/containers/systemd/
systemctl --user daemon-reload
systemctl --user start zulu25-service
journalctl --user -u zulu25-service -f
loginctl enable-linger "$USER"      # overlev utlogging
```

---

## 3. Sett opp VS Code

### Utvidelser

Åpne mappa i VS Code og godta anbefalingene fra `.vscode/extensions.json`:

| Utvidelse                                    | Hva du får                                 |
|----------------------------------------------|--------------------------------------------|
| `vscjava.vscode-java-pack`                    | Språkserver, Maven, debugger, testrunner    |
| `ms-azuretools.vscode-containers`              | Bygg/kjør/inspiser containere fra editoren  |
| `ms-vscode-remote.remote-containers`           | Dev Containers                              |
| `ms-kubernetes-tools.vscode-kubernetes-tools`  | Manifester, kontekster, logger              |
| `redhat.vscode-yaml`, `redhat.vscode-xml`      | Schema-validering av YAML og `pom.xml`      |

### Java 25 som runtime

Java-utvidelsen trenger en JDK 25 lokalt. Enkleste vei:

```bash
sdk install java 25-zulu     # SDKMAN
sdk use java 25-zulu
```

`.vscode/settings.json` peker `JavaSE-25` mot `${env:JAVA_HOME}`. Har du flere
JDK-er installert, bytt til absolutt sti – typisk
`~/.sdkman/candidates/java/25-zulu`, `/usr/lib/jvm/zulu25` eller
`C:\Program Files\Zulu\zulu-25`. Kjør `Java: Clean Java Language Server Workspace`
fra kommandopaletten etter endring.

### Peke VS Code mot Podman i stedet for Docker

Repoet tvinger ikke noe motorvalg her – `.vscode/settings.json` har bevisst
ingen `containers.containerClient`/`docker.dockerPath`. Vil du at VS Code alltid
skal bruke Podman i stedet for å plukke opp `docker` fra PATH, legg selv til:

```jsonc
"containers.containerClient": "com.microsoft.visualstudio.containers.podman",
"docker.dockerPath": "podman",
"dev.containers.dockerPath": "podman",
"dev.containers.dockerComposePath": "podman-compose"
```

`containers.*` gjelder den nye Container Tools-utvidelsen, `docker.*` den eldre
Docker-utvidelsen – sett begge så virker det uansett hvilken du har. Skal du
bruke containeren fra en dev container også, se "Bygge/kjøre containerimaget
fra inne i dev containeren" lenger ned – samme socket-mekanisme gjelder der.

### Kjøre og debugge

**Lokalt (raskest):** F5 → *Run zulu25-service*. Setter `PORT=8080` og
`SHUTDOWN_DRAIN_SECONDS=0` så omstart går kjapt. Breakpoints virker som normalt.

**Tester:** Testing-panelet lister `ServerTest`. Enkelttester kjøres med
kodelinsen over metoden.

**Inne i containeren:** Ctrl/Cmd+Shift+P → *Tasks: Run Task* →
`podman: run image with debug port`. Den starter imaget med JDWP på 5005 –
JVM-flagget injiseres via `JAVA_OPTS`, som `ENTRYPOINT` allerede plukker opp.
Deretter F5 → *Attach to container (port 5005)*.

> Aldri la JDWP stå på i produksjon: `address=*:5005` åpner for hvem som helst
> som når porten. Den hører hjemme i en lokal task, ikke i en Deployment.

Andre tasks: `maven: verify` (default byggeoppgave, Ctrl/Cmd+Shift+B),
`podman: build image`, `podman: run image`, `podman: stop`.

### Dev container (valgfritt)

Vil du ha hele toolchainen i containeren i stedet for på laptopen, ligger den i
`.devcontainer/` – Zulu 25 JDK + Maven, samme Maven-triks som produksjonsbygget.
Ctrl/Cmd+Shift+P → *Dev Containers: Reopen in Container*.

Dette virker med Docker eller Podman som vertsmotor uten noen endring i
`devcontainer.json` – se neste avsnitt. På SELinux-systemer sørger `Z`-flagget
i `workspaceMount` for riktig labeling. Går det galt, sjekk
*Dev Containers: Show Container Log*.

### Bygge/kjøre containerimaget fra inne i dev containeren

Dev containeren har kun Java/Maven-verktøyet – den kjører ikke sin egen
container-motor. Nøstede containere (motor-i-motor) er skjøre: rootless-i-rootless,
cgroups v2 og lagringsdrivere virker ikke alltid i et nøstet miljø, og å anta én
bestemt motor der ville gjort dev containeren opinionert igjen.

I stedet er dette satt opp som *Docker-outside-of-Docker*
(`ghcr.io/devcontainers/features/docker-outside-of-docker` i `devcontainer.json`):
den installerer kun `docker`-klienten og en bind-mount som peker mot en socket.
`docker build`/`docker run` (eller `podman --remote` om du foretrekker det) kjørt
fra en integrert terminal i dev containeren snakker dermed med motoren på
hosten – hvilken motor det faktisk er, er irrelevant så lenge den eksponerer en
Docker-API-kompatibel socket.

Forutsetninger på hosten (Docker trenger normalt ingenting ekstra; Podman må
eksplisitt eksponere en socket):

```bash
# Linux, med Podman
systemctl --user enable --now podman.socket

# macOS, med Podman
podman machine start
```

`.devcontainer/link-container-socket.sh` kjører automatisk før containeren
startes (`initializeCommand`) og prøver en liste kjente socket-stier – Docker
Desktop, rootless Docker, Podman, Colima – i tur og orden, og peker en
repo-lokal, gitignored symlink (`.devcontainer/.container-socket/docker.sock`)
mot den første som faktisk finnes. Finner den ingen, bygger dev containeren
fortsatt – `docker` inni den feiler bare med en tydelig tilkoblingsfeil
(`postCreateCommand` varsler om dette), og du faller tilbake til å bygge på
hosten som vanlig.

Merk: siden motoren kjører på hosten, havner bygde images og kjørende
containere også der – `docker ps`/`podman ps` på hosten viser dem, og
`-p 8080:8080` publiserer på hostens nettverk (på macOS videreformidlet gjennom
Docker Desktop eller `podman machine` som vanlig). Se også feature-siden sine
begrensninger rundt bind mounts av arbeidsmappen – de gjelder ikke her siden
`docker build .` strømmer build-konteksten i stedet for å referere en sti.

---

## 4. Neste steg

- `make deploy` treffer det clusteret `kubectl config current-context` peker på.
- Legg `deploy/k8s` inn i et Flux-repo; `kustomization.yaml` har allerede
  `images:`-blokken image automation patcher.
- CI-workflowen i `.github/workflows/ci.yml` bygger og pusher til GHCR med
  buildx. Skal CI kjøre Podman i stedet, bytt build-steget mot
  `podman build` + `podman push` – ingenting i `Dockerfile` må endres.
