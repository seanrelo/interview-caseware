import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import axe from 'axe-core';
import { PendingListComponent } from './pending-list.component';
import { SummaryPanelComponent } from './summary-panel.component';
import { readySummary, row, testProviders } from './testing';

// Accessibility smoke checks (axe-core). jsdom cannot compute colour contrast, so that rule is disabled;
// contrast was chosen from the mockup tokens and is not verified here.
async function violations(el: HTMLElement) {
  document.body.appendChild(el);
  const r = await axe.run(el, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } });
  el.remove();
  return r.violations.map((v) => `${v.id}: ${v.help}`);
}

describe('accessibility (axe-core)', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: testProviders() }));

  it('NFR-14 list with rows has no violations', async () => {
    const http = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(PendingListComponent);
    await f.whenStable();
    http.expectOne('/api/v1/pending-updates?size=50').flush({
      rows: [row(), row({ ef: 'ef-2', status: 'UNKNOWN', baseline: null, pendingTarget: null })],
      nextToken: null, totals: { upToDate: 0, updatePending: 1, unknown: 1 },
    });
    await f.whenStable();
    expect(await violations(f.nativeElement)).toEqual([]);
  });

  it('FR-43 READY summary panel has no violations', async () => {
    const http = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(SummaryPanelComponent);
    f.componentRef.setInput('efId', 'ef-1');
    await f.whenStable();
    http.expectOne('/api/v1/pending-updates/ef-1/summary').flush(readySummary());
    await f.whenStable();
    expect(await violations(f.nativeElement)).toEqual([]);
  });
});
