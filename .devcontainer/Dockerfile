# Dev container: Azul Zulu 25 JDK + Maven + the bits VS Code expects.
# Same Maven trick as the production build - the distribution is pure Java.
ARG JDK_IMAGE=docker.io/library/azul-zulu:25
ARG MAVEN_IMAGE=docker.io/library/maven:3.9-eclipse-temurin-21

FROM ${MAVEN_IMAGE} AS maven-dist

FROM ${JDK_IMAGE}

COPY --from=maven-dist /usr/share/maven /opt/maven
ENV PATH="/opt/maven/bin:${PATH}"

# git and curl are what the Java extension pack and most workflows reach for.
# The docker CLI itself comes from the docker-outside-of-docker feature (see
# devcontainer.json), not from here.
RUN apt-get update && \
    apt-get install -y --no-install-recommends git curl ca-certificates less procps && \
    rm -rf /var/lib/apt/lists/*

# Non-root user. updateRemoteUserUID (devcontainer.json) lines the UID up with the host.
ARG USERNAME=vscode
ARG USER_UID=1000
ARG USER_GID=${USER_UID}
RUN groupadd --gid ${USER_GID} ${USERNAME} && \
    useradd --uid ${USER_UID} --gid ${USER_GID} --create-home --shell /bin/bash ${USERNAME} && \
    mkdir -p /workspace && chown ${USERNAME}:${USERNAME} /workspace

USER ${USERNAME}
WORKDIR /workspace
