import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError, ApiErrorKind, StatusApi } from './status-api';
import { PendingPage, StatusKind, Totals, toStatusView } from './models';
import { StatusBadgeComponent } from './status-badge.component';

type ListState =
  | { kind: 'loading' }
  | { kind: 'feature-off' }
  | { kind: 'error'; error: ApiErrorKind }
  | { kind: 'loaded'; page: PendingPage };

type Filter = 'all' | StatusKind;

/**
 * Smart component for the list (route "/"). FR-22..FR-26, NFR-14 (paged, 50 per page),
 * NFR-41 (feature off renders nothing), FR-25 (error is never "up to date").
 */
@Component({
  selector: 'app-pending-list',
  imports: [RouterLink, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let s = state();
    @if (s.kind !== 'feature-off') {
      <section aria-labelledby="list-title">
        <h2 id="list-title">Engagement Files</h2>
        <p class="note" role="status" aria-live="polite">{{ live() }}</p>

        @switch (s.kind) {
          @case ('loading') {
            <ul class="list" aria-busy="true" aria-label="Loading Engagement Files">
              @for (i of skeleton; track i) { <li class="skeleton"></li> }
            </ul>
          }
          @case ('error') {
            <div class="warn" role="alert">
              @if (s.error === 'unauthorized') {
                <p>You are not signed in. Sign in and try again.</p>
              } @else {
                <p>Status is unavailable. Statuses are unknown; this is not a confirmation that anything is up to date.</p>
              }
              <button type="button" (click)="load()">Retry</button>
            </div>
          }
          @case ('loaded') {
            <div class="filters" role="group" aria-label="Filter by status (applies to the loaded page only)">
              @for (f of filters(); track f.key) {
                <button type="button" [attr.aria-pressed]="filter() === f.key" (click)="filter.set(f.key)">
                  {{ f.label }} ({{ f.count }})
                </button>
              }
            </div>
            @if (filter() !== 'all') {
              <p class="note">Filter applies to the loaded page only: it narrows the {{ s.page.rows.length }} EFs on this page. Counts are for all EFs.</p>
            } @else {
              <p class="note">Counts are for all EFs; a filter would only narrow the {{ s.page.rows.length }} EFs on this page.</p>
            }

            <ul class="list" aria-label="Engagement Files">
              @for (row of visible(); track row.row.ef) {
                <li class="card">
                  <div>
                    <h3>{{ row.row.ef }}</h3>
                    <p class="meta">{{ row.row.template ?? 'Template not recorded' }}@if (row.row.baseline) {, baseline {{ row.row.baseline }}}</p>
                    @switch (row.view.kind) {
                      @case ('update-pending') {
                        <p class="meta">Update {{ row.view.baseline }} → {{ row.view.target }}</p>
                      }
                      @case ('unknown') {
                        <p class="meta">Status could not be determined. This is not a confirmation that the EF is up to date.</p>
                      }
                      @case ('up-to-date') {}
                    }
                  </div>
                  <app-status-badge [status]="row.view.kind" />
                  <div class="actions">
                    <a class="btn" [class.primary]="row.view.kind === 'update-pending'" [routerLink]="['/ef', row.row.ef]"
                       [attr.aria-label]="(row.view.kind === 'update-pending' ? 'Review update for ' : 'View ') + row.row.ef">
                      {{ row.view.kind === 'update-pending' ? 'Review update' : 'View' }}
                    </a>
                  </div>
                </li>
              } @empty {
                <li class="note">{{ emptyMessage(s.page) }}</li>
              }
            </ul>

            <nav class="pager" aria-label="EF list pages">
              <button type="button" [disabled]="tokens().length === 0" (click)="previous()">Previous</button>
              <span class="note">Page {{ tokens().length + 1 }}</span>
              <button type="button" [disabled]="!s.page.nextToken" (click)="next(s.page.nextToken)">Next</button>
            </nav>
          }
        }
      </section>
    }
  `,
})
export class PendingListComponent implements OnInit {
  private readonly api = inject(StatusApi);
  protected readonly skeleton = [1, 2, 3, 4, 5];
  protected readonly state = signal<ListState>({ kind: 'loading' });
  protected readonly filter = signal<Filter>('all');
  /** Tokens of the pages before the current one (Previous pops, Next pushes). */
  protected readonly tokens = signal<(string | null)[]>([]);
  private current: string | null = null;

  protected readonly live = computed(() => {
    const s = this.state();
    switch (s.kind) {
      case 'loading': return 'Loading EF statuses…';
      case 'feature-off': return '';
      case 'error': return s.error === 'unauthorized' ? 'Not signed in.' : 'Status unavailable.';
      case 'loaded': return `${s.page.rows.length} EFs loaded.`;
    }
  });

  protected readonly visible = computed(() => {
    const s = this.state();
    if (s.kind !== 'loaded') return [];
    const f = this.filter();
    return s.page.rows
      .map((row) => ({ row, view: toStatusView(row) }))
      .filter((r) => f === 'all' || r.view.kind === f);
  });

  protected readonly filters = computed(() => {
    const s = this.state();
    const t: Totals = s.kind === 'loaded' ? s.page.totals : { upToDate: 0, updatePending: 0, unknown: 0 };
    return [
      { key: 'all' as Filter, label: 'All', count: t.upToDate + t.updatePending + t.unknown },
      { key: 'update-pending' as Filter, label: 'Update pending', count: t.updatePending },
      { key: 'up-to-date' as Filter, label: 'Up to date', count: t.upToDate },
      { key: 'unknown' as Filter, label: 'Unknown', count: t.unknown },
    ];
  });

  protected emptyMessage(page: PendingPage): string {
    if (page.rows.length === 0) return 'No Engagement Files.';
    const f = this.filters().find((x) => x.key === this.filter());
    const total = f?.count ?? 0;
    if (page.nextToken && total > 0) {
      return `No rows of this status on this page. ${total} in total; use Next to look further.`;
    }
    return 'No EFs on this page with this status.';
  }

  ngOnInit(): void {
    this.load();
  }

  protected load(token: string | null = this.current): void {
    this.current = token;
    this.state.set({ kind: 'loading' });
    this.api.list(token).subscribe({
      next: (page) => this.state.set({ kind: 'loaded', page }),
      error: (e: unknown) => {
        const kind = e instanceof ApiError ? e.kind : 'unavailable';
        this.state.set(kind === 'feature-off' ? { kind: 'feature-off' } : { kind: 'error', error: kind });
      },
    });
  }

  protected next(token: string | null): void {
    if (!token) return;
    this.tokens.update((t) => [...t, this.current]);
    this.load(token);
  }

  protected previous(): void {
    const t = this.tokens();
    if (t.length === 0) return;
    this.tokens.set(t.slice(0, -1));
    this.load(t[t.length - 1]);
  }
}
