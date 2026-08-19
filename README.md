# zulu25-service

Et lite, container-klart Java 25-tjenesteprosjekt bygget på **Azul Zulu 25 (LTS)**.
`docker build` gir et ferdig deploybart image – Maven-bygg, tester og runtime er
kjedet sammen i én multi-stage `Dockerfile`.

- **Null runtime-avhengigheter** – HTTP-laget er JDK-ets eget `com.sun.net.httpserver`,
  requests serveres på virtuelle tråder. Lite image, minimal CVE-flate, ingen
  framework-oppstartstid.
- **Kubernetes-klar** – separate liveness/readiness-endepunkter, graceful shutdown
  på SIGTERM, non-root, `readOnlyRootFilesystem`-kompatibel.
- **Byggbar overalt** – base-images er `ARG`-er, så internt registry/mirror kan
  injiseres uten å endre filen.
- **Malprosjekt** – `scripts/init-template.sh` bytter `groupId`/pakke, `artifactId`
  og image-navn i ett steg, klart til å bli et nytt prosjekt.

## Kom i gang

```bash
docker build -t zulu25-service:1.0.0 .
docker run --rm -p 8080:8080 zulu25-service:1.0.0

curl localhost:8080/
curl "localhost:8080/api/greet?name=Erling"
```

Med Podman er det de samme kommandoene – base-imagene er fullkvalifiserte, så
short-name-prompten uteblir:

```bash
podman build -t zulu25-service:1.0.0 .
podman run --rm -p 8080:8080 zulu25-service:1.0.0
```

Eller via `make` (`ENGINE` styrer motoren, default `docker`):

```bash
make help                  # alle targets
make image                 # bygg image med OCI-labels og versjon fra pom.xml
make image ENGINE=podman   # samme bygg med podman
make run-image             # bygg + kjør på :8080
make run-image ENGINE=podman  # bygg + kjør rootless med podman
make smoke                 # treff endepunktene
make deploy                # kubectl apply -k deploy/k8s
```

> **Podman + VS Code:** full oppsettsguide i
> [`docs/PODMAN-VSCODE.md`](docs/PODMAN-VSCODE.md) – installasjon, rootless-fellene
> (short names, SELinux, UID-mapping), `podman kube play`, Quadlet-tjeneste, og
> VS Code med debugging både lokalt og inn i containeren.

Lokalt bygg uten Docker krever JDK 25 på maskinen (`sdk install java 25-zulu`):

```bash
mvn clean verify
java -jar target/app.jar
```

## Endepunkter

| Metode     | Path                | Beskrivelse                                            |
|------------|---------------------|--------------------------------------------------------|
| GET/HEAD   | `/`                 | Tjeneste- og runtime-info som JSON                      |
| GET/HEAD   | `/healthz`          | Liveness – 200 så lenge prosessen lever                 |
| GET/HEAD   | `/readyz`           | Readiness – 200 når klar, 503 under oppstart og drenering |
| GET/HEAD   | `/api/greet?name=`  | Eksempelendepunkt, default `verden`                     |

Alt annet gir 404, andre metoder gir 405 med `Allow: GET, HEAD`.

## Konfigurasjon

Alt styres av miljøvariabler (12-factor), ingen konfigfiler i imaget:

| Variabel                  | Default | Betydning                                                      |
|---------------------------|---------|----------------------------------------------------------------|
| `PORT`                    | `8080`  | Lytteport                                                       |
| `SHUTDOWN_DRAIN_SECONDS`  | `5`     | Tid readiness feiler før lytteren lukkes                        |
| `SHUTDOWN_GRACE_SECONDS`  | `10`    | Maks ventetid på requests som er i flukt                        |
| `JAVA_OPTS`               | se under| JVM-flagg, settes på `ENTRYPOINT`                               |

Default `JAVA_OPTS` er `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8`.
`MaxRAMPercentage` gjør at heapen følger container-grensen i stedet for nodens totale RAM.
GC-valget er overlatt til JVM-ergonomien: under ~2 CPU / 1792 MB velger den SerialGC,
som er riktig for små pods – overstyr med `-XX:+UseG1GC` hvis podden er større.

## Hvordan bygget henger sammen

