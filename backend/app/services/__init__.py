"""Business-logic (service) layer for the CardDemo backend.

One service module per legacy CICS online program cluster (AAP 0.4.3 service
layer): each service holds the ported PROCEDURE DIVISION business rules 1:1 with
its COBOL origin (Minimal Change Clause, AAP 0.8.1), orchestrating the thin
repositories and enforcing the validations, optimistic-lock semantics, and
numeric rules the mainframe programs performed. Routers (``app.api``) stay thin
and delegate here; repositories (``app.repositories``) own data access.

This package initializer re-exports the concrete service classes so callers can
write ``from app.services import CardService`` instead of a deep module path.
The re-export intentionally references only the service modules present in this
tree; importing a service class pulls in its repositories/schemas but never
instantiates a repository, opens a connection, or creates a session, so this
marker performs no side effects and stays free of import cycles (it never
imports from ``app.api`` / ``app.main``).
"""

# Card list/view/update service (ports COCRDLIC / COCRDSLC / COCRDUPC).
from app.services.card_service import CardService

__all__ = ["CardService"]
