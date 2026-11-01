import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HttpStatusApi, StatusApi } from './status-api';
import { UpdateDetailComponent } from './update-detail.component';
import { SummaryPanelComponent } from './summary-panel.component';
import { readySummary, row, testProviders } from './testing';

const ROW = '/api/v1/pending-updates/ef-1';
const SUM = `${ROW}/summary`;

describe('UpdateDetailComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  async function create(summary: object, opts: { status?: number } = {}) {
    const f = TestBed.createComponent(UpdateDetailComponent);
    f.componentRef.setInput('id', 'ef-1');
    await f.whenStable();
    http.expectOne(ROW).flush(row());
    await f.whenStable();
    const r = http.expectOne(SUM);
    if (opts.status) r.flush(summary, { status: opts.status, statusText: 'x' });
    else r.flush(summary);
    await f.whenStable();
    return f;
  }
  const text = (f: { nativeElement: HTMLElement }) => f.nativeElement.textContent!;

  it('FR-26 shows baseline to target as one cumulative update, labelled by title (UI-30)', async () => {
    const f = await create({ state: 'GENERATING' });
    expect(text(f)).toContain('Baseline v3');
    expect(text(f)).toContain('Latest eligible v6');
    expect(f.nativeElement.querySelector('article')!.getAttribute('aria-labelledby')).toBe('detail-title');
  });

  for (const decision of ['accept', 'decline'] as const) {
    it(`UI-32 FR-32 after ${decision} shows refreshing then the refreshed row; body has only {version}`, async () => {
      const f = await create({ state: 'GENERATING' });
      const btn = [...(f.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.btns button')].find((b) => b.textContent!.toLowerCase().includes(decision))!;
      btn.click();
      const post = http.expectOne(`${ROW}/${decision}`);
      expect(post.request.method).toBe('POST');
      expect(post.request.body).toEqual({ version: 'v6' });
      await f.whenStable();
      expect(text(f)).toContain('Refreshing status');
      post.flush({ result: 'RECORDED', row: null });
      http.expectOne(ROW).flush(row({ status: 'UP_TO_DATE', baseline: 'v6', pendingTarget: null }));
      await f.whenStable();
      expect(text(f)).not.toContain('Refreshing status');
      expect(text(f)).toContain('Up to date');
      expect(text(f)).toContain('No pending update');
    });
  }

  it('a 503 DECISION_UNAVAILABLE keeps the pending row and shows an error (UI-30)', async () => {
    const f = await create({ state: 'GENERATING' });
    (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.btns .primary')!.click();
    http.expectOne(`${ROW}/accept`).flush({ error: 'DECISION_UNAVAILABLE' }, { status: 503, statusText: 'x' });
    await f.whenStable();
    expect(f.nativeElement.querySelector('[role="alert"]')!.textContent).toContain('Nothing was changed');
    expect(text(f)).toContain('Update pending');
  });

  it('U4 FR-42 UNAVAILABLE shows the message while the status badge stays pending', async () => {
    const f = await create({ state: 'UNAVAILABLE', message: 'summary unavailable' });
    expect(text(f)).toContain('summary unavailable');
    expect(f.nativeElement.querySelector('article > p .badge')!.textContent).toContain('Update pending');
  });

  it('FR-25 a 503 on the row shows an error state and never "up to date", retry works', async () => {
    const f = TestBed.createComponent(UpdateDetailComponent);
    f.componentRef.setInput('id', 'ef-1');
    await f.whenStable();
    http.expectOne(ROW).flush({ error: 'STATUS_UNAVAILABLE' }, { status: 503, statusText: 'x' });
    await f.whenStable();
    expect(text(f)).not.toContain('Up to date');
    (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('[role="alert"] button')!.click();
    http.expectOne(ROW).flush(row({ status: 'UNKNOWN', baseline: null, pendingTarget: null }));
    await f.whenStable();
    expect(f.nativeElement.querySelector('.badge')!.textContent).toContain('Unknown');
  });

  it('UI-41 a 404 FEATURE_OFF renders nothing', async () => {
    const f = TestBed.createComponent(UpdateDetailComponent);
    f.componentRef.setInput('id', 'ef-1');
    await f.whenStable();
    http.expectOne(ROW).flush({ error: 'FEATURE_OFF' }, { status: 404, statusText: 'x' });
    await f.whenStable();
    expect(text(f).trim()).toBe('');
  });

  it('422 STALE_OR_NOT_PENDING shows the message, re-reads the row and shows the refreshed status (FR-27, FR-32)', async () => {
    const f = await create({ state: 'GENERATING' });
    (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.btns .primary')!.click();
    http.expectOne(`${ROW}/accept`).flush({ error: 'STALE_OR_NOT_PENDING' }, { status: 422, statusText: 'x' });
    await f.whenStable();
    expect(text(f)).toContain('This update changed. Reloading the current status.');
    http.expectOne(ROW).flush(row({ pendingTarget: 'v7' }));
    await f.whenStable();
    http.expectOne(SUM).flush({ state: 'GENERATING' });
    await f.whenStable();
    expect(text(f)).toContain('Latest eligible v7');
    expect(text(f)).toContain('Update pending');
    expect(text(f)).not.toContain('Refreshing status…');
    expect((f.nativeElement as HTMLElement).querySelectorAll('.btns button').length).toBe(2);
  });

  it('422 STALE_OR_NOT_PENDING then a row that is no longer pending says up to date only because the row says so', async () => {
    const f = await create({ state: 'GENERATING' });
    (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.btns .primary')!.click();
    http.expectOne(`${ROW}/accept`).flush({ error: 'STALE_OR_NOT_PENDING' }, { status: 422, statusText: 'x' });
    await f.whenStable();
    expect(text(f)).not.toContain('Up to date');
    http.expectOne(ROW).flush(row({ status: 'UP_TO_DATE', baseline: 'v6', pendingTarget: null }));
    await f.whenStable();
    expect(text(f)).toContain('Up to date');
  });

  it('422 STALE_OR_NOT_PENDING followed by a failing re-read never shows up to date (FR-25)', async () => {
    const f = await create({ state: 'GENERATING' });
    (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.btns .primary')!.click();
    http.expectOne(`${ROW}/accept`).flush({ error: 'STALE_OR_NOT_PENDING' }, { status: 422, statusText: 'x' });
    await f.whenStable();
    http.expectOne(ROW).flush({ error: 'STATUS_UNAVAILABLE' }, { status: 503, statusText: 'x' });
    await f.whenStable();
    expect(text(f)).not.toContain('Up to date');
    expect(text(f)).toContain('Status is unavailable');
  });

  it('422 UNKNOWN_VERSION still shows its message and keeps the row', async () => {
    const f = await create({ state: 'GENERATING' });
    (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.btns .primary')!.click();
    http.expectOne(`${ROW}/accept`).flush({ error: 'UNKNOWN_VERSION' }, { status: 422, statusText: 'x' });
    await f.whenStable();
    expect(text(f)).toContain('That version is no longer valid');
    expect(text(f)).toContain('Update pending');
  });

  it('a double click on Accept sends exactly one POST (second click ignored while not loaded)', async () => {
    const f = await create({ state: 'GENERATING' });
    const btn = (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.btns .primary')!;
    btn.click();
    btn.click();
    const posts = http.match((r) => r.method === 'POST');
    expect(posts.length).toBe(1);
    posts[0].flush({ result: 'RECORDED', row: null });
    http.expectOne(ROW).flush(row({ status: 'UP_TO_DATE', baseline: 'v6', pendingTarget: null }));
    await f.whenStable();
  });

  it('StatusApi is provided by the HTTP implementation', () => {
    expect(TestBed.inject(StatusApi)).toBeInstanceOf(HttpStatusApi);
  });
});

describe('SummaryPanelComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  async function create(body: object, status = 200) {
    const f = TestBed.createComponent(SummaryPanelComponent);
    f.componentRef.setInput('efId', 'ef-1');
    await f.whenStable();
    http.expectOne(SUM).flush(body, { status, statusText: 'x' });
    await f.whenStable();
    return f;
  }
  const el = (f: { nativeElement: HTMLElement }) => f.nativeElement;

  it('U1 FR-33 READY shows statements', async () => {
    const f = await create(readySummary());
    expect(el(f).querySelector('.stmts li')!.textContent).toContain('The materiality section changed.');
  });

  it('U5 FR-43 READY shows the AI-generated label and a single audit details block (FR-40, NFR-16)', async () => {
    const f = await create(readySummary());
    expect(el(f).querySelector('.ai')!.textContent).toContain('AI-generated');
    const d = el(f).querySelectorAll('details');
    expect(d.length).toBe(1);
    expect(d[0].textContent).toContain('model-x');
    expect(d[0].textContent).toContain('p-3');
    expect(d[0].textContent).toContain('2026-10-02T10:00:00Z');
    expect(el(f).querySelector('section')!.getAttribute('aria-live')).toBe('polite');
  });

  it('U2 FR-34 GENERATING shows a spinner message', async () => {
    const f = await create({ state: 'GENERATING' });
    expect(el(f).textContent).toContain('Generating summary');
  });

  it('U3 FR-39 clicking a marker reveals the linked diff item (section and change)', async () => {
    const f = await create(readySummary());
    expect(el(f).querySelector('.reveal')).toBeNull();
    el(f).querySelector<HTMLButtonElement>('button.ref')!.click();
    await f.whenStable();
    const r = el(f).querySelector('.reveal')!;
    expect(r.textContent).toContain('Materiality');
    expect(r.textContent).toContain('Threshold wording replaced');
  });

  it('U6 a 404 on the summary hides the panel', async () => {
    const f = await create({ error: 'NOT_FOUND' }, 404);
    expect(el(f).querySelector('section')).toBeNull();
  });

  it('escaping: a statement containing <script> renders as text', async () => {
    const f = await create(readySummary({ statements: [{ text: '<script>alert(1)</script>', diffItemIds: [] }] }));
    expect(el(f).querySelector('script')).toBeNull();
    expect(el(f).querySelector('.stmts li')!.textContent).toContain('<script>alert(1)</script>');
  });
});
