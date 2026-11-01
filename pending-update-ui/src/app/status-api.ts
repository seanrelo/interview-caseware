import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
import { environment } from '../environments/environment';
import { DecisionResult, PendingPage, Row, SummaryResponse } from './models';

export type ApiErrorKind =
  | 'feature-off' // 404 FEATURE_OFF: render nothing (NFR-41)
  | 'not-found' // 404 NOT_FOUND
  | 'unauthorized' // 401
  | 'unknown-version' // 422 UNKNOWN_VERSION
  | 'stale' // 422 STALE_OR_NOT_PENDING: the pending target changed or the EF is no longer pending
  | 'unavailable'; // 503 and anything else: unknown, never up to date (FR-25)

export class ApiError extends Error {
  constructor(readonly kind: ApiErrorKind) {
    super(kind);
  }
}

/** Typed API client behind an interface (an abstract class doubles as the DI token). */
@Injectable({ providedIn: 'root', useFactory: () => inject(HttpStatusApi) })
export abstract class StatusApi {
  abstract list(token: string | null, size?: number): Observable<PendingPage>;
  abstract get(efId: string): Observable<Row>;
  abstract summary(efId: string): Observable<SummaryResponse>;
  /** The body carries only the version (never a firm or template). */
  abstract decide(efId: string, decision: 'accept' | 'decline', version: string): Observable<DecisionResult>;
}

function toApiError(e: unknown): ApiError {
  if (!(e instanceof HttpErrorResponse)) return new ApiError('unavailable');
  const code = (e.error as { error?: string } | null)?.error;
  if (e.status === 401) return new ApiError('unauthorized');
  if (e.status === 404) return new ApiError(code === 'FEATURE_OFF' ? 'feature-off' : 'not-found');
  if (e.status === 422 && code === 'UNKNOWN_VERSION') return new ApiError('unknown-version');
  if (e.status === 422 && code === 'STALE_OR_NOT_PENDING') return new ApiError('stale');
  return new ApiError('unavailable');
}

@Injectable({ providedIn: 'root' })
export class HttpStatusApi extends StatusApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBase}/pending-updates`;

  private wrap<T>(o: Observable<T>): Observable<T> {
    return o.pipe(catchError((e) => throwError(() => toApiError(e))));
  }

  list(token: string | null, size = 50): Observable<PendingPage> {
    const params: Record<string, string> = { size: String(size) };
    if (token) params['token'] = token;
    return this.wrap(this.http.get<PendingPage>(this.base, { params }));
  }

  get(efId: string): Observable<Row> {
    return this.wrap(this.http.get<Row>(`${this.base}/${encodeURIComponent(efId)}`));
  }

  summary(efId: string): Observable<SummaryResponse> {
    return this.wrap(this.http.get<SummaryResponse>(`${this.base}/${encodeURIComponent(efId)}/summary`));
  }

  decide(efId: string, decision: 'accept' | 'decline', version: string): Observable<DecisionResult> {
    return this.wrap(
      this.http.post<DecisionResult>(`${this.base}/${encodeURIComponent(efId)}/${decision}`, { version }),
    );
  }
}
