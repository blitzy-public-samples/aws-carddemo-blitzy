/**
 * Header component tests.
 *
 * :purpose: Verify the BMS-faithful header: the per-mapset caption literals and
 *     their values -- the unspaced ``Tran:`` / ``Prog:`` / ``Date:`` / ``Time:`` of
 *     the sixteen sibling mapsets and the spaced ``Tran :`` family plus the
 *     ``AppID:`` / ``SysID:`` row that only ``app/bms/COSGN00.bms`` carries -- the
 *     BLUE ``.label`` value styling and YELLOW ``.title`` heading styling, the
 *     ``MM/DD/YY`` and 24-hour ``HH:MM:SS`` formatting derived from
 *     ``app/cpy/CSDAT01Y.cpy``, and the static server-snapshot timestamp that does
 *     not tick (legacy ``POPULATE-HEADER-INFO`` captures the time once at send).
 */
import { render, screen, act } from '@testing-library/react';
// Jest's ESM runtime does not inject ``jest`` as a global (unlike describe/it/
// expect), so it is imported explicitly.
import { jest } from '@jest/globals';
import Header, { formatDate, formatTime } from './Header';

describe('Header', () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  it('renders the sixteen sibling mapsets\' unspaced captions by default', () => {
    render(
      <Header
        transactionId="CM00"
        programName="COMEN01C"
        title01="AWS Mainframe Modernization"
        title02="CardDemo"
      />,
    );
    expect(screen.getByText('Tran:')).toBeInTheDocument();
    expect(screen.getByText('Prog:')).toBeInTheDocument();
    expect(screen.getByText('Date:')).toBeInTheDocument();
    expect(screen.getByText('Time:')).toBeInTheDocument();
    expect(screen.queryByText('Tran :')).not.toBeInTheDocument();
    expect(screen.getByTestId('tran-id')).toHaveTextContent('CM00');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COMEN01C');
    expect(screen.getByTestId('title01')).toHaveTextContent(
      'AWS Mainframe Modernization',
    );
    expect(screen.getByTestId('title02')).toHaveTextContent('CardDemo');
    // Only COSGN00.bms carries the third row.
    expect(screen.queryByTestId('app-sys-row')).not.toBeInTheDocument();
  });

  it('renders COSGN00\'s spaced captions and its AppID/SysID row', () => {
    render(
      <Header
        transactionId="CC00"
        programName="COSGN00C"
        title01="AWS Mainframe Modernization"
        title02="CardDemo"
        captionStyle="signon"
        appId="CARDDEMO"
      />,
    );
    expect(screen.getByText('Tran :')).toBeInTheDocument();
    expect(screen.getByText('Prog :')).toBeInTheDocument();
    expect(screen.getByText('Date :')).toBeInTheDocument();
    expect(screen.getByText('Time :')).toBeInTheDocument();
    expect(screen.queryByText('Tran:')).not.toBeInTheDocument();
    expect(screen.getByText('AppID:')).toBeInTheDocument();
    expect(screen.getByText('SysID:')).toBeInTheDocument();
    expect(screen.getByTestId('app-id')).toHaveTextContent('CARDDEMO');
    // The mapset's SYSID field is INITIAL='        ', so an unset value is blank.
    expect(screen.getByTestId('sys-id')).toHaveTextContent('');
  });

  it('renders values BLUE (.label) and titles YELLOW (.title)', () => {
    render(<Header title01="T1" title02="T2" />);
    expect(screen.getByTestId('title01')).toHaveClass('title');
    expect(screen.getByTestId('title02')).toHaveClass('title');
    expect(screen.getByTestId('tran-id')).toHaveClass('label');
    expect(screen.getByTestId('cur-date')).toHaveClass('label');
    expect(screen.getByTestId('cur-time')).toHaveClass('label');
  });

  it('formats date as MM/DD/YY and time as 24-hour HH:MM:SS', () => {
    const morning = new Date(2026, 6, 21, 9, 5, 3);
    expect(formatDate(morning)).toBe('07/21/26');
    expect(formatTime(morning)).toBe('09:05:03');
    const night = new Date(2026, 11, 3, 23, 59, 7);
    expect(formatDate(night)).toBe('12/03/26');
    expect(formatTime(night)).toBe('23:59:07');
  });

  it('shows a static snapshot that does not tick as the clock advances', () => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date(2026, 6, 21, 9, 5, 3));
    render(<Header />);
    expect(screen.getByTestId('cur-time')).toHaveTextContent('09:05:03');
    // Advancing the mocked clock must not change the mount-time snapshot.
    act(() => {
      jest.advanceTimersByTime(1000);
    });
    expect(screen.getByTestId('cur-time')).toHaveTextContent('09:05:03');
  });
});