```
maven-dist   (maven:3.9-*)        -> henter kun Maven-distribusjonen
     |
builder      (azul-zulu:25)       -> mvn verify på Zulu 25 JDK, tester kjører her
     |
runtime      (azul-zulu:25-jre-headless) -> app.jar + non-root bruker
```

Maven-distribusjonen kopieres inn fra det offisielle Maven-imaget
(`COPY --from=maven-dist /usr/share/maven /opt/maven`). Den er ren Java og
JDK-agnostisk, så selve kompileringen skjer fortsatt på Zulu 25 – og vi slipper
`curl`/`apt-get`/checksum-håndtering i byggesteget.

`pom.xml` kopieres og `dependency:go-offline` kjøres før `src/`, slik at
avhengighetslaget kun invalideres når `pom.xml` endres. I tillegg brukes
BuildKit cache mount på `/root/.m2`, så gjentatte bygg ikke laster ned på nytt.

> Bygger du uten BuildKit (`DOCKER_BUILDKIT=0`) må `--mount=type=cache`-linjene
> fjernes. Med Docker 23+, Podman/Buildah eller BuildKit i CI virker det som det står.

### Base-images

`azul-zulu` er Docker Official Image vedlikeholdt av Azul – `azul-zulu:25` er i
praksis `25-jdk-debian13`. Bytt via build-args:

```bash
docker build \
  --build-arg JDK_IMAGE=registry.internal/azul-zulu:25 \
  --build-arg JRE_IMAGE=registry.internal/azul-zulu:25-jre-headless \
  --build-arg MAVEN_IMAGE=registry.internal/maven:3.9-eclipse-temurin-21 \
  -t zulu25-service:1.0.0 .
```

Andre nyttige varianter: `azul-zulu:25-jre-headless-alpine`,
`azul-zulu:25-jre-headless-almalinux10`, eller `azul/zulu-openjdk:25` hvis du
heller vil ha Azuls eget Ubuntu-baserte repo.

### Slankere image med jlink

`Dockerfile.jlink` bygger en egen runtime med kun modulene tjenesten faktisk
bruker, og legger den på `debian:13-slim`:

```bash
docker build -f Dockerfile.jlink -t zulu25-service:1.0.0-slim .
```

Modullisten verifiseres med `jdeps --print-module-deps --ignore-missing-deps target/app.jar`.
Legger du til avhengigheter senere må listen oppdateres.

## Deploy til Kubernetes

```bash
kubectl apply -k deploy/k8s
```

Manifestene i `deploy/k8s` inneholder Deployment (2 replicas, rolling update med
`maxUnavailable: 0`), Service, PodDisruptionBudget og Kustomize-overlay. Podden
kjører non-root som UID 10001, med `readOnlyRootFilesystem`, droppede
capabilities og `seccompProfile: RuntimeDefault` – `/tmp` er en emptyDir.

Bildereferansen ligger i `kustomization.yaml` under `images:`, som er det Flux'
image automation patcher hvis du legger dette inn i et GitOps-repo.

Rekkefølgen ved skalering ned: SIGTERM → `/readyz` gir 503 → 5 sekunders drenering
mens kubelet fjerner podden fra endpoints → lytteren lukkes → in-flight requests
får inntil 10 sekunder. Derfor trengs ingen `preStop`-hook.

Merk at imaget bevisst ikke har `HEALTHCHECK`: `-jre-headless` inneholder verken
`curl` eller `wget`, og i et cluster er det kubelet som eier probene uansett.
For Docker Compose ligger det en `/dev/tcp`-basert healthcheck i `docker-compose.yml`.

## Testing

`ServerTest` starter den ekte serveren på en efemer port og snakker HTTP mot den
med `java.net.http.HttpClient` – ruting, statuskoder og JSON-encoding dekkes ende
til ende. Testene kjører som del av image-bygget, så et image kan ikke produseres
fra kode som ikke passerer. Hopp over ved behov:

```bash
docker build --build-arg SKIP_TESTS=true -t zulu25-service:dev .
```

## CI/CD

`.github/workflows/ci.yml` kjører på hver push/PR mot `main` og på `v*`-tagger:

