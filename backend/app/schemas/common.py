"""Shared Pydantic v2 base models and cross-cutting response envelopes.

This is the foundational schema module for the CardDemo backend: every other
module under ``app.schemas`` builds on the base classes and envelopes defined
here, and this module imports from no sibling schema module (it is the root of
the schema dependency graph).

Traceability (legacy COBOL copybooks, REFERENCE only):
    * ``app/cpy/CSMSG01Y.cpy`` -- CCDA-COMMON-MESSAGES: CCDA-MSG-THANK-YOU and
      CCDA-MSG-INVALID-KEY (both ``PIC X(50)``). These on-screen message texts
      map to :class:`MessageResponse` and :class:`ErrorResponse` respectively.
    * ``app/cpy/CSMSG02Y.cpy`` -- ABEND-DATA: ABEND-CODE ``PIC X(4)``,
      ABEND-CULPRIT ``PIC X(8)``, ABEND-REASON ``PIC X(50)``, ABEND-MSG
      ``PIC X(72)``. The reason/message texts map to
      :attr:`ErrorResponse.message` and :attr:`ErrorResponse.detail`.

The generic :class:`PaginatedResponse` provides the browse/list envelope that
replaces the 3270 scroll semantics (for example the <= 7 rows-per-page card
browse of ``COCRDLIC``); see :data:`DEFAULT_PAGE_SIZE`.
"""

from datetime import datetime, timezone
from typing import Generic, Optional, TypeVar

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.utils import validators

__all__ = [
    "OrmBase",
    "RequestBase",
    "TItem",
    "PaginationParams",
    "PaginatedResponse",
    "MessageResponse",
    "ErrorResponse",
]

# ---------------------------------------------------------------------------
# Pagination bounds (Ochs Rule 0.8.2: constants are ALL_UPPERCASE).
#
# DEFAULT_PAGE_SIZE mirrors the legacy card-browse limit of <= 7 rows per
# screen (F-004, program COCRDLIC). Sibling list services (cards, transactions,
# users) reuse these bounds so paging behaves consistently across screens.
# ---------------------------------------------------------------------------
DEFAULT_PAGE = 1
MIN_PAGE = 1
DEFAULT_PAGE_SIZE = 7
MIN_PAGE_SIZE = 1
MAX_PAGE_SIZE = 100


class OrmBase(BaseModel):
    """Base class for read/response schemas built from ORM objects.

    ``from_attributes=True`` lets a schema be populated directly from a
    SQLAlchemy ORM instance via ``Model.model_validate(orm_obj)``.
    ``populate_by_name=True`` allows population by field name as well as by
    alias, and ``str_strip_whitespace=True`` trims incidental surrounding
    whitespace carried over from fixed-width mainframe fields.
    """

    model_config = ConfigDict(
        from_attributes=True,
        populate_by_name=True,
        str_strip_whitespace=True,
    )


class RequestBase(BaseModel):
    """Base class for request DTOs that must reject unexpected input.

    ``extra="forbid"`` rejects fields that are not declared on the schema
    (input sanitization per the Ochs Rule -- "sanitize/validate all data that
    comes from users"), and ``str_strip_whitespace=True`` trims surrounding
    whitespace on incoming string values before validation.
    """

    model_config = ConfigDict(
        str_strip_whitespace=True,
        extra="forbid",
    )

    @model_validator(mode="after")
    def _RejectControlCharacters(self) -> "RequestBase":
        """Reject ASCII control characters in any string field (QA finding F4).

        Every inbound request DTO inherits this base, so this single guard makes
        the whole write surface reject malformed input (an embedded NUL U+0000,
        which the PostgreSQL text type and bcrypt reject with an unhandled 500)
        at the schema edge, turning it into a bounded 422 rather than a generic
        500 (Ochs rule: sanitize all user data; no generic 500). It runs after
        field validation, so ``str_strip_whitespace`` has already trimmed
        surrounding whitespace and only meaningful interior content is checked.

        Iterating the model yields ``(field_name, value)`` pairs; only ``str``
        values are inspected (``None`` optionals and non-text fields are skipped
        by :func:`app.utils.validators.ValidateNoControlChars`). Raising
        ``ValueError`` surfaces as a 422 whose ``input`` container has its
        sensitive keys (for example ``password``) redacted by the request
        validation handler in ``app.main`` (QA finding F7), so the value is
        never echoed back.

        Returns:
            ``self`` unchanged when every string field is free of control
            characters.

        Raises:
            ValueError: If any string field contains a control character; the
                message names only the field, never its value.
        """
        for fieldName, fieldValue in self:
            result = validators.ValidateNoControlChars(fieldName, fieldValue)
            if not result.isValid:
                raise ValueError(result.message)
        return self


