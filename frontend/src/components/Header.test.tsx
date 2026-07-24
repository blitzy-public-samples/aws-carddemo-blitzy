/**
 * Header component tests.
 *
 * :purpose: Verify the BMS-faithful header (``app/bms/COSGN00.bms``): the
 *     ``Tran :`` / ``Prog :`` / ``Date :`` / ``Time :`` labels and their values,
 *     the BLUE ``.label`` value styling and YELLOW ``.title`` heading styling, the
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

  it('renders both header rows with labels and provided values', () => {
    render(
      <Header transactionId="CC00" programName="COSGN00C" title01="Sign On" title02="CardDemo" />,
    );
    expect(screen.getByText('Tran :')).toBeInTheDocument();
    expect(screen.getByText('Prog :')).toBeInTheDocument();
    expect(screen.getByText('Date :')).toBeInTheDocument();
    expect(screen.getByText('Time :')).toBeInTheDocument();
    expect(screen.getByTestId('tran-id')).toHaveTextContent('CC00');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COSGN00C');
    expect(screen.getByTestId('title01')).toHaveTextContent('Sign On');
    expect(screen.getByTestId('title02')).toHaveTextContent('CardDemo');
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
