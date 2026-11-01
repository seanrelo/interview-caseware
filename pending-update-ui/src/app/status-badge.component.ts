import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { StatusKind } from './models';

const LABELS: Record<StatusKind, { icon: string; text: string }> = {
  'up-to-date': { icon: '✔', text: 'Up to date' },
  'update-pending': { icon: '●', text: 'Update pending' },
  unknown: { icon: '?', text: 'Unknown' },
};

/** FR-22: one status badge; text plus icon, never colour alone. */
@Component({
  selector: 'app-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge" [class]="cls()"><span aria-hidden="true">{{ label().icon }}</span> {{ label().text }}</span>`,
})
export class StatusBadgeComponent {
  readonly status = input.required<StatusKind>();
  protected readonly label = computed(() => LABELS[this.status()]);
  protected readonly cls = computed(
    () => ({ 'up-to-date': 's-ok', 'update-pending': 's-pending', unknown: 's-unknown' })[this.status()],
  );
}