# Element type variable for the generic paginated envelope below.
TItem = TypeVar("TItem")


class PaginationParams(BaseModel):
    """Query parameters describing a single page of a browse request.

    The ``page_size`` default of :data:`DEFAULT_PAGE_SIZE` (7) preserves the
    legacy card-browse page size (F-004, ``COCRDLIC``); callers may request a
    larger window up to :data:`MAX_PAGE_SIZE`.
    """

    page: int = Field(default=DEFAULT_PAGE, ge=MIN_PAGE)
    page_size: int = Field(
        default=DEFAULT_PAGE_SIZE,
        ge=MIN_PAGE_SIZE,
        le=MAX_PAGE_SIZE,
    )


class PaginatedResponse(OrmBase, Generic[TItem]):
    """Generic envelope wrapping one page of ``TItem`` rows plus page metadata.

    Reused by the card, transaction, and user list endpoints, for example
    ``PaginatedResponse[CardSummary]``. Inherits :class:`OrmBase` so a page of
    ORM rows can be validated directly.
    """

    items: list[TItem]
    page: int
    page_size: int
    total_items: int
    total_pages: int
    has_next: bool
    has_previous: bool

    @classmethod
    def Create(
        cls,
        items: list[TItem],
        total_items: int,
        params: PaginationParams,
    ) -> "PaginatedResponse[TItem]":
        """Build an envelope from a page slice, total count, and page params.

        Derives ``total_pages``, ``has_next``, and ``has_previous`` from the
        pagination window using integer arithmetic only (no floating point, per
        the numeric-precision requirement) so callers supply only the current
        page slice and the overall row count.
        """
        pageNumber = params.page
        pageSize = params.page_size
        totalPages = (total_items + pageSize - 1) // pageSize if total_items > 0 else 0
        return cls(
            items=items,
            page=pageNumber,
            page_size=pageSize,
            total_items=total_items,
            total_pages=totalPages,
            has_next=pageNumber < totalPages,
            has_previous=pageNumber > MIN_PAGE,
        )


class MessageResponse(BaseModel):
    """Generic informational/confirmation message envelope.

    Modern REST equivalent of CSMSG01Y ``CCDA-MSG-THANK-YOU`` (legacy
    ``PIC X(50)``). The message is not length-limited here; modern clients do
    not depend on the fixed-width terminal field size.
    """

    message: str


class ErrorResponse(BaseModel):
    """Standard error envelope routers use to serialize domain exceptions.

    Modern REST equivalent of the CSMSG01Y ``CCDA-MSG-INVALID-KEY`` on-screen
    error text and the CSMSG02Y ABEND-DATA structure. This is only the response
    *shape*; the exception *classes* (CardDemoError, NotFoundError,
    TransactionPostingError, ...) live in ``app.core.exceptions`` and are not
    imported here (which avoids a schemas -> core dependency cycle).
    """

    message: str
    # Serialized domain/error code, e.g. posting codes 100/101/102/103/109.
    # Kept as a free string so any legacy or HTTP code can be represented.
    code: Optional[str] = None
    # Extended detail; modern equivalent of CSMSG02Y ABEND-MSG (PIC X(72)).
    detail: Optional[str] = None
    # UTC instant the error was generated (aids diagnostics/tracing).
    timestamp: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc),
    )
