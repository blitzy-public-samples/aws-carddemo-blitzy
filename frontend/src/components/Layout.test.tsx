import { render, screen, act } from '@testing-library/react';
// Jest's ESM runtime does not inject ``jest`` as a global (unlike describe/it/
// expect), so it is imported explicitly.
import { jest } from '@jest/globals';
import { useEffect } from 'react';
import type { ReactElement } from 'react';
import Layout, { useScreenChrome } from './Layout';
import { PfKeyAction } from '../types';
import { __setSession } from '../hooks/useSession';

/**
 * A minimal page that publishes screen chrome through the Layout context,
 * exercising the same flow real page components use.
 */
function ChromePublisher(): ReactElement {
  const { setChrome } = useScreenChrome();
  useEffect(() => {
    setChrome({
      transactionId: 'CAUP',
      programName: 'COACTUPC',
      title01: 'Account Update',
      errorMessage: 'Record changed by some one else. Please review',
      pfKeys: [{ action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: jest.fn() }],
    });
  }, [setChrome]);
  return <div data-testid="page-body">page content</div>;
}

describe('Layout', () => {
  afterEach(() => {
    act(() => {
      __setSession(null, null);
    });
  });

  it('renders the header, routed content, error region, and key bar', () => {
    render(
      <Layout>
        <div data-testid="page-body">hello</div>
      </Layout>,
    );
    expect(screen.getByText('Tran :')).toBeInTheDocument();
    expect(screen.getByTestId('page-body')).toHaveTextContent('hello');
    expect(screen.getByRole('toolbar', { name: 'Function keys' })).toBeInTheDocument();
  });

  it('lets a page publish chrome (title, tran/prog, message, keys) via useScreenChrome', () => {
    render(
      <Layout>
        <ChromePublisher />
      </Layout>,
    );
    expect(screen.getByTestId('tran-id')).toHaveTextContent('CAUP');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COACTUPC');
    expect(screen.getByTestId('title01')).toHaveTextContent('Account Update');
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Record changed by some one else. Please review',
    );
    expect(screen.getByRole('button', { name: 'F3=Exit' })).toBeInTheDocument();
  });

  it('reflects authentication state via the data-authenticated attribute', () => {
    const { container } = render(
      <Layout>
        <div>content</div>
      </Layout>,
    );
    expect(container.querySelector('.screen')).toHaveAttribute('data-authenticated', 'false');
    act(() => {
      __setSession('USER01', 'U');
    });
    expect(container.querySelector('.screen')).toHaveAttribute('data-authenticated', 'true');
  });
});
