# CardDemo Documentation

This directory holds the architecture and reference documentation for the
**modernized** CardDemo application — a Python [FastAPI](https://fastapi.tiangolo.com/)
backend, a [Next.js](https://nextjs.org/) + [Material UI](https://mui.com/)
frontend, a **PostgreSQL 17** database, and a Python batch CLI. These pages carry
the deeper technical detail behind the project. For installation, environment
setup, and the quick-start guide, start with the
[root `../README.md`](../README.md).

## Documents in This Directory

| Document | Contents |
| :------- | :------- |
| [`./architecture.md`](./architecture.md) | Three-tier system architecture, the layered backend (routers → services → repositories → models), the authentication and session model, and the COBOL/CICS/VSAM → modern migration flow. |
| [`./api-reference.md`](./api-reference.md) | REST API endpoints, request/response DTOs, validation rules, and error/message codes. The running backend also serves interactive OpenAPI/Swagger UI at `http://localhost:8000/docs`. |
| [`./data-model.md`](./data-model.md) | The 10 PostgreSQL tables, their keys, indexes, foreign-key relationships, and exact-decimal (`NUMERIC`) precision. |
| [`./batch.md`](./batch.md) | The Python batch CLI: the preserved chain order, per-job descriptions, data loaders, and golden-master parity. |
| [`./traceability.md`](./traceability.md) | Legacy transaction / BMS map / COBOL program / VSAM dataset → modern route / endpoint / service / model / table mapping. |

## Modern Stack at a Glance

| Tier | Technology | Local URL / Port |
| :--- | :--------- | :--------------- |
| Frontend | Next.js 16 + Material UI 9 (React 19) | `http://localhost:3000` |
| Backend API | FastAPI (Python 3.13) | `http://localhost:8000` — REST API under `/api/v1`, Swagger UI at `/docs` |
| Database | PostgreSQL 17 | `localhost:5432` |
| Batch | Python CLI (Typer) — `python -m batch.cli` | n/a (command-line) |

The whole stack can be started together with `docker compose up`; see the
[root `../README.md`](../README.md) and
[`../docker-compose.yml`](../docker-compose.yml) for details.

## Legacy Reference

The [`../app/`](../app) directory contains the original z/OS mainframe
implementation — COBOL online and batch programs, copybooks, BMS 3270 maps,
JCL/PROC job definitions, and VSAM/sequential sample data. It is the **source of
truth for behavioral parity** and is preserved **unchanged**: the modern stack is
never built from it, but reproduces its business rules exactly and is verified by
golden-master parity against the sample data in [`../app/data`](../app/data).

The [`../diagrams/`](../diagrams) (application-flow and screen images) and
[`../samples/`](../samples) (mainframe compile and runtime samples) directories
are **legacy reference material** only and are not part of the modern build.

## Configuration and Credentials

All configuration is **environment-based** — no secret is ever hardcoded or
committed. Copy the example templates and set the values locally:

- Backend: [`../backend/.env.example`](../backend/.env.example) → `backend/.env`
- Frontend: [`../frontend/.env.local.example`](../frontend/.env.local.example) → `frontend/.env.local`

Refer to configuration by **environment-variable name** only — for example
`DATABASE_URL` (the PostgreSQL DSN), `SECRET_KEY` (the token-signing key),
`AUTH_MODE` (`session` baseline or `jwt`), and `NEXT_PUBLIC_API_URL` (the
browser-facing backend origin). Never print or commit a real secret value.
Generate a strong `SECRET_KEY` locally, for example:

```bash
openssl rand -hex 32
```

The seed data provides two demo logins — `ADMIN001` (administrator) and
`USER0001` (regular user), both with the password `PASSWORD`. These are
**seed-only, non-production** accounts: their passwords are stored **hashed**
(bcrypt/argon2) at rest — never in plaintext — and they must never be treated as
real credentials or hardcoded anywhere.

## Documentation Conventions

These documents are authored per Google's
[Style Guide for Code Samples](https://developers.google.com/style/code-samples)
— imperative voice, concise prose, and minimal, tested code samples — and render
on GitHub-flavored Markdown.
