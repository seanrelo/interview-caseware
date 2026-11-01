import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { Subscription } from 'rxjs';
import { ApiError, StatusApi } from './status-api';
import { ReadySummary } from './models';

type PanelState =
  | { kind: 'loading' }
  | { kind: 'hidden' } // 404: panel hidden
  | { kind: 'generating' } // FR-34: no timeout
  | { kind: 'unavailable'; message: string } // FR-42
  | { kind: 'ready'; summary: ReadySummary };

/**
 * Summary panel (spec 05). FR-43 AI-generated marker (constant text, always shown for READY, independent of the server label), FR-39 statement markers reveal the
 * linked diff item, FR-40/NFR-16 audit block, FR-42 unavailable while status stays pending.
 * All server text goes through interpolation, never innerHTML.
 */
@Component({
  selector: 'app-summary-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let s = state();
    @if (s.kind !== 'hidden') {
      <section class="panel" aria-live="polite" aria-label="Summary of changes">
        @switch (s.kind) {
          @case ('loading') { <p><span class="spin" aria-hidden="true"></span>Loading summary…</p> }
          @case ('generating') {
            <p><span class="spin" aria-hidden="true"></span>Generating summary…</p>
            <button type="button" (click)="load()">Check again</button>
          }
          @case ('unavailable') {
            <div class="unavail"><strong>{{ s.message }}.</strong> The update is still pending.</div>
          }
          @case ('ready') {
            <p><span class="ai"><span aria-hidden="true">✨</span> AI-generated</span>@if (s.summary.label && s.summary.label !== 'AI-generated') { <span class="meta"> ({{ s.summary.label }})</span> }</p>
            <ol class="stmts">
              @for (st of s.summary.statements; track $index) {
                <li>
                  {{ st.text }}
                  @for (id of st.diffItemIds; track id) {
                    <button type="button" class="ref" [attr.aria-expanded]="open() === $index + ':' + id"
                            [attr.aria-label]="'Show changed item ' + id" (click)="toggle($index + ':' + id)">[{{ id }}]</button>
                  }
                  @for (id of st.diffItemIds; track id) {
                    @if (open() === $index + ':' + id) {
                      <p class="reveal">
                        @let d = diffItem(s.summary, id);
                        @if (d) { <strong>{{ d.section }}</strong>: {{ d.change }} } @else { Linked item not available. }
                      </p>
                    }
                  }
                </li>
              }
            </ol>
            <details>
              <summary>Audit details</summary>
              <dl class="dl">
                <dt>Model</dt><dd>{{ s.summary.model }}</dd>
                <dt>Prompt version</dt><dd>{{ s.summary.promptVersion }}</dd>
                <dt>Generated</dt><dd>{{ s.summary.generatedAt }}</dd>
                <dt>Range</dt><dd>{{ s.summary.baseline }} → {{ s.summary.target }}</dd>
                <dt>Record</dt><dd>{{ s.summary.generationId }}</dd>
              </dl>
            </details>
          }
        }
      </section>
    }
  `,
})
export class SummaryPanelComponent {
  private readonly api = inject(StatusApi);
  readonly efId = input.required<string>();
  protected readonly state = signal<PanelState>({ kind: 'loading' });
  protected readonly open = signal<string | null>(null);
  private sub?: Subscription;

  constructor() {
    effect(() => {
      this.efId();
      untracked(() => this.load());
    });
    inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
  }

  protected load(): void {
    this.sub?.unsubscribe();
    this.open.set(null);
    this.state.set({ kind: 'loading' });
    this.sub = this.api.summary(this.efId()).subscribe({
      next: (r) => {
        switch (r.state) {
          case 'READY': this.state.set({ kind: 'ready', summary: r }); break;
          case 'GENERATING': this.state.set({ kind: 'generating' }); break;
          case 'UNAVAILABLE': this.state.set({ kind: 'unavailable', message: r.message || 'Summary unavailable' }); break;
          default: this.state.set({ kind: 'unavailable', message: 'Summary unavailable' });
        }
      },
      error: (e: unknown) =>
        this.state.set(
          e instanceof ApiError && (e.kind === 'not-found' || e.kind === 'feature-off')
            ? { kind: 'hidden' }
            : { kind: 'unavailable', message: 'Summary unavailable' },
        ),
    });
  }

  protected toggle(key: string): void {
    this.open.update((o) => (o === key ? null : key));
  }

  protected diffItem(s: ReadySummary, id: string) {
    return s.diff.find((d) => d.id === id);
  }
}
