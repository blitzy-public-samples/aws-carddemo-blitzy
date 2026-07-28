"""HTTP routing layer for the CardDemo backend.

This package (``app.api``) is the HTTP interface tier of the modernized
CardDemo FastAPI backend. It hosts the versioned router subpackage
``app.api.v1``, whose thin routers translate between HTTP requests and
responses and the service layer; they hold no business logic themselves,
since all domain rules live in ``app.services`` (see AAP §0.4.1).

The aggregate ``APIRouter`` is assembled inside ``app.api.v1`` and wired into
the application by ``app.main``; it is deliberately not referenced from this
module. This package initializer is intentionally inert: it pulls in no
submodules and has no side effects, so loading ``app.api`` on its own never
constructs routers, opens a database connection, or triggers the
router-to-service-to-repository chain.
"""
