SHELL := /bin/bash

# Container engine: docker or podman (make image ENGINE=podman)
ENGINE     ?= docker
COMPOSE    ?= $(ENGINE) compose

APP        ?= zulu25-service
VERSION    ?= $(shell grep -m1 '<version>' pom.xml | sed -E 's/.*<version>(.*)<\/version>.*/\1/')
REGISTRY   ?= ghcr.io/egkristi
IMAGE      ?= $(REGISTRY)/$(APP)
TAG        ?= $(VERSION)
PLATFORMS  ?= linux/amd64,linux/arm64
VCS_REF    ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo unknown)
BUILD_DATE ?= $(shell date -u +%Y-%m-%dT%H:%M:%SZ)
PORT       ?= 8080

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: ## Build the jar locally (needs a local JDK 25)
	mvn -B -ntp clean verify

.PHONY: test
test: ## Run the tests only
	mvn -B -ntp test

.PHONY: run
run: build ## Run the jar locally
	java -jar target/app.jar

.PHONY: image
image: ## Build the container image
	$(ENGINE) build \
		--build-arg APP_VERSION=$(VERSION) \
		--build-arg VCS_REF=$(VCS_REF) \
		--build-arg BUILD_DATE=$(BUILD_DATE) \
		-t $(IMAGE):$(TAG) -t $(IMAGE):latest .

.PHONY: image-slim
image-slim: ## Build the jlink'ed (slim) image
	$(ENGINE) build -f Dockerfile.jlink -t $(IMAGE):$(TAG)-slim .

.PHONY: image-multiarch
image-multiarch: ## Build and push amd64 + arm64 (docker buildx only)
	docker buildx build --platform $(PLATFORMS) \
		--build-arg APP_VERSION=$(VERSION) \
		--build-arg VCS_REF=$(VCS_REF) \
		--build-arg BUILD_DATE=$(BUILD_DATE) \
		-t $(IMAGE):$(TAG) --push .

.PHONY: run-image
run-image: image ## Run the container and expose it on $(PORT)
	$(ENGINE) run --rm -p $(PORT):8080 --name $(APP) $(IMAGE):$(TAG)

.PHONY: smoke
smoke: ## Hit the endpoints of a running container
	curl -fsS http://localhost:$(PORT)/ | head -c 400; echo
	curl -fsS http://localhost:$(PORT)/healthz; echo
	curl -fsS "http://localhost:$(PORT)/api/greet?name=Erling"; echo

.PHONY: push
push: image ## Push the image
	$(ENGINE) push $(IMAGE):$(TAG)

.PHONY: scan
scan: image ## Scan the image for known vulnerabilities
	docker scout cves $(IMAGE):$(TAG) 2>/dev/null || trivy image $(IMAGE):$(TAG)

.PHONY: up
up: ## Start via compose (docker compose / podman compose)
	$(COMPOSE) up --build

.PHONY: down
down: ## Stop the compose stack
	$(COMPOSE) down

.PHONY: image-podman
image-podman: ## Build the image with rootless podman
	$(MAKE) image ENGINE=podman

.PHONY: run-podman
run-podman: ## Build and run with rootless podman on $(PORT)
	$(MAKE) run-image ENGINE=podman

.PHONY: deploy
deploy: ## Apply the Kubernetes manifests to the current context
	kubectl apply -k deploy/k8s

.PHONY: undeploy
undeploy: ## Remove the Kubernetes resources
	kubectl delete -k deploy/k8s --ignore-not-found

.PHONY: clean
clean: ## Remove build output
	mvn -B -ntp clean
	$(ENGINE) rmi $(IMAGE):$(TAG) $(IMAGE):latest 2>/dev/null || true
