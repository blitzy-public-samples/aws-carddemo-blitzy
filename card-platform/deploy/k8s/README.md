## Applying these manifests

Eleven files describe the platform on a cluster: a namespace, Kafka, PostgreSQL, one
ConfigMap, a Secret template, and six Deployment-and-Service pairs.
[`kustomization.yaml`](kustomization.yaml) lists ten of them and is the entry point.

```bash
# 1. Build and load the six images on every node that will run a Pod.
mvn -f card-platform/pom.xml -DskipTests package
for service in authorization-service ledger-posting-service fraud-detection-service \
               notification-service account-service card-service; do
  docker build -f "card-platform/services/${service}/Dockerfile" \
    -t "carddemo/${service}:1.0.0-SNAPSHOT" "card-platform/services/${service}"
done

# 2. Create the Secrets. 31-secret.example.yaml is a template of refused values and is
#    deliberately not listed in kustomization.yaml; card-platform/docs/onboarding.md
#    documents generating each value.

# 3. Apply everything else.
kubectl apply -k card-platform/deploy/k8s
```

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
# Publish to a registry the deployment owns, then pin what the push reported.
kustomize edit set image \
  carddemo/authorization-service=registry.example.internal/carddemo/authorization-service@sha256:<digest>
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
