/**
 * :module: ``frontend/src/components/display.test.ts``
 * :purpose: Verify the shared display helpers the screens depend on for their line-23
 *     region and for placing the cursor: which message a failed call publishes, WHICH
 *     FIELD that call refused (the client half of the legacy ``MOVE -1 TO <field>L``),
 *     and the message a route guard hands to the screen it bounced the caller to.
 * :output: Jest test suite; no exports.
 */

import { ApiError } from '../api';
import type { ApiErrorResponse } from '../types';
import {
  guardScreenMessage,
  resolveApiErrorMessage,
  resolveFaultedField,
  toSuppressedAmountPicture,
} from './display';

/**
 * :purpose: Build a complete error envelope of the shape every service publishes.
 * :param status: the HTTP status.
 * :param message: the line-23 message the service carried.
 * :param extra: the envelope fields under test (``errorCode`` / ``fieldErrors``).
 * :returns: the envelope.
 */
function envelope(
  status: number,
  message: string,
  extra: Partial<ApiErrorResponse> = {},
): ApiErrorResponse {
  return {
    timestamp: '2026-08-08T00:00:00Z',
    status,
    error: 'Bad Request',
    message,
    path: '/users',
    ...extra,
  };
}

/**
 * :purpose: Build a normalized API error carrying a given envelope.
 * :param status: the HTTP status.
 * :param body: the error envelope the service returned, if any.
 * :param message: the client-side message.
 * :returns: the error a screen receives from ``useApi``.
 */
function apiError(status: number, body?: ApiErrorResponse, message = ''): ApiError {
  return new ApiError(status, message, body);
}

describe('resolveApiErrorMessage', () => {
  it("prefers the envelope's own verbatim legacy literal", () => {
    const error = apiError(400, envelope(400, 'User ID NOT found...'), 'Request failed');
    expect(resolveApiErrorMessage(error, 'fallback')).toBe('User ID NOT found...');
  });

  it('falls back to the client message, then to the supplied fallback', () => {
    expect(resolveApiErrorMessage(apiError(500, undefined, 'boom'), 'fallback')).toBe('boom');
    expect(resolveApiErrorMessage(apiError(500, undefined, ''), 'fallback')).toBe('fallback');
    expect(resolveApiErrorMessage(apiError(500, undefined, ''))).toBe('');
  });
});

describe('resolveFaultedField', () => {
  it('names the field the service refused', () => {
    const error = apiError(
      400,
      envelope(400, 'User Type must be A or U', {
        errorCode: 'VALIDATION_FAILED',
        fieldErrors: { userType: 'User Type must be A or U' },
      }),
    );
    expect(resolveFaultedField(error)).toBe('userType');
  });

  it('acts on the FIRST field, which is the edit that failed first', () => {
    // The services evaluate their edits in legacy screen order and report only the edit
    // that failed first, exactly as a COBOL EVALUATE TRUE would have.
    const error = apiError(
      400,
      envelope(400, 'First Name can NOT be empty...', {
        fieldErrors: {
          firstName: 'First Name can NOT be empty...',
          lastName: 'Last Name can NOT be empty...',
        },
      }),
    );
    expect(resolveFaultedField(error)).toBe('firstName');
  });

  it('survives an explicit null fieldErrors, which every whole-submission refusal sends', () => {
    // The service serialises the member as an explicit JSON null rather than omitting
    // it, so a guard that only tested for undefined let Object.keys(null) throw and the
    // error boundary replaced the whole screen instead of showing the message. The
    // unchanged-record outcome is the everyday way to reach this shape.
    const error = apiError(
      400,
      envelope(400, 'Please modify to update ...', {
        errorCode: 'VALIDATION_FAILED',
        fieldErrors: null,
      }),
    );
    expect(resolveFaultedField(error)).toBeNull();
  });

  it('names no field when fieldErrors is not an object at all', () => {
    expect(
      resolveFaultedField(
        apiError(400, envelope(400, 'x', { fieldErrors: 'unexpected' as never })),
      ),
    ).toBeNull();
  });

  it('names no field for a whole-submission refusal, a conflict or no error at all', () => {
    expect(
      resolveFaultedField(
        apiError(409, envelope(409, 'Record changed by some one else. Please review')),
      ),
    ).toBeNull();
    expect(resolveFaultedField(apiError(500))).toBeNull();
    expect(resolveFaultedField(null)).toBeNull();
  });

  it('names no field when the envelope carries an empty map', () => {
    expect(
      resolveFaultedField(apiError(400, envelope(400, 'x', { fieldErrors: {} }))),
    ).toBeNull();
  });
});

describe('guardScreenMessage', () => {
  it('reads the reason a route guard carried to the screen it bounced to', () => {
    expect(guardScreenMessage({ screenMessage: 'No access - Admin Only option... ' })).toBe(
      'No access - Admin Only option... ',
    );
  });

  it('trusts nothing about the shape of the navigation state', () => {
    expect(guardScreenMessage(null)).toBe('');
    expect(guardScreenMessage(undefined)).toBe('');
    expect(guardScreenMessage('a string')).toBe('');
    expect(guardScreenMessage({})).toBe('');
    expect(guardScreenMessage({ screenMessage: 42 })).toBe('');
  });
});

describe('toSuppressedAmountPicture (COACTVW PICOUT=+ZZZ,ZZZ,ZZZ.99)', () => {
  it('edits a positive value with grouping and leading-zero suppression', () => {
    expect(toSuppressedAmountPicture('4998.00')).toBe('+      4,998.00');
  });

  it('carries the minus sign for a negative value', () => {
    expect(toSuppressedAmountPicture('-919.50')).toBe('-        919.50');
  });

  it('suppresses every integer position of a zero value, group separators included', () => {
    expect(toSuppressedAmountPicture('0.00')).toBe('+           .00');
  });

  it('keeps a zero that follows a significant digit', () => {
    expect(toSuppressedAmountPicture('1000000.05')).toBe('+  1,000,000.05');
  });

  it('fills all nine integer positions when the value needs them', () => {
    expect(toSuppressedAmountPicture('999999999.99')).toBe('+999,999,999.99');
  });

  it('loses the high-order digits a nine-position receiver cannot hold', () => {
    expect(toSuppressedAmountPicture('9999999999.99')).toBe('+999,999,999.99');
  });

  it('pads a value that carries fewer decimals than the picture', () => {
    expect(toSuppressedAmountPicture('12.5')).toBe('+         12.50');
    expect(toSuppressedAmountPicture('7')).toBe('+          7.00');
  });

  it('is always exactly the fifteen characters the map field declares', () => {
    for (const value of ['0.00', '1.00', '-1.00', '123456789.99', '9999999999.99', '-0.01']) {
      expect(toSuppressedAmountPicture(value)).toHaveLength(15);
    }
  });
});
