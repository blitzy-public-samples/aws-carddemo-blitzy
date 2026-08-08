#!/usr/bin/env bash
#
# Builds the six service images and puts them inside a local Kubernetes cluster.
#
# The six Deployments name carddemo/<service>:<project version> with
# imagePullPolicy: Never, so the kubelet never contacts a registry and the image has to
# already be in the cluster node's own image store. Nothing publishes these images
# anywhere, so a cluster that has not been given them reports ErrImageNeverPull and no pod
# ever starts. That failure names no cause and is the reason this script exists.
#
# Usage:
#   deploy/k8s/load-images.sh              # runtime read from the current kubectl context
#   deploy/k8s/load-images.sh kind
#   deploy/k8s/load-images.sh minikube
#   deploy/k8s/load-images.sh docker-desktop
#
# KIND_CLUSTER_NAME (default kind) and MINIKUBE_PROFILE (default minikube) choose the
# cluster. IMAGE_TAG overrides the tag, which is otherwise the version in
# card-platform/pom.xml, the one value the manifests, Compose and the pipeline all name.
#
# Re-running is safe: each image is rebuilt and replaced in the cluster. A Deployment
# already running an older copy needs a restart to pick the new one up, and the command
# for that is printed at the end.
#
# Rationale for the choices here: card-platform/docs/decision-log.md
# Cluster walkthrough: card-platform/docs/onboarding.md

set -euo pipefail

platform_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${platform_root}"

services=(
    authorization-service
    ledger-posting-service
    fraud-detection-service
    notification-service
    account-service
    card-service
)

note() { printf 'load-images: %s\n' "$1"; }
fail() {
    printf 'load-images: %s\n' "$1" >&2
    exit 1
}

command -v docker >/dev/null 2>&1 || fail "docker is not on the path."
docker info >/dev/null 2>&1 || fail "the Docker daemon is not reachable. Start Docker."

image_tag="${IMAGE_TAG:-}"
if [ -z "${image_tag}" ]; then
    image_tag="$(grep -m1 -o '<version>[^<]*</version>' pom.xml | sed 's|</\{0,1\}version>||g')"
fi
[ -n "${image_tag}" ] || fail "cannot read the project version from ${platform_root}/pom.xml"

runtime="${1:-}"
if [ -z "${runtime}" ]; then
    command -v kubectl >/dev/null 2>&1 \
        || fail "kubectl is not on the path, so the runtime cannot be detected. Name it:
  deploy/k8s/load-images.sh kind | minikube | docker-desktop"
    context="$(kubectl config current-context 2>/dev/null || true)"
    case "${context}" in
        kind-*) runtime="kind" ;;
        minikube*) runtime="minikube" ;;
        docker-desktop*) runtime="docker-desktop" ;;
        *) fail "cannot tell which runtime the context '${context}' is. Name it:
  deploy/k8s/load-images.sh kind | minikube | docker-desktop" ;;
    esac
    note "detected ${runtime} from the kubectl context '${context}'"
fi

case "${runtime}" in
    kind)
        command -v kind >/dev/null 2>&1 || fail "kind is not on the path."
        ;;
    minikube)
        command -v minikube >/dev/null 2>&1 || fail "minikube is not on the path."
        ;;
    docker-desktop) ;;
    *) fail "unknown runtime '${runtime}'. Use kind, minikube or docker-desktop." ;;
esac

# Every Dockerfile copies a packaged archive out of its own module's target/ directory, so
# a missing archive fails the build with a message about a COPY rather than about Maven.
missing_archive="no"
for service in "${services[@]}"; do
    # shellcheck disable=SC2086
    set -- services/"${service}"/target/"${service}"-*.jar
    [ -f "$1" ] || missing_archive="yes"
done
if [ "${missing_archive}" = "yes" ]; then
    note "an archive is missing, so the reactor is packaged first"
    command -v mvn >/dev/null 2>&1 \
        || fail "mvn is not on the path and the archives are missing. Package them first."
    mvn -B -ntp -DskipTests package
fi

for service in "${services[@]}"; do
    note "building carddemo/${service}:${image_tag}"
    docker build \
        --file "services/${service}/Dockerfile" \
        --tag "carddemo/${service}:${image_tag}" \
        "services/${service}"
done

case "${runtime}" in
    kind)
        cluster="${KIND_CLUSTER_NAME:-kind}"
        for service in "${services[@]}"; do
            note "loading carddemo/${service}:${image_tag} into kind cluster ${cluster}"
            kind load docker-image "carddemo/${service}:${image_tag}" --name "${cluster}"
        done
        note "images now in the cluster:"
        docker exec "${cluster}-control-plane" crictl images \
            | grep carddemo || fail "no carddemo image reached the node"
        ;;
    minikube)
        profile="${MINIKUBE_PROFILE:-minikube}"
        for service in "${services[@]}"; do
            note "loading carddemo/${service}:${image_tag} into minikube profile ${profile}"
            minikube image load "carddemo/${service}:${image_tag}" --profile "${profile}"
        done
        note "images now in the cluster:"
        minikube image ls --profile "${profile}" \
            | grep carddemo || fail "no carddemo image reached the node"
        ;;
    docker-desktop)
        # The cluster shares this machine's Docker daemon, so a built image is already
        # visible to the kubelet and there is nothing to transfer.
        note "docker-desktop shares this daemon, so the six builds above are already in reach"
        for service in "${services[@]}"; do
            docker image inspect "carddemo/${service}:${image_tag}" >/dev/null \
                || fail "carddemo/${service}:${image_tag} is not in the local daemon"
        done
        note "all six images are present locally"
        ;;
esac

cat <<APPLY

load-images: the images are in the cluster. Apply the manifests in the order
00-namespace.yaml documents, from ${platform_root}:

  1. kubectl apply -f deploy/k8s/00-namespace.yaml

  2. The credential Secrets, from a filled-in copy kept OUTSIDE this folder:

       cp deploy/k8s/31-secret.example.yaml ~/carddemo-secrets.yaml
       # replace every REPLACE value in ~/carddemo-secrets.yaml, then
       kubectl apply -f ~/carddemo-secrets.yaml

  3. Everything else, in filename order, with the template excluded:

       ls deploy/k8s/*.yaml \\
         | grep -v '31-secret.example.yaml' \\
         | xargs -n1 kubectl apply -f

Applying the whole folder instead would overwrite step 2 with the placeholders the
template publishes, and every workload would refuse to start.

load-images: after a rebuild, restart the workloads so they pick the new image up:

  kubectl -n carddemo rollout restart deployment
APPLY
