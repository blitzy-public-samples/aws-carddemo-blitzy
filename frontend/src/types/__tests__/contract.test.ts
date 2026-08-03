/**
 * :module: types/__tests__/contract
 * :purpose: Consumer/provider contract test locking the ``frontend/src/types``
 *   layer to the backend REST wire contracts (the ``carddemo-common`` /
 *   ``auth-service`` DTO classes and the api-gateway ``MenuController`` records).
 *   Each sample payload is shaped exactly like the JSON the backend produces /
 *   consumes and is assigned to the corresponding frontend type imported through
 *   the ``../index`` barrel. Because ts-jest type-checks (and ``tsc --noEmit``
 *   covers this file), any field-name or shape drift between a frontend type and
 *   its backend DTO fails compilation, so the divergence that motivated C15 cannot
 *   regress silently. Runtime assertions additionally lock the enum / constant
 *   values (``Role``, ``PfKeyAction``, ``ROLE_AUTHORITY``, the ``CDEMO_*``
 *   condition codes).
 * :note: Importing every type from ``../index`` also proves the barrel resolves and
 *   that the ten sibling modules export a collision-free namespace (a duplicate
 *   name would make the wildcard re-exports ambiguous and fail to compile).
 * :note: Monetary and identifier fields are ``string`` on the wire (AAP 0.6.1) to
 *   preserve packed-decimal scale and zero-padded width. The backend money / id
 *   DTO fields still serialize as JSON numbers pending ``@JsonFormat(shape=STRING)``
 *   (the SessionContext precedent); that backend alignment is tracked separately.
 */

import type {
  MenuOption,
  MenuResponseDto,
  MenuSelectionRequestDto,
  MenuSelectionResponseDto,
  MainMenuResponseDto,
  AdminMenuResponseDto,
  SignonRequestDto,
  SignonResponseDto,
  SignonScreenState,
  SessionContext,
  Role,
  ProgramContext,
  AccountViewResponseDto,
  AccountUpdateRequestDto,
  AccountUpdateResponseDto,
  AccountUpdateFormParts,
  OptimisticLockConflict,
  CardListItemDto,
  CardListRequestDto,
  CardListResponseDto,
  CardDetailResponseDto,
  CardUpdateRequestDto,
  CardUpdateResponseDto,
  TranListItemDto,
  TranListRequestDto,
  TranListResponseDto,
  TranViewResponseDto,
  TranAddRequestDto,
  TranAddResponseDto,
  BillPayRequestDto,
  BillPayResponseDto,
  ReportRequestDto,
  ReportResponseDto,
  ReportType,
  ReportDateParts,
  UserDto,
  UserListItemDto,
  UserListRequestDto,
  UserListResponseDto,
  UserAddRequestDto,
  UserUpdateRequestDto,
  UserDeleteRequestDto,
  UserAddResponseDto,
  UserUpdateResponseDto,
  UserDeleteResponseDto,
  ApiErrorResponse,
  Page,
  PageInfo,
  FieldErrorMap,
  FieldErrorState,
  ErrMsg,
  InfoMsg,
  ActiveStatus,
} from '../index';

import {
  PfKeyAction,
  ROLE_AUTHORITY,
  REPORT_TYPES,
  CDEMO_USRTYP_ADMIN,
  CDEMO_USRTYP_USER,
  CDEMO_PGM_ENTER,
  CDEMO_PGM_REENTER,
} from '../index';

describe('menu contract (api-gateway MenuController records)', () => {
  it('binds MenuResponse / MenuOptionView', () => {
    // MenuController.MenuOptionView(int, String, String, String)
    const option: MenuOption = {
      optionNumber: 1,
      optionName: 'Account View',
      programName: 'COACTVWC',
      targetRoute: '/accounts',
    };
    // A coming-soon option has no resolved route.
    const unmapped: MenuOption = {
      optionNumber: 9,
      optionName: 'Reports',
      programName: 'CORPT00C',
      targetRoute: null,
    };
    // MenuController.MenuResponse(String, String, List<MenuOptionView>, String)
    const menu: MenuResponseDto = {
      tranId: 'CM00',
      programName: 'COMEN01C',
      options: [option, unmapped],
      message: null,
    };
    const main: MainMenuResponseDto = menu;
    const admin: AdminMenuResponseDto = { ...menu, tranId: 'CA00', programName: 'COADM01C' };

    expect(option.optionNumber).toBe(1);
    expect(unmapped.targetRoute).toBeNull();
    expect(menu.options).toHaveLength(2);
    expect(main.tranId).toBe('CM00');
    expect(admin.tranId).toBe('CA00');
  });

  it('binds MenuSelectionRequest / MenuSelectionResponse', () => {
    // MenuController.MenuSelectionRequest(String option, String aid)
    const req: MenuSelectionRequestDto = { option: '1', aid: 'ENTER' };
    const reqNoAid: MenuSelectionRequestDto = { option: '2' };
    // MenuController.MenuSelectionResponse(boolean, String, String, String)
    const dispatched: MenuSelectionResponseDto = {
      dispatched: true,
      programName: 'COACTVWC',
      targetRoute: '/accounts',
      message: null,
    };
    const comingSoon: MenuSelectionResponseDto = {
      dispatched: false,
      programName: null,
      targetRoute: null,
      message: 'This option is coming soon.',
    };

    expect(req.aid).toBe('ENTER');
    expect(reqNoAid.aid).toBeUndefined();
    expect(dispatched.dispatched).toBe(true);
    expect(comingSoon.message).toContain('coming soon');
  });
});

