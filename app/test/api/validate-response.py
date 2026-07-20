#!/usr/bin/env python3
# CardDemo REST/JSON API - single-response OpenAPI schema validator.
#
# Validates ONE captured HTTP response body against the exact schema that
# app/api/openapi.yaml declares for a given (path, method, status). It is the
# schema-validation engine invoked by run-api-tests.sh after every request so
# the harness can no longer let a response that violates the published contract
# (an extra field, a missing required field, a wrong type, a bad pattern) pass
# silently. The 200 responses resolve to their components.schemas.*Response
# envelope; the 400/401/404/500 responses resolve through the shared
# components.responses.* entries to the ErrorResponse envelope.
#
# It is intentionally dependency-light: standard library plus PyYAML and
# jsonschema (and, when present, the modern "referencing" API used by
# jsonschema >= 4.18). It performs no network access and never installs
# packages. If an optional package - or the spec - is unavailable it prints a
# single SKIP line and exits 0 so it never blocks a minimal build, UNLESS
# --release is passed, in which case the same condition is a hard FAIL (exit 3).
#
# Licensed under the Apache License, Version 2.0:
#   http://www.apache.org/licenses/LICENSE-2.0
"""Validate a single CardDemo API response body against the OpenAPI contract.

Usage::

    validate-response.py --spec app/api/openapi.yaml \\
        --path /accounts/{acctId} --method GET --status 200 \\
        --body /tmp/body.json [--release]

The ``--path`` value is the OpenAPI path TEMPLATE (with ``{param}``
placeholders), not the concrete request URL. Exit codes: ``0`` the body
validates (or a graceful SKIP); ``1`` the body violates the declared schema;
``2`` the (path, method, status) has no resolvable application/json schema or
the body is not JSON; ``3`` a required dependency/spec is missing under
``--release``.
"""

import argparse
import json
import sys
import warnings


# --- Optional dependencies (graceful, mirrors openapi-contract-test.py) -----
try:
    import yaml
except Exception:  # pragma: no cover - exercised only when PyYAML is absent
    yaml = None

try:
    import jsonschema  # noqa: F401  (imported to confirm availability)
    from jsonschema import Draft7Validator
except Exception:  # pragma: no cover - exercised only when jsonschema absent
    jsonschema = None
    Draft7Validator = None


# --- Version-robust $ref validators (identical strategy to the contract test)-
def validator_for(ref, root):
    """Return a ``Draft7Validator`` for a component ``$ref`` resolved against
    ``root`` (the full parsed OpenAPI document) so nested ``$ref`` chains
    resolve. Tries the modern ``referencing`` registry (jsonschema >= 4.18)
    first, then falls back to the legacy ``RefResolver``.
    """
    try:
        from referencing import Registry, Resource
        from referencing.jsonschema import DRAFT7

        resource = Resource.from_contents(root, default_specification=DRAFT7)
        registry = Registry().with_resource(uri="urn:spec", resource=resource)
        return Draft7Validator(
            {"$ref": "urn:spec#" + ref[1:]}, registry=registry
        )
    except Exception:
        pass

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        from jsonschema import RefResolver

        resolver = RefResolver.from_schema(root)
        return Draft7Validator({"$ref": ref}, resolver=resolver)


def validator_for_inline(schema, root):
    """Return a ``Draft7Validator`` for an INLINE ``schema`` object, resolving
    nested component ``$ref``s against ``root``. Mirrors
    :func:`validator_for`'s dual strategy.
    """
    try:
        from referencing import Registry, Resource
        from referencing.jsonschema import DRAFT7

        resource = Resource.from_contents(root, default_specification=DRAFT7)
        registry = Registry().with_resource(uri="urn:spec", resource=resource)
        return Draft7Validator(schema, registry=registry)
    except Exception:
        pass

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        from jsonschema import RefResolver

        resolver = RefResolver.from_schema(root)
        return Draft7Validator(schema, resolver=resolver)


