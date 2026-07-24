/**
 * :module: ``frontend/src/api/reports.ts``
 * :purpose: Domain API module for the report-request screen ``ReportPage`` (BMS
 *   mapset ``CORPT00``). It wraps the single report route exposed by the
 *   api-gateway and re-expresses the legacy CICS transaction ``CR00`` / program
 *   ``CORPT00C`` — which built a JCL stream and wrote it to the extra-partition
 *   TDQ ``'JOBS'`` for asynchronous JES submission — as one REST call. On the
 *   backend that ``POST /reports`` triggers an asynchronous Spring Batch
 *   ``JobLauncher`` submission (AAP 0.4.4); this client therefore only submits
 *   the request and surfaces the acknowledgement, exactly as the legacy screen
 *   re-displayed after writing to the TDQ (``app/cbl/CORPT00C.cbl``).
 * :output: The named export ``requestReport`` — an ``async`` function that issues
 *   ``POST /reports`` through the shared ``apiClient`` and resolves to the
 *   report-request response body.
 * :note: This module is a thin, stateless data-layer carrier. It performs no
 *   client-side polling, blocking, or waiting for job completion (the launch is
 *   fire-and-acknowledge on the backend) and no reformatting of the request:
 *   the ``ReportRequestDto`` — including any ``YYYY-MM-DD``-derived custom date
 *   parts — is forwarded to the wire untouched. HTTP-error normalization is
 *   owned by the ``apiClient`` response interceptor, so failures propagate as an
 *   ``ApiError`` to the calling page / hook rather than being caught here.
 *   Rationale for the async-launch mapping lives in ``docs/decision-log.md``.
 */

import apiClient from './client';
import type { ReportRequestDto, ReportResponseDto } from '../types';

/**
 * :purpose: Submit a transaction-report request (CICS ``CR00`` / program
 *   ``CORPT00C``). Sends ``POST /reports`` with the report-window selection and,
 *   for the custom window, the decomposed start / end date parts, and returns
 *   the reporting service's acknowledgement. The backend launches the
 *   report-generation batch job asynchronously (the legacy TDQ ``'JOBS'`` -> JES
 *   submission becomes a Spring Batch ``JobLauncher`` invocation), so this call
 *   is fire-and-acknowledge: it neither polls nor blocks for job completion —
 *   any status polling is a page / hook concern, not this carrier's.
 * :param request: the ``ReportRequestDto`` payload carrying the report-window
 *   selector flags and, when the custom window is chosen, the start / end date
 *   parts. It is forwarded to the request body verbatim — no date reformatting
 *   or field remapping is performed here.
 * :returns: a ``Promise`` resolving to the ``ReportResponseDto`` submission
 *   acknowledgement (the echoed selection plus the status / message line the
 *   reporting service populates).
 */
export async function requestReport(
  request: ReportRequestDto,
): Promise<ReportResponseDto> {
  const response = await apiClient.post<ReportResponseDto>('/reports', request);
  return response.data;
}
