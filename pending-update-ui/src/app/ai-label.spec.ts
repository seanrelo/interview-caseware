import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SummaryPanelComponent } from './summary-panel.component';
import { readySummary, testProviders } from './testing';

/** FR-43: the AI-generated marker never depends on the server label string. */
describe('AI-generated marker', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
  });

  it('FR-43 a READY summary still carries the AI-generated marker when the server label is empty', async () => {
    const f = TestBed.createComponent(SummaryPanelComponent);
    f.componentRef.setInput('efId', 'ef-1');
    await f.whenStable();
    http.expectOne('/api/v1/pending-updates/ef-1/summary').flush(readySummary({ label: '' }));
    await f.whenStable();
    expect((f.nativeElement as HTMLElement).textContent).toMatch(/AI-generated/i);
  });
});
