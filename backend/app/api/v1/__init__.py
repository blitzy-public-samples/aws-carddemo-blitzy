"""CardDemo v1 API package.

Aggregates the 8 versioned routers into a single ``api_router`` that
``app.main`` mounts under ``settings.API_V1_PREFIX`` (``/api/v1``).

Each sub-router owns its own resource sub-prefix and tags; the ``/api/v1``
prefix is applied once at mount time in ``app.main`` (never hardcoded here).
Ported area map (COBOL online programs -> routers):
  auth<-COSGN00C(CC00)  menu<-COMEN01C/COADM01C(CM00/CA00)
  accounts<-COACTVWC/COACTUPC(CAVW/CAUP)
  cards<-COCRDLIC/COCRDSLC/COCRDUPC(CCLI/CCDL/CCUP)
  transactions<-COTRN00C/01C/02C(CT00-02)  reports<-CORPT00C(CR00)
  billpay<-COBIL00C(CB00)  users<-COUSR00C-03C(CU00-03)
"""

from fastapi import APIRouter

from app.api.v1.auth import router as auth_router
from app.api.v1.menu import router as menu_router
from app.api.v1.accounts import router as accounts_router
from app.api.v1.cards import router as cards_router
from app.api.v1.transactions import router as transactions_router
from app.api.v1.reports import router as reports_router
from app.api.v1.billpay import router as billpay_router
from app.api.v1.users import router as users_router

api_router = APIRouter()
api_router.include_router(auth_router)
api_router.include_router(menu_router)
api_router.include_router(accounts_router)
api_router.include_router(cards_router)
api_router.include_router(transactions_router)
api_router.include_router(reports_router)
api_router.include_router(billpay_router)
api_router.include_router(users_router)

__all__ = ["api_router"]
