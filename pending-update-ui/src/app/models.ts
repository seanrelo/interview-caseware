/** Wire types for the approved API contract (specs 04 and 05). No EF content beyond what the API returns. */

export type WireStatus = 'UP_TO_DATE' | 'UPDATE_PENDING' | 'UNKNOWN';

export interface Row {
  ef: string;
  template: string | null;
  status: WireStatus;
  baseline: string | null;
  pendingTarget: string | null;
  updatedAt: string;
  source: string;
}

export interface Totals {
  upToDate: number;
  updatePending: number;
  unknown: number;
}

export interface PendingPage {
  rows: Row[];
  nextToken: string | null;
  totals: Totals;
}

export interface DiffItem {
  id: string;
  section: string;
  change: string;
}

export interface Statement {
  text: string;
  diffItemIds: string[];
}

export interface ReadySummary {
  state: 'READY';
  label: string;
  generationId: string;
  generatedAt: string;
  model: string;
  promptVersion: string;
  baseline: string;
  target: string;
  statements: Statement[];
  diff: DiffItem[];
}

export type SummaryResponse =
  | ReadySummary
  | { state: 'GENERATING' }
  | { state: 'UNAVAILABLE'; message: string };

export interface DecisionResult {
  result: 'RECORDED' | 'DUPLICATE';
  row: Row | null;
}

export type Decision = 'accept' | 'decline';

/**
 * Status as a discriminated union so the compiler forces every case to be handled
 * (FR-22). Anything that is not positively known maps to `unknown`, never to
 * `up-to-date` (FR-24, FR-25).
 */
export type StatusView =
  | { kind: 'up-to-date' }
  | { kind: 'update-pending'; baseline: string; target: string }
  | { kind: 'unknown' };

export type StatusKind = StatusView['kind'];

export function toStatusView(row: Row): StatusView {
  switch (row.status) {
    case 'UP_TO_DATE':
      return { kind: 'up-to-date' };
    case 'UPDATE_PENDING':
      // A pending row without both versions cannot be presented as one update; do not guess.
      return row.baseline && row.pendingTarget
        ? { kind: 'update-pending', baseline: row.baseline, target: row.pendingTarget }
        : { kind: 'unknown' };
    case 'UNKNOWN':
      return { kind: 'unknown' };
    default:
      // Unrecognised value from the server: never "up to date" (FR-25).
      return { kind: 'unknown' };
  }
}
