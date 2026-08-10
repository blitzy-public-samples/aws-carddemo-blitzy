import { render, screen } from '@testing-library/react';
import ErrorBanner, {
  ERROR_LINE_ID,
  ERROR_LINE_WIDTH,
  isFieldInError,
  fieldErrorClass,
  fieldMarker,
  hasFieldErrors,
  invalidFieldProps,
  invalidValueProps,
  faultedFieldProps,
} from './ErrorBanner';
import type { FieldErrorMap } from '../types';

describe('ErrorBanner', () => {
  it('renders the error message with an alert role', () => {
    render(<ErrorBanner message="Record changed by some one else. Please review" />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Record changed by some one else. Please review');
    expect(alert).toHaveClass('errorBanner');
  });

  it('falls back to the informational message with a status role', () => {
    render(<ErrorBanner infoMessage="Type your User ID and Password" />);
    expect(screen.getByRole('status')).toHaveTextContent('Type your User ID and Password');
  });

  it('renders an empty placeholder when there is no message', () => {
    const { container } = render(<ErrorBanner />);
    const banner = container.querySelector('.errorBanner');
    expect(banner).not.toBeNull();
    expect(banner).toHaveClass('errorBanner--empty');
  });

  it('exposes the line-23 width of 78 columns', () => {
    expect(ERROR_LINE_WIDTH).toBe(78);
  });

  it('flags invalid or blank fields as in error (CSSETATY semantics)', () => {
    expect(isFieldInError({ invalid: true, blank: false })).toBe(true);
    expect(isFieldInError({ invalid: false, blank: true })).toBe(true);
    expect(isFieldInError({ invalid: false, blank: false })).toBe(false);
    expect(isFieldInError(undefined)).toBe(false);
  });

  it('returns the RED field class and a leading * marker for blank fields', () => {
    expect(fieldErrorClass({ invalid: true, blank: false })).toBe('fieldError');
    expect(fieldErrorClass({ invalid: false, blank: false })).toBe('');
    expect(fieldMarker({ invalid: false, blank: true })).toBe('*');
    expect(fieldMarker({ invalid: true, blank: false })).toBe('');
  });

  it('binds aria-invalid and the message region only on the faulted control', () => {
    expect(invalidFieldProps(true)).toEqual({
      'aria-invalid': true,
      'aria-describedby': ERROR_LINE_ID,
    });
    // A control that is not faulted emits nothing, so no dangling IDREF is left
    // behind on a screen whose banner is showing the info or blank variant.
    expect(invalidFieldProps(false)).toEqual({});
  });

  it('keeps a field hint ahead of the message region when faulted', () => {
    expect(invalidFieldProps(true, 'tranAmtHint')).toEqual({
      'aria-invalid': true,
      'aria-describedby': `tranAmtHint ${ERROR_LINE_ID}`,
    });
    expect(invalidFieldProps(false, 'tranAmtHint')).toEqual({
      'aria-describedby': 'tranAmtHint',
    });
    expect(invalidFieldProps(false, '')).toEqual({});
  });

  it('emits an id the error variant actually renders', () => {
    render(<ErrorBanner message="Tran ID can NOT be empty..." />);
    expect(screen.getByRole('alert')).toHaveAttribute('id', ERROR_LINE_ID);
    const bound = invalidFieldProps(true)['aria-describedby'];
    expect(document.getElementById(bound ?? '')).not.toBeNull();
  });

  it('adds the paint signal only for a control the program also reddens', () => {
    // `data-faulted` is what the stylesheet paints RED from, and it is emitted ONLY by
    // this helper -- so a screen whose program contains no `MOVE DFHRED` cannot acquire
    // the frame by marking its control for assistive technology.
    expect(faultedFieldProps(true)).toEqual({
      'aria-invalid': true,
      'aria-describedby': ERROR_LINE_ID,
      'data-faulted': 'true',
    });
    expect(faultedFieldProps(false)).toEqual({});
    expect(faultedFieldProps(true, 'acctsidHint')).toEqual({
      'aria-invalid': true,
      'aria-describedby': `acctsidHint ${ERROR_LINE_ID}`,
      'data-faulted': 'true',
    });
    // The accessible-only helper must never emit it, which is what keeps the twelve
    // screens that mark without painting free of the frame.
    expect(invalidFieldProps(true)).not.toHaveProperty('data-faulted');
    expect(invalidValueProps(true)).not.toHaveProperty('data-faulted');
  });

  it('omits the message reference for a value-evident fault', () => {
    // A row-action column faults from its own value without publishing a line-23
    // message, so the error variant is never mounted and the id does not exist.
    expect(invalidValueProps(true)).toEqual({ 'aria-invalid': true });
    expect(invalidValueProps(false)).toEqual({});
    expect(invalidValueProps(true)).not.toHaveProperty('aria-describedby');
  });

  it('keeps the message reference resolvable on every variant that carries text', () => {
    // ERRMSG is one BMS field whichever colour the program moves into it, so the id a
    // control points at through `aria-describedby` resolves on the informational
    // variant too: a screen whose outcome turns informational must not leave that
    // reference dangling.
    const { unmount } = render(<ErrorBanner infoMessage="Press PF5 key to delete this user ..." />);
    const info = document.getElementById(ERROR_LINE_ID);
    expect(info).not.toBeNull();
    expect(info).toHaveAttribute('role', 'status');
    unmount();

    // The blank variant carries no text at all, so it carries no id either and a
    // control faulted by its own value publishes no description.
    render(<ErrorBanner />);
    expect(document.getElementById(ERROR_LINE_ID)).toBeNull();
    expect(invalidValueProps(true)['aria-describedby']).toBeUndefined();
  });

  it('re-announces an unchanged message on the next send', () => {
    // A program writes ERRMSG on every SEND MAP, so a second identical rejection is
    // announced again. A live region is silent when text it already holds is written
    // back, so the region is remounted per send.
    const { rerender } = render(<ErrorBanner message="Did not find this user..." sendCount={1} />);
    const first = screen.getByRole('alert');
    rerender(<ErrorBanner message="Did not find this user..." sendCount={2} />);
    const second = screen.getByRole('alert');
    expect(second).not.toBe(first);
    expect(second).toHaveTextContent('Did not find this user...');
  });

  it('detects whether any field in a map is in error', () => {
    const map: FieldErrorMap = {
      acctId: { invalid: false, blank: false },
      amount: { invalid: true, blank: false },
    };
    expect(hasFieldErrors(map)).toBe(true);
    expect(hasFieldErrors({ x: { invalid: false, blank: false } })).toBe(false);
    expect(hasFieldErrors(undefined)).toBe(false);
  });
  it('publishes the INFOMSG line ABOVE the error line when a mapset declares both', () => {
    // COACTUP, COACTVW, COCRDLI, COCRDSL and COCRDUP each declare an ERRMSG field at
    // POS=(23,1) and a SEPARATE INFOMSG field at a LOWER row -- (22,23) on the two account
    // maps, row 20 on the three card maps -- and their programs populate both on the same
    // send: COACTUPC L2979-2981 moves WS-INFO-MSG to INFOMSGO and then WS-RETURN-MSG to
    // ERRMSGO. The informational line therefore renders FIRST. Rendering one and
    // discarding the other dropped half of what the screen says; rendering them the other
    // way round contradicted the rows the mapsets declare.
    const { container } = render(
      <ErrorBanner
        message="No change detected with respect to values fetched."
        infoFieldMessage="Update account details presented above."
      />,
    );

    const rows = Array.from(container.querySelectorAll('.errorBanner'));
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveClass('errorBanner--infoField');
    expect(rows[0]).toHaveTextContent('Update account details presented above.');
    expect(rows[1]).toHaveTextContent('No change detected with respect to values fetched.');
    // The line-23 id stays on the error line, so a faulted control's
    // ``aria-describedby`` still resolves to the message that describes it.
    expect(rows[1].id).toBe(ERROR_LINE_ID);
    expect(rows[0].id).toBe('');
    expect(screen.getByRole('alert')).toBe(rows[1]);
    expect(screen.getByRole('status')).toBe(rows[0]);
  });

  it('reserves the INFOMSG row while its field is darkened', () => {
    // The five programs move DFHBMDAR into the field when there is nothing to say, and a
    // darkened field still occupies its row on a 24-row device. Reserving the row keeps
    // the message region from shifting as the line comes and goes.
    const { container } = render(<ErrorBanner infoFieldMessage="" />);

    const rows = Array.from(container.querySelectorAll('.errorBanner'));
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveClass('errorBanner--infoField');
    expect(rows[0].textContent).toBe('');
    // Nothing is announced: an empty live region would be a reader-only artefact of
    // reserving the row.
    expect(rows[0]).not.toHaveAttribute('role');
    expect(rows[1]).toHaveClass('errorBanner--empty');
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('renders no INFOMSG row for the twelve mapsets that declare none', () => {
    const { container } = render(<ErrorBanner message="Tran ID can NOT be empty..." />);

    expect(container.querySelectorAll('.errorBanner')).toHaveLength(1);
    expect(container.querySelector('.errorBanner--infoField')).toBeNull();
  });

  it('keeps the ERRMSG field to one value when a program greens it', () => {
    // Eight programs move DFHGREEN into ERRMSGC and write their informational text into
    // that same field, so it holds either an error or that text -- never both. Every one
    // of the six screens that publish it clears the other before setting one.
    render(<ErrorBanner infoMessage="User ZZREG001 has been updated ..." />);

    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('User ZZREG001 has been updated ...');
    expect(status).not.toHaveClass('errorBanner--infoField');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('announces a browse boundary politely while keeping the line-23 reference', () => {
    // COUSR00.bms, COCRDLI.bms and COTRN00.bms declare ERRMSG with COLOR=RED statically
    // and their programs never move DFHGREEN, so the colour is preserved; reaching the
    // end of a browse is not a failure, so it must not interrupt as an alert.
    render(<ErrorBanner noticeMessage="You have reached the bottom of the page..." />);

    expect(screen.queryByRole('alert')).toBeNull();
    const notice = screen.getByRole('status');
    expect(notice).toHaveTextContent('You have reached the bottom of the page...');
    expect(notice).toHaveClass('errorBanner--notice');
    expect(notice.id).toBe(ERROR_LINE_ID);
  });

  it('prefers a genuine failure over a notice on the single message line', () => {
    render(
      <ErrorBanner message="User ID NOT found..." noticeMessage="NO RECORDS TO SHOW" />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('User ID NOT found...');
    expect(screen.queryByText('NO RECORDS TO SHOW')).toBeNull();
  });
});
