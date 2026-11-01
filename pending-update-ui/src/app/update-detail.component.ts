import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiError, ApiErrorKind, StatusApi } from './status-api';
import { Decision, Row, StatusView, toStatusView } from './models';
import { StatusBadgeComponent } from './status-badge.component';
import { SummaryPanelComponent } from './summary-panel.component';

type DetailState =
  | { kind: 'loading' }
  | { kind: 'feature-off' } // NFR-41: render nothing
  | { kind: 'error'; error: ApiErrorKind }
  | { kind: 'loaded'; row: Row; view: StatusView }
  | { kind: 'refreshing'; row: Row }; // FR-32

/**
 * Detail for one EF (route "/ef/:id"). FR-26 baseline to pendingTarget as ONE cumulative
 * update, FR-4/FR-27 accept/decline, FR-32 refreshing then refreshed row, FR-25 errors are never "up to date".
 */
@Component({
  selector: 'app-update-detail',
  imports: [RouterLink, StatusBadgeComponent, SummaryPanelComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let s = state();
    @if (s.kind !== 'feature-off') {
      <article aria-labelledby="detail-title">
        <p><a routerLink="/">Back to list</a></p>
        <h2 id="detail-title">{{ id() }}</h2>
        <p class="note" role="status" aria-live="polite">{{ live() }}</p>

        @if (decisionError(); as m) { <div class="warn" role="alert">{{ m }}</div> }

        @switch (s.kind) {
          @case ('loading') { <p><span class="spin" aria-hidden="true"></span>Loading…</p> }
          @case ('error') {
            <div class="warn" role="alert">
              @switch (s.error) {
                @case ('not-found') { <p>This Engagement File was not found.</p> }
                @case ('unauthorized') { <p>You are not signed in. Sign in and try again.</p> }
                @default { <p>Status is unavailable. The status is unknown; this is not a confirmation that the EF is up to date.</p> }
              }
              @if (s.error !== 'not-found') { <button type="button" (click)="load()">Retry</button> }
            </div>
          }
          @case ('refreshing') {
            <p class="meta">{{ s.row.template ?? 'Template not recorded' }}</p>
            <p><span class="spin" aria-hidden="true"></span>Refreshing status…</p>
          }
          @case ('loaded') {
            <p class="meta">{{ s.row.template ?? 'Template not recorded' }}</p>
            <p><app-status-badge [status]="s.view.kind" /></p>
            @switch (s.view.kind) {
              @case ('update-pending') {
                <div class="ver">
                  <span>Baseline {{ s.view.baseline }}</span>
                  <span class="arrow" aria-hidden="true">→</span>
                  <span>Latest eligible {{ s.view.target }}</span>
                </div>
                <p class="meta">Presented as one cumulative update from the baseline.</p>
                <app-summary-panel [efId]="s.row.ef" />
                <div class="btns">
                  <button type="button" class="primary" (click)="decide('accept', s.view.target)">Accept {{ s.view.target }}</button>
                  <button type="button" (click)="decide('decline', s.view.target)">Decline {{ s.view.target }}</button>
                </div>
              }
              @case ('unknown') {
                <div class="warn">Status could not be determined. This is not a confirmation that the EF is up to date.</div>
              }
              @case ('up-to-date') {
                <p class="meta">@if (s.row.baseline) {Baseline {{ s.row.baseline }}. }No pending update.</p>
              }
            }
          }
        }
      </article>
    }
  `,
})
export class UpdateDetailComponent {
  private readonly api = inject(StatusApi);
  readonly id = input.required<string>();
  protected readonly state = signal<DetailState>({ kind: 'loading' });
  protected readonly decisionError = signal<string | null>(null);
  protected readonly live = signal('');
  private sub?: Subscription;

  constructor() {
    effect(() => {
      this.id();
      untracked(() => this.load());
    });
    inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
  }

  protected load(): void {
    this.fetch(false);
  }

  private fetch(afterDecision: boolean): void {
    this.sub?.unsubscribe();
    if (!afterDecision) this.state.set({ kind: 'loading' });
    this.sub = this.api.get(this.id()).subscribe({
      next: (row) => {
        this.state.set({ kind: 'loaded', row, view: toStatusView(row) });
        if (afterDecision) this.live.set('Status refreshed.');
      },
      error: (e: unknown) => {
        const kind = e instanceof ApiError ? e.kind : 'unavailable';
        this.state.set(kind === 'feature-off' ? { kind: 'feature-off' } : { kind: 'error', error: kind });
      },
    });
  }

  /** The body sent is only the version (spec 04). */
  protected decide(decision: Decision, version: string): void {
    const s = this.state();
    if (s.kind !== 'loaded') return;
    this.decisionError.set(null);
    this.state.set({ kind: 'refreshing', row: s.row });
    this.live.set('Refreshing status…');
    this.sub?.unsubscribe();
    this.sub = this.api.decide(this.id(), decision, version).subscribe({
      // Whether RECORDED or DUPLICATE, re-read the authoritative row (FR-32).
      next: () => this.fetch(true),
      error: (e: unknown) => {
        const kind = e instanceof ApiError ? e.kind : 'unavailable';
        if (kind === 'feature-off') return this.state.set({ kind: 'feature-off' });
        if (kind === 'stale') {
          // The pending target changed or the EF is no longer pending: show the authoritative row, never assume.
          this.decisionError.set('This update changed. Reloading the current status.');
          this.fetch(true);
          return;
        }
        this.decisionError.set(
          kind === 'not-found' ? 'This Engagement File was not found.'
          : kind === 'unknown-version' ? 'That version is no longer valid. Reload to see the current update.'
          : kind === 'unauthorized' ? 'You are not signed in.'
          : 'The decision could not be recorded. Nothing was changed. Try again.',
        );
        this.state.set({ kind: 'loaded', row: s.row, view: toStatusView(s.row) });
        this.live.set('');
      },
    });
  }
}