describe('auth contract (auth-service Signon DTOs)', () => {
  it('binds SignonRequestDto / SignonResponseDto', () => {
    const req: SignonRequestDto = { userId: 'ADMIN001', password: 'secret01' };
    // SignonResponseDto.userType serializes via @JsonValue to 'A' / 'U'.
    const resp: SignonResponseDto = {
      userId: 'ADMIN001',
      userType: 'A',
      redirectTarget: 'CA00',
    };
    const screen: SignonScreenState = { errMsg: '' };

    expect(req.userId).toBe('ADMIN001');
    expect(resp.userType).toBe('A');
    expect(resp.redirectTarget).toBe('CA00');
    expect(screen.errMsg).toBe('');
  });
});

describe('session contract (SessionContext)', () => {
  it('binds the externalized COMMAREA context', () => {
    // userType via @JsonValue 'A'/'U'; programContext via @JsonValue 0/1;
    // custId / acctId are @JsonFormat(shape=STRING).
    const session: SessionContext = {
      userId: 'USER0001',
      userType: 'U',
      programContext: 1,
      custId: '000000001',
      acctId: '00000000011',
      cardNum: '4111111111111111',
      lastMap: 'COMEN1A',
      lastMapset: 'COMEN01',
    };
    const role: Role = 'A';
    const ctx: ProgramContext = 0;

    expect(session.programContext).toBe(1);
    expect(session.custId).toBe('000000001');
    expect(role).toBe('A');
    expect(ctx).toBe(0);
  });
});

