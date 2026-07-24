/**
 * Header component tests.
 *
 * :purpose: Verify the BMS-faithful three-row header structure (MJ-02), the
 *     static (non-ticking) server-snapshot timestamp behavior (MJ-20), and the
 *     h1/h2 screen-title heading semantics (MN-05).
 */
import { render, screen, act } from '@testing-library/react';
// Under Jest's ESM runtime the ``jest`` object is not injected as a global (unlike
// describe/it/expect) and must be imported explicitly.
import { jest } from '@jest/globals';
import Header, { formatDate, formatTime } from './Header';

describe('Header', () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  it('renders the two required header rows with their fields and labels (MJ-02)', () => {
    render(
      <Header
        transactionId="CC00"
        programName="COSGN00C"
        title01="AWS Mainframe Modernization"
        title02="CardDemo"
        currentDate="03/09/24"
        currentTime="14:05:07"
      />,
    );

    expect(screen.getByTestId('tran-id')).toHaveTextContent('CC00');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COSGN00C');
    expect(screen.getByTestId('title01')).toHaveTextContent('AWS Mainframe Modernization');
    expect(screen.getByTestId('title02')).toHaveTextContent('CardDemo');
    expect(screen.getByTestId('cur-date')).toHaveTextContent('03/09/24');
    expect(screen.getByTestId('cur-time')).toHaveTextContent('14:05:07');

    // Labels are preserved verbatim from COSGN00.bms.
    expect(screen.getByText('Tran :')).toBeInTheDocument();
    expect(screen.getByText('Prog :')).toBeInTheDocument();
    expect(screen.getByText('Date :')).toBeInTheDocument();
    expect(screen.getByText('Time :')).toBeInTheDocument();
  });

  it('renders the two title lines as h1/h2 heading semantics (MN-05)', () => {
    render(<Header title01="AWS Mainframe Modernization" title02="CardDemo" />);

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      'AWS Mainframe Modernization',
    );
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('CardDemo');
  });

  it('omits the optional AppID/SysID row until an id is supplied, then renders it (MJ-02)', () => {
    const { rerender } = render(<Header transactionId="CC00" />);

    expect(screen.queryByTestId('app-sys-row')).not.toBeInTheDocument();
    expect(screen.queryByTestId('app-id')).not.toBeInTheDocument();
    expect(screen.queryByTestId('sys-id')).not.toBeInTheDocument();

    rerender(<Header transactionId="CC00" appId="CARDDEMO" sysId="CICS1" />);

    expect(screen.getByTestId('app-sys-row')).toBeInTheDocument();
    expect(screen.getByTestId('app-id')).toHaveTextContent('CARDDEMO');
    expect(screen.getByTestId('sys-id')).toHaveTextContent('CICS1');
    expect(screen.getByText('AppID:')).toBeInTheDocument();
    expect(screen.getByText('SysID:')).toBeInTheDocument();
  });

  it('prefers server-supplied date/time props (MJ-20)', () => {
    render(<Header currentDate="01/02/03" currentTime="04:05:06" />);

    expect(screen.getByTestId('cur-date')).toHaveTextContent('01/02/03');
    expect(screen.getByTestId('cur-time')).toHaveTextContent('04:05:06');
  });

  it('shows a static snapshot that does not tick when no timestamp props are supplied (MJ-20)', () => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date(2024, 2, 9, 14, 5, 7)); // 2024-03-09 14:05:07 local

    render(<Header />);
    expect(screen.getByTestId('cur-date')).toHaveTextContent('03/09/24');
    expect(screen.getByTestId('cur-time')).toHaveTextContent('14:05:07');

    // With the live clock removed, advancing time must not change the display.
    act(() => {
      jest.advanceTimersByTime(5000);
    });
    expect(screen.getByTestId('cur-time')).toHaveTextContent('14:05:07');
  });

  it('formats date and time like the legacy WS-CURDATE / WS-CURTIME fields', () => {
    const moment = new Date(2024, 2, 9, 14, 5, 7);
    expect(formatDate(moment)).toBe('03/09/24');
    expect(formatTime(moment)).toBe('14:05:07');
  });
});