def resolve_response_schema(spec, path, method, status):
    """Resolve ``paths[path][method].responses[status]`` to its
    ``application/json`` schema object, following a response-level ``$ref``
    into ``components.responses`` when present (as the 4xx/5xx responses do).

    Returns ``(schema, None)`` on success or ``(None, reason)`` when no
    application/json schema is declared for that (path, method, status).
    """
    paths = spec.get("paths", {})
    item = paths.get(path)
    if not isinstance(item, dict):
        return None, "path %r not declared in spec" % path
    operation = item.get(method.lower())
    if not isinstance(operation, dict):
        return None, "method %s not declared for path %r" % (method, path)
    responses = operation.get("responses", {})
    # OpenAPI keys response status codes as strings.
    response = responses.get(str(status))
    if response is None:
        return None, "status %s not declared for %s %s" % (status, method, path)

    # A response may itself be a $ref into components.responses (the shared
    # BadRequest / Unauthorized / NotFound / InternalError error responses).
    if isinstance(response, dict) and "$ref" in response:
        ref = response["$ref"]
        prefix = "#/components/responses/"
        if not ref.startswith(prefix):
            return None, "unexpected response $ref %r" % ref
        name = ref[len(prefix):]
        response = spec.get("components", {}).get("responses", {}).get(name)
        if not isinstance(response, dict):
            return None, "components.responses.%s not found" % name

    schema = (response.get("content", {})
              .get("application/json", {})
              .get("schema"))
    if not isinstance(schema, dict):
        return None, ("no application/json schema for %s %s %s"
                      % (method, path, status))
    return schema, None


def main():
    ap = argparse.ArgumentParser(
        description="Validate one API response body against the OpenAPI "
                    "schema declared for a (path, method, status).")
    ap.add_argument("--spec", required=True,
                    help="path to app/api/openapi.yaml")
    ap.add_argument("--path", required=True,
                    help="OpenAPI path TEMPLATE, e.g. /accounts/{acctId}")
    ap.add_argument("--method", required=True, help="HTTP method")
    ap.add_argument("--status", required=True, help="expected HTTP status code")
    ap.add_argument("--body", required=True,
                    help="file containing the captured response body")
    ap.add_argument("--release", action="store_true",
                    help="treat a missing dependency/spec as a hard FAIL")
    args = ap.parse_args()

    # Graceful degradation when an optional dependency is unavailable.
    if yaml is None or Draft7Validator is None:
        missing = "PyYAML" if yaml is None else "jsonschema"
        msg = "validate-response: optional dependency %s unavailable" % missing
        if args.release:
            print("FAIL: " + msg + " (required with --release)")
            return 3
        print("SKIP: " + msg)
        return 0

    # Load the spec.
    try:
        with open(args.spec, "r", encoding="utf-8") as handle:
            spec = yaml.safe_load(handle)
    except Exception as exc:
        msg = "validate-response: cannot read spec %s (%s)" % (args.spec, exc)
        if args.release:
            print("FAIL: " + msg + " (required with --release)")
            return 3
        print("SKIP: " + msg)
        return 0

    # Resolve the declared schema for this (path, method, status).
    schema, reason = resolve_response_schema(
        spec, args.path, args.method, args.status)
    if schema is None:
        print("ERROR: " + reason)
        return 2

    # Parse the captured body as JSON.
    try:
        with open(args.body, "r", encoding="utf-8") as handle:
            raw = handle.read()
        instance = json.loads(raw)
    except (OSError, ValueError) as exc:
        print("ERROR: response body is not readable JSON (%s)" % exc)
        return 2

    # Build the validator and validate.
    try:
        if "$ref" in schema:
            validator = validator_for(schema["$ref"], spec)
        else:
            validator = validator_for_inline(schema, spec)
        errors = sorted(validator.iter_errors(instance), key=lambda e: e.path)
    except Exception as exc:
        print("ERROR: %s %s %s schema did not resolve (broken $ref?): %r"
              % (args.method, args.path, args.status, exc))
        return 2

    if errors:
        for err in errors[:8]:
            loc = "/".join(str(p) for p in err.path) or "<root>"
            print("FAIL: %s %s %s body violates schema at %s: %s"
                  % (args.method, args.path, args.status, loc, err.message))
        return 1

    print("PASS: %s %s %s body validates against its declared schema"
          % (args.method, args.path, args.status))
    return 0


if __name__ == "__main__":
    sys.exit(main())