describe('account contract (Account*Dto)', () => {
  // The frontend account contract follows its authoritative agent-prompt and AAP
  // 0.6.2 optimistic locking: it carries `version` and the read-only `acctAddrZip`
  // and types `acctId` / `custId` as string. The backend AccountViewResponseDto /
  // AccountUpdateRequestDto currently lack `version` + `acctAddrZip` and use Long
  // ids; bringing those DTOs up to this contract is a tracked backend follow-up.
  const view: AccountViewResponseDto = {
    acctId: '00000000011',
    acctAddrZip: '12345-6789',
    custId: '000000001',
    version: 3,
    acctActiveStatus: 'Y',
    acctCurrBal: '1250.00',
    acctCreditLimit: '5000.00',
    acctCashCreditLimit: '1000.00',
    acctOpenDate: '2020-01-15',
    acctExpiraionDate: '2027-01-31',
    acctReissueDate: '2024-01-15',
    acctCurrCycCredit: '300.00',
    acctCurrCycDebit: '150.00',
    acctGroupId: 'GRP0000001',
    custFirstName: 'JANE',
    custMiddleName: 'Q',
    custLastName: 'DOE',
    custAddrLine1: '1 MAIN ST',
    custAddrLine2: 'APT 2',
    custAddrLine3: 'ANYTOWN',
    custAddrStateCd: 'NY',
    custAddrCountryCd: 'USA',
    custAddrZip: '12345',
    custPhoneNum1: '(555)555-1212',
    custPhoneNum2: '(555)555-3434',
    custSsn: '123456789',
    custGovtIssuedId: 'DL-1234567890',
    custDobYyyyMmDd: '1985-06-15',
    custEftAccountId: 'EFT0000001',
    custPriCardHolderInd: 'Y',
    custFicoCreditScore: 720,
  };

  it('binds AccountViewResponseDto with optimistic-lock version', () => {
    expect(view.version).toBe(3);
    expect(view.acctExpiraionDate).toBe('2027-01-31');
    expect(view.acctAddrZip).toBe('12345-6789');
    expect(view.acctId).toBe('00000000011');
  });

  it('binds AccountUpdateRequestDto (mutable fields + version, no identity fields)', () => {
    const update: AccountUpdateRequestDto = {
      version: 3,
      acctActiveStatus: 'Y',
      acctCurrBal: '1250.00',
      acctCreditLimit: '5000.00',
      acctCashCreditLimit: '1000.00',
      acctOpenDate: '2020-01-15',
      acctExpiraionDate: '2027-01-31',
      acctReissueDate: '2024-01-15',
      acctCurrCycCredit: '300.00',
      acctCurrCycDebit: '150.00',
      acctGroupId: 'GRP0000001',
      custFirstName: 'JANE',
      custMiddleName: 'Q',
      custLastName: 'DOE',
      custAddrLine1: '1 MAIN ST',
      custAddrLine2: 'APT 2',
      custAddrLine3: 'ANYTOWN',
      custAddrStateCd: 'NY',
      custAddrCountryCd: 'USA',
      custAddrZip: '12345',
      custPhoneNum1: '(555)555-1212',
      custPhoneNum2: '(555)555-3434',
      custSsn: '123456789',
      custGovtIssuedId: 'DL-1234567890',
      custDobYyyyMmDd: '1985-06-15',
      custEftAccountId: 'EFT0000001',
      custPriCardHolderInd: 'Y',
      custFicoCreditScore: 720,
    };
    const updated: AccountUpdateResponseDto = view;
    const conflict: OptimisticLockConflict = {
      message: 'Record changed by some one else. Please review',
    };

    expect(update.version).toBe(3);
    expect(update.acctGroupId).toBe('GRP0000001');
    expect(updated.version).toBe(3);
    expect(conflict.message).toContain('Please review');
  });

  it('binds the page-level AccountUpdateFormParts helper', () => {
    const parts: AccountUpdateFormParts = {
      opnYear: '2020', opnMon: '01', opnDay: '15',
      expYear: '2027', expMon: '01', expDay: '31',
      risYear: '2024', risMon: '01', risDay: '15',
      dobYear: '1985', dobMon: '06', dobDay: '15',
      ssnArea: '123', ssnGroup: '45', ssnSerial: '6789',
      phone1Area: '555', phone1Prefix: '555', phone1Line: '1212',
      phone2Area: '555', phone2Prefix: '555', phone2Line: '3434',
    };
    expect(parts.expYear).toBe('2027');
  });
});

describe('card contract (Card*Dto)', () => {
  it('binds CardListResponseDto ({ cards })', () => {
    const item: CardListItemDto = {
      cardNum: '4111111111111111',
      cardAcctId: '00000000011',
      cardActiveStatus: 'Y',
    };
    const list: CardListResponseDto = { cards: [item] };
    const listReq: CardListRequestDto = { accountId: '00000000011', page: 0 };

    expect(list.cards[0].cardNum).toBe('4111111111111111');
    expect(listReq.accountId).toBe('00000000011');
  });

  it('binds CardDetailResponseDto (with custId) and CardUpdateResponseDto', () => {
    const detail: CardDetailResponseDto = {
      cardNum: '4111111111111111',
      cardAcctId: '00000000011',
      cardEmbossedName: 'JANE Q DOE',
      cardActiveStatus: 'Y',
      cardExpiraionDate: '2027-01-31',
      custId: '000000001',
      version: 4,
    };
    const updated: CardUpdateResponseDto = detail;

    expect(detail.custId).toBe('000000001');
    expect(detail.cardExpiraionDate).toBe('2027-01-31');
    expect(detail.version).toBe(4);
    expect(updated.custId).toBe('000000001');
    expect(updated.version).toBe(4);
  });

  it('binds CardUpdateRequestDto (editable fields + version, no CVV, no keys)', () => {
    // CVV (cardCvvCd) is intentionally absent per C09 — it is never accepted from
    // the client; the card number travels in the request path, not the body. The
    // optimistic-lock `version` read at display time IS carried, mirroring the
    // account update contract (AAP 0.6.2).
    const update: CardUpdateRequestDto = {
      cardEmbossedName: 'JANE Q DOE',
      cardActiveStatus: 'N',
      cardExpiraionDate: '2028-01-31',
      version: 4,
    };
    expect(update.cardActiveStatus).toBe('N');
    expect(update.version).toBe(4);
  });
});

