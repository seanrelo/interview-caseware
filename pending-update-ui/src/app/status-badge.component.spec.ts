import { TestBed } from '@angular/core/testing';
import { StatusKind } from './models';
import { StatusBadgeComponent } from './status-badge.component';

describe('StatusBadgeComponent', () => {
  const cases: [StatusKind, string, string][] = [
    ['up-to-date', '✔', 'Up to date'],
    ['update-pending', '●', 'Update pending'],
    ['unknown', '?', 'Unknown'],
  ];
  for (const [kind, icon, text] of cases) {
    it(`UI-22 FR-22 shows text and icon for ${kind}`, async () => {
      const f = TestBed.createComponent(StatusBadgeComponent);
      f.componentRef.setInput('status', kind);
      await f.whenStable();
      const el: HTMLElement = f.nativeElement;
      expect(el.textContent).toContain(text);
      expect(el.querySelector('[aria-hidden="true"]')?.textContent).toBe(icon);
    });
  }
});