| Jobb           | Gjør hva                                                                 |
|----------------|---------------------------------------------------------------------------|
| `test`         | `mvn clean verify` på Azul Zulu 25, laster opp `app.jar` som artifact      |
| `image`        | Bygger og pusher multi-arch (`amd64`/`arm64`) image til GHCR med buildx – kun på push, ikke på PR |
| `devcontainer` | Bygger `.devcontainer/Dockerfile` med `@devcontainers/cli` – fanger opp at devcontaineren råtner (apt-pakker, base-image) uten å kreve Podman på runneren |

`devcontainer`-jobben kjører kun `devcontainer build` (image-steget), ikke `up` –
dermed testes aldri containerens kjøretids-mounts (socket-oppsettet) på selve
GitHub-runneren, som uansett bare har Docker.

## Utviklingsmiljø

`.vscode/` inneholder anbefalte utvidelser, Java 25-runtime, launch-config for
lokal kjøring og remote debug (JDWP på 5005), og tasks for `mvn verify` og
podman build/run (bytt `podman` mot `docker` i tasken om du foretrekker det).
`.devcontainer/` gir hele toolchainen i en container basert på `azul-zulu:25`
hvis du heller vil ha den der enn på laptopen – den snakker med hvilken som
helst Docker-API-kompatibel motor på hosten (Docker, Podman, ...) via
Docker-outside-of-Docker, uten å tvinge noe valg.
`deploy/systemd/zulu25-service.container` er en Podman Quadlet-unit for å kjøre
tjenesten som rootless systemd-service uten Kubernetes.

Detaljene ligger i [`docs/PODMAN-VSCODE.md`](docs/PODMAN-VSCODE.md).

## Videre herfra

- **Raskere oppstart:** Java 25 har AOT-cache (Project Leyden). En treningskjøring
  i byggesteget med `-XX:AOTMode=record` / `-XX:AOTCacheOutput` kan kutte
  oppstartstiden markant. Enklere variant: `-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=/tmp/app.jsa`.
- **Logging:** `Log` skriver enlinjes records til stdout. Trenger du MDC eller
  JSON-encoding, bytt klassen mot SLF4J + Logback – resten av koden er uberørt.
- **Observability:** legg på `jdk.jfr`-basert profilering eller et
  Micrometer/OTel-endepunkt hvis tjenesten skal inn i en eksisterende stack.
- **Reproduserbare bygg:** sett `<project.build.outputTimestamp>` i `pom.xml` for
  bit-identiske jar-er mellom bygg.

## Prosjektstruktur

```
.
├── Dockerfile              # multi-stage bygg på Azul Zulu 25
├── Dockerfile.jlink        # valgfri slank runtime
├── Makefile                # build / image / deploy-targets (ENGINE=docker|podman)
├── scripts/init-template.sh # bytt groupId/artifactId/image-navn i ett steg
├── docker-compose.yml
├── pom.xml                 # Java 25, ingen runtime-avhengigheter
├── docs/PODMAN-VSCODE.md   # kom-i-gang med Podman og VS Code
├── .vscode/                # utvidelser, settings, launch, tasks
├── .devcontainer/          # dev container på azul-zulu:25
├── deploy/k8s/             # Deployment, Service, PDB, kustomization
├── deploy/systemd/         # Podman Quadlet-unit
├── .github/workflows/ci.yml
└── src/
    ├── main/java/no/egk/demo/
    │   ├── Application.java   # oppstart, config, graceful shutdown
    │   ├── Server.java        # HTTP-lag, ruter, probes, access-log
    │   ├── Json.java          # minimal JSON-writer
    │   ├── BuildInfo.java     # navn/versjon/byggetid fra Maven-filtrering
    │   └── Log.java           # enlinjes stdout-logging
    ├── main/resources/build-info.properties
    └── test/java/no/egk/demo/ServerTest.java
```

### Bruke som mal

```bash
bash scripts/init-template.sh com.acme.widgets widget-service ghcr.io/acme
```

Scriptet bytter `groupId`/pakkenavn, `artifactId` og image-navnet i ett steg –
Java-pakkestien flyttes, og `pom.xml`, `Makefile`, Kubernetes-manifestene,
Quadlet-unit-filen og `devcontainer.json` oppdateres samtidig. Kjør `git diff`
etterpå og `mvn clean verify` for å bekrefte at alt fortsatt bygger.
Registry-argumentet er valgfritt – uten det beholdes `ghcr.io/egkristi`.
