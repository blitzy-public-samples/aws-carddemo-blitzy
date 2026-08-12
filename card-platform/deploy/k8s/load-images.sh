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
# cluster.
#
# IMAGE_TAG names the tag to build and load. It is checked rather than simply obeyed,
# because building a tag the manifests do not request loads an image no Pod will ever run:
# the kubelet asks for the tag kustomization.yaml sets and refuses anything else under
# imagePullPolicy: Never, so the cluster reports ErrImageNeverPull exactly as it would have
# with no images at all. This script therefore refuses an IMAGE_TAG the manifests do not
# name, and prints the `kustomize edit set image` command that changes what they ask for.
# Left unset, the tag is the version in card-platform/pom.xml, the one value the manifests,
# Compose and the pipeline all name; the script checks that the manifests still agree with
# it rather than assuming they do.
#
# Re-running is safe: each image is rebuilt and replaced in the cluster. A Deployment
# already running an older copy needs a restart to pick the new one up, and the command
# for that is printed at the end.
#
# Docker is the only tool this script requires. Each image compiles its own module inside a
# builder stage, so no Java Development Kit and no Maven installation has to be present on
# this machine.
#
# Design decisions for the choices here: card-platform/docs/decision-log.md
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

project_version="$(grep -m1 -o '<version>[^<]*</version>' pom.xml | sed 's|</\{0,1\}version>||g')"
[ -n "${project_version}" ] \
    || fail "cannot read the project version from ${platform_root}/pom.xml"

# The tag the manifests actually request. kustomization.yaml is the one place the six
# platform image references live, so its newTag is what the kubelet will ask for.
kustomization="deploy/k8s/kustomization.yaml"
[ -f "${kustomization}" ] || fail "${platform_root}/${kustomization} is missing"
requested_tags="$(sed -n 's/^ *newTag: *//p' "${kustomization}" | sort -u)"
requested_tag_count="$(printf '%s\n' "${requested_tags}" | grep -c .)"
if [ "${requested_tag_count}" -ne 1 ]; then
    fail "the six entries in ${kustomization} request ${requested_tag_count} different tags:
$(printf '%s\n' "${requested_tags}" | sed 's/^/  /')
Give all six the same newTag, or pin them by digest, before loading images."
fi
manifest_tag="${requested_tags}"

if [ "${manifest_tag}" != "${project_version}" ]; then
    fail "${kustomization} requests '${manifest_tag}' but pom.xml declares '${project_version}'.
Compose and the pipeline both build '${project_version}', so the cluster would ask for a tag
nothing produces. Bring them back into step:

  cd ${platform_root}/deploy/k8s
  for service in ${services[*]}; do
    kustomize edit set image \"carddemo/\${service}=carddemo/\${service}:${project_version}\"
  done"
fi

image_tag="${IMAGE_TAG:-${manifest_tag}}"
if [ "${image_tag}" != "${manifest_tag}" ]; then
    fail "IMAGE_TAG is '${image_tag}' but the manifests request '${manifest_tag}'.
Loading '${image_tag}' would leave every Pod reporting ErrImageNeverPull, because
imagePullPolicy: Never makes the kubelet run the requested tag or refuse the Pod. Change what
the manifests ask for first, then run this again with the same value:

  cd ${platform_root}/deploy/k8s
  for service in ${services[*]}; do
    kustomize edit set image \"carddemo/\${service}=carddemo/\${service}:${image_tag}\"
  done"
fi
note "building and loading tag ${image_tag}, which ${kustomization} requests"

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

# Each Dockerfile builds its module from source in a Java Development Kit 25 builder stage,
# so nothing has to be packaged on this machine first and no Maven installation is required
# here at all. The context is this directory rather than the module directory, because that
# builder needs the aggregator descriptor and both shared libraries in reach;
# services/<service>/Dockerfile.dockerignore reduces what is sent to exactly that.
#
# BuildKit is named explicitly because each builder stage mounts a cache for the local Maven
# repository. The first build populates it and the other five resolve from it, so six
# in-image builds cost one dependency download rather than six.
for service in "${services[@]}"; do
    note "building carddemo/${service}:${image_tag} from source"
    DOCKER_BUILDKIT=1 docker build \
        --file "services/${service}/Dockerfile" \
        --tag "carddemo/${service}:${image_tag}" \
        .
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

# The six application Deployments by name. `rollout restart deployment` with no argument
# restarts every Deployment in the namespace, including the broker and the database.
restart_targets=""
for service in "${services[@]}"; do
    restart_targets="${restart_targets:+${restart_targets} }deployment/${service}"
done

cat <<APPLY

load-images: the images are in the cluster. Apply the manifests in the order
00-namespace.yaml documents, from ${platform_root}:

  1. kubectl apply -f deploy/k8s/00-namespace.yaml

  2. The credential Secrets, from a filled-in copy kept OUTSIDE this folder:

       cp deploy/k8s/31-secret.example.yaml ~/carddemo-secrets.yaml
       # replace every REPLACE value in ~/carddemo-secrets.yaml, then
       kubectl apply -f ~/carddemo-secrets.yaml

  3. Everything else, through the kustomization:

       kubectl apply -k deploy/k8s

     kustomization.yaml lists the ten manifests to apply and omits
     31-secret.example.yaml, so this cannot overwrite step 2 with the placeholders
     that template publishes. It also cannot try to apply kustomization.yaml
     itself, which is not a Kubernetes API object.

load-images: after a rebuild, restart the six application Deployments so they pick
the new image up. Kafka and PostgreSQL are named out deliberately: restarting the
whole namespace would cycle the broker and the database as well, which drops every
consumer group and every open connection for no reason.

  kubectl -n carddemo rollout restart ${restart_targets}
APPLY
