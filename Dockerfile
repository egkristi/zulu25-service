# syntax=docker/dockerfile:1
#
# Multi-stage build on Azul Zulu 25 (LTS).
#
#   docker build -t zulu25-service:1.0.0 .      (or: podman build ...)
#   docker run --rm -p 8080:8080 zulu25-service:1.0.0
#
# All base images are ARGs so an internal registry / mirror can be injected:
#   docker build --build-arg JDK_IMAGE=registry.internal/azul-zulu:25 ... .
#
# Podman reads this file as-is (it looks for Containerfile first, then Dockerfile).

# Docker Official Image, maintained by Azul: azul-zulu:25 == 25-jdk-debian13.
# Names are fully qualified so that Podman does not have to resolve short names.
ARG JDK_IMAGE=docker.io/library/azul-zulu:25
ARG JRE_IMAGE=docker.io/library/azul-zulu:25-jre-headless
# Only the Maven distribution is taken from this image - it is pure Java and
# JDK-agnostic, so the compile itself still runs on Zulu 25.
ARG MAVEN_IMAGE=docker.io/library/maven:3.9-eclipse-temurin-21


# ---------------------------------------------------------------- maven dist
FROM ${MAVEN_IMAGE} AS maven-dist


# -------------------------------------------------------------------- build
FROM ${JDK_IMAGE} AS builder

COPY --from=maven-dist /usr/share/maven /opt/maven
ENV PATH="/opt/maven/bin:${PATH}"

WORKDIR /workspace

# Resolve dependencies first: this layer is only invalidated by pom.xml changes.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src

# Tests run as part of the image build - the image cannot be produced from
# code that does not pass. Override with --build-arg SKIP_TESTS=true.
ARG SKIP_TESTS=false
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests=${SKIP_TESTS} clean verify && \
    test -f target/app.jar


# ------------------------------------------------------------------ runtime
FROM ${JRE_IMAGE} AS runtime

ARG APP_UID=10001
ARG APP_VERSION=1.0.0-SNAPSHOT
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="zulu25-service" \
      org.opencontainers.image.description="Java 25 service on Azul Zulu" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.base.name="azul-zulu:25-jre-headless"

# -XX:MaxRAMPercentage makes the heap follow the container memory limit instead
# of the node's total RAM. ExitOnOutOfMemoryError lets the orchestrator restart
# the pod rather than leaving a wedged JVM behind.
ENV APP_HOME=/app \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

RUN groupadd --system --gid ${APP_UID} app && \
    useradd --system --uid ${APP_UID} --gid app --home-dir ${APP_HOME} --shell /usr/sbin/nologin app && \
    mkdir -p ${APP_HOME} && \
    chown app:app ${APP_HOME}

WORKDIR ${APP_HOME}
COPY --from=builder --chown=app:app /workspace/target/app.jar ${APP_HOME}/app.jar

USER app
EXPOSE 8080

# No HEALTHCHECK on purpose: the -jre-headless image has neither curl nor wget,
# and in Kubernetes the kubelet owns liveness/readiness anyway (see deploy/k8s).
# For plain Docker Compose, use the healthcheck defined in docker-compose.yml.

# exec so the JVM becomes PID 1 and receives SIGTERM directly.
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
