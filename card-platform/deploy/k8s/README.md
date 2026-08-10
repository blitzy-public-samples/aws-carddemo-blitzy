## Applying these manifests

Eleven files describe the platform on a cluster: a namespace, Kafka, PostgreSQL, one
ConfigMap, a Secret template, and six Deployment-and-Service pairs.
[`kustomization.yaml`](kustomization.yaml) lists ten of them and is the entry point.

```bash
# 1. Build the six images AND put them inside the cluster. This script is the only
#    supported way to do step 1: a plain `docker build` leaves the images in this
#    machine's daemon, which is not where a kind or minikube node looks for them.
cd card-platform
deploy/k8s/load-images.sh              # runtime read from the current kubectl context
# deploy/k8s/load-images.sh kind       # or name it: kind, minikube, docker-desktop

# 2. Create the Secrets. 31-secret.example.yaml is a template of refused values and is
#    deliberately not listed in kustomization.yaml; card-platform/docs/onboarding.md
#    documents generating each value.

# 3. Apply everything else.
kubectl apply -k card-platform/deploy/k8s
```

Step 1 is a script rather than a loop copied into this page because building an image and
giving it to a cluster are two different operations, and only one of them is a `docker
build`. `kind` keeps its own containerd image store per node, `minikube` keeps one per
profile, and neither reads this machine's Docker daemon; Docker Desktop is the single case
where they are the same store. The script builds all six from source — each `Dockerfile`
compiles its module in a Java Development Kit 25 builder stage, so nothing has to be
packaged on the host first — then loads them the way the detected runtime requires, reads
the node's image list back, and fails if no `carddemo` image arrived. It finishes by
printing steps 2 and 3, so the apply order comes from the same place as the load.

The script also refuses a tag the manifests do not request. `IMAGE_TAG` is checked against
the `newTag` values in [`kustomization.yaml`](kustomization.yaml), and the run stops if they
differ, because `imagePullPolicy: Never` makes the kubelet run the requested tag or refuse
the Pod: loading some other tag produces the same `ErrImageNeverPull` as loading nothing.
The same check catches `kustomization.yaml` drifting from the project version in
`pom.xml`, which is the tag Compose and the pipeline build. Changing the tag therefore means
changing what the manifests ask for, and the script prints the `kustomize edit set image`
command that does it.

<br/>

## What is verified about the images, and what is not

Two kinds of image appear here, and they carry different guarantees. Stating them apart is
the point of this section: a reader who assumes all eight are pinned the same way is wrong
about six of them.

| Kind | Images | How it is named | What a node verifies |
| :--- | :--- | :--- | :--- |
| Upstream | `apache/kafka` in `10-kafka.yaml`, `postgres` in `20-postgres.yaml` | Tag **and** `@sha256:` digest | The bytes. A digest is content-addressed, so the pull either yields exactly those bytes or fails |
| Platform | the six `carddemo/*` services | Tag only, `1.0.0-SNAPSHOT` | Nothing. The kubelet runs the image an operator loaded under that name |

The six platform images cannot carry a digest as this repository ships. A locally built
image has no manifest digest: `docker inspect` reports an empty `RepoDigests` list until
the image is pushed somewhere, so a digest reference would name something no node could
resolve.

`imagePullPolicy: Never` on those six is a deliberate compensating control rather than an
oversight. This project publishes no image anywhere — no workflow, script or document
pushes to a registry — so `carddemo/<service>` is a Docker Hub name this project does not
own. A policy that permitted a pull would let a node fetch bytes from whoever does own that
name, under a name that reads like ours. `Never` removes that path entirely: the kubelet
runs the image an operator loaded onto the node, or it refuses the Pod and reports why.

What `Never` does **not** do is make the reference immutable. THE SIX PLATFORM TAGS ARE
MUTABLE. An operator who tags different bytes as
`carddemo/authorization-service:1.0.0-SNAPSHOT` on one node gets those bytes, and no
manifest here would notice. That is the residual risk, it is why the six references live in
one place, and it is why the next section exists.

<br/>

## Pinning the six by digest

```bash
# Publish to a registry the deployment owns, then pin what the push reported. DIGEST is the
# sha256 value the push printed, quoted so the shell reads it as one word.
DIGEST='sha256:0000000000000000000000000000000000000000000000000000000000000000'
kustomize edit set image \
  "carddemo/authorization-service=registry.example.internal/carddemo/authorization-service@${DIGEST}"
```

`newTag` and `digest` are mutually exclusive in a Kustomize `images` entry, so replacing one
with the other is the whole edit. Then remove `imagePullPolicy: Never` from that Deployment,
because a digest that is never pulled is never verified.

Signing and admission-time verification are the step after that, and this platform ships
neither: they need a registry, a signing key and an admission controller. Section 0.2.2 of
the plan places production hardening out of scope and section 0.8.5 records the instruction
to keep infrastructure configuration minimal and swappable.
[Publish, sign and verify the six service images](../../docs/suggested-next-tasks.md)
carries the task and names every file it touches.

<br/>

## Related documentation

- [Platform README](../../README.md)
- [Onboarding](../../docs/onboarding.md), which covers the Compose path and every generated value
- [Decision log](../../docs/decision-log.md), the single home for why each choice was made
- [Suggested next tasks](../../docs/suggested-next-tasks.md)