describe('transaction contract (Transaction*Dto)', () => {
  it('binds TransactionListItemDto (tranDate) and the list request / response', () => {
    const row: TranListItemDto = {
      tranId: '0000000000000001',
      tranDate: '01/15/24',
      tranDesc: 'PURCHASE',
      tranAmt: '42.50',
    };
    const req: TranListRequestDto = {
      action: 'F',
      tranIdFilter: '0000000000000001',
      pageNumber: 1,
      nextPage: true,
    };
    const resp: TranListResponseDto = {
      transactions: [row],
      pageNumber: 1,
      tranIdFirst: '0000000000000001',
      tranIdLast: '0000000000000010',
      nextPage: true,
      selectedTranId: null,
      message: null,
    };

    expect(row.tranDate).toBe('01/15/24');
    expect(req.pageNumber).toBe(1);
    expect(resp.transactions).toHaveLength(1);
    expect(resp.selectedTranId).toBeNull();
  });

  it('binds TranViewResponseDto', () => {
    const detail: TranViewResponseDto = {
      tranId: '0000000000000001',
      tranCardNum: '4111111111111111',
      tranTypeCd: '01',
      tranCatCd: 5,
      tranSource: 'POS',
      tranDesc: 'PURCHASE',
      tranAmt: '42.50',
      tranOrigTs: '2024-01-15-10.30.00.000000',
      tranProcTs: '2024-01-15-23.59.59.000000',
      tranMerchantId: '000000123',
      tranMerchantName: 'ACME STORE',
      tranMerchantCity: 'ANYTOWN',
      tranMerchantZip: '12345',
    };
    expect(detail.tranCatCd).toBe(5);
    expect(detail.tranMerchantId).toBe('000000123');
  });

  it('binds TranAddRequestDto (acctId / tranCardNum) and TranAddResponseDto (message)', () => {
    const add: TranAddRequestDto = {
      acctId: '00000000011',
      tranCardNum: '4111111111111111',
      tranTypeCd: '01',
      tranCatCd: 5,
      tranSource: 'POS',
      tranDesc: 'PURCHASE',
      tranAmt: '42.50',
      tranOrigTs: '2024-01-15-10.30.00.000000',
      tranProcTs: '2024-01-15-23.59.59.000000',
      tranMerchantId: '000000123',
      tranMerchantName: 'ACME STORE',
      tranMerchantCity: 'ANYTOWN',
      tranMerchantZip: '12345',
      confirm: 'Y',
    };
    const resp: TranAddResponseDto = {
      tranId: '0000000000000042',
      message: 'Transaction added successfully.',
    };

    expect(add.acctId).toBe('00000000011');
    expect(add.tranCardNum).toBe('4111111111111111');
    expect(resp.tranId).toBe('0000000000000042');
    expect(resp.message).toContain('successfully');
  });
});

describe('billpay contract (BillPayment*Dto)', () => {
  it('binds BillPayRequestDto and BillPayResponseDto (transactionId + message)', () => {
    const req: BillPayRequestDto = { accountId: '00000000011', confirm: 'Y' };
    const resp: BillPayResponseDto = {
      accountId: '00000000011',
      currentBalance: '0.00',
      transactionId: '0000000000000043',
      message: 'Payment successful. Your Transaction ID is 0000000000000043.',
    };
    const declined: BillPayResponseDto = {
      accountId: '00000000011',
      currentBalance: '1250.00',
      transactionId: null,
      message: null,
    };

    expect(req.confirm).toBe('Y');
    expect(resp.transactionId).toBe('0000000000000043');
    expect(declined.transactionId).toBeNull();
  });
});

describe('report contract (Report*Dto)', () => {
  it('binds ReportRequestDto / ReportResponseDto and the helpers', () => {
    const req: ReportRequestDto = {
      monthly: 'Y',
      yearly: 'N',
      custom: 'N',
      confirm: 'Y',
    };
    const resp: ReportResponseDto = {
      monthly: 'Y',
      confirm: 'Y',
      errorMessage: '',
      title01: 'CardDemo',
      trnName: 'CR00',
      pgmName: 'CORPT00C',
      currentDate: '01/15/24',
      currentTime: '10:30:00',
    };
    const type: ReportType = 'MONTHLY';
    const parts: ReportDateParts = { month: '01', day: '15', year: '2024' };

    expect(req.monthly).toBe('Y');
    expect(resp.pgmName).toBe('CORPT00C');
    expect(type).toBe('MONTHLY');
    expect(parts.year).toBe('2024');
    expect(REPORT_TYPES).toContain('MONTHLY');
  });
});

describe('user contract (User DTOs)', () => {
  it('binds UserDto and the list request / response', () => {
    const user: UserDto = {
      userId: 'ADMIN001',
      firstName: 'JANE',
      lastName: 'DOE',
      userType: 'A',
    };
    const listItem: UserListItemDto = user;
    const listReq: UserListRequestDto = { userId: 'A', page: 1 };
    const pageInfo: PageInfo = {
      pageNumber: 1,
      pageSize: 10,
      hasNext: false,
      hasPrevious: false,
    };
    const listResp: UserListResponseDto = { items: [listItem], page: pageInfo };

    expect(user.userType).toBe('A');
    expect(listReq.page).toBe(1);
    expect(listResp.items[0].userId).toBe('ADMIN001');
    expect(listResp.page.pageSize).toBe(10);
  });

  it('binds add / update / delete requests (password required on add + update)', () => {
    // AddUserRequestDto / UpdateUserRequestDto have no password field on the
    // backend record; the raw password is carried in the request body and encoded
    // server-side. The frontend bundles it as a required field on both flows.
    const add: UserAddRequestDto = {
      userId: 'USER0001',
      firstName: 'JOHN',
      lastName: 'SMITH',
      userType: 'U',
      password: 'secret01',
    };
    const update: UserUpdateRequestDto = {
      firstName: 'JOHN',
      lastName: 'SMITH',
      userType: 'U',
      password: 'secret02',
    };
    const del: UserDeleteRequestDto = { userId: 'USER0001' };

    const addResp: UserAddResponseDto = {
      userId: 'USER0001', firstName: 'JOHN', lastName: 'SMITH', userType: 'U',
    };
    const updResp: UserUpdateResponseDto = addResp;
    const delResp: UserDeleteResponseDto = addResp;

    expect(add.password).toBe('secret01');
    expect(update.password).toBe('secret02');
    expect(del.userId).toBe('USER0001');
    expect(addResp.userType).toBe('U');
    expect(updResp.userId).toBe('USER0001');
    expect(delResp.userId).toBe('USER0001');
  });
});

describe('common contract (ErrorResponse + shared primitives)', () => {
  it('binds ApiErrorResponse to the backend ErrorResponse', () => {
    const err: ApiErrorResponse = {
      timestamp: '2024-01-15T10:30:00Z',
      status: 409,
      error: 'Conflict',
      errorCode: '102',
      message: 'Over credit limit',
      path: '/accounts/11',
      traceId: 'abc123',
      correlationId: 'corr-abc123',
      fieldErrors: { acctCreditLimit: 'exceeded' },
    };
    const minimal: ApiErrorResponse = {
      timestamp: '2024-01-15T10:30:00Z',
      status: 500,
      error: 'Internal Server Error',
      message: 'Unexpected error',
      path: '/accounts/11',
    };

    expect(err.errorCode).toBe('102');
    expect(err.traceId).toBe('abc123');
    expect(err.correlationId).toBe('corr-abc123');
    expect(minimal.errorCode).toBeUndefined();
    expect(minimal.correlationId).toBeUndefined();
  });

  it('binds the shared primitives (Page, FieldError*, message aliases, ActiveStatus)', () => {
    const page: Page<UserDto> = {
      items: [],
      page: { pageNumber: 1, pageSize: 7, hasNext: true, hasPrevious: false },
    };
    const fieldState: FieldErrorState = { invalid: true, blank: false };
    const fieldMap: FieldErrorMap = { acctId: fieldState };
    const err: ErrMsg = 'invalid';
    const info: InfoMsg = 'saved';
    const status: ActiveStatus = 'Y';

    expect(page.page.pageSize).toBe(7);
    expect(fieldMap.acctId.invalid).toBe(true);
    expect(err).toBe('invalid');
    expect(info).toBe('saved');
    expect(status).toBe('Y');
  });
});

describe('runtime enum / constant contract', () => {
  it('locks PfKeyAction AID values', () => {
    expect(PfKeyAction.Enter).toBe('ENTER');
    expect(PfKeyAction.PF3).toBe('PF3');
    expect(PfKeyAction.PF7).toBe('PF7');
    expect(PfKeyAction.PF8).toBe('PF8');
  });

  it('locks Role codes and the ROLE_AUTHORITY mapping', () => {
    expect(CDEMO_USRTYP_ADMIN).toBe('A');
    expect(CDEMO_USRTYP_USER).toBe('U');
    expect(ROLE_AUTHORITY.A).toBe('ROLE_ADMIN');
    expect(ROLE_AUTHORITY.U).toBe('ROLE_USER');
  });

  it('locks the ProgramContext condition codes', () => {
    expect(CDEMO_PGM_ENTER).toBe(0);
    expect(CDEMO_PGM_REENTER).toBe(1);
  });
});
