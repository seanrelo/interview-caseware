import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PendingPage } from './models';
import { PendingListComponent } from './pending-list.component';
import { row, testProviders } from './testing';

describe('PendingListComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  async function create() {
    const f = TestBed.createComponent(PendingListComponent);
    await f.whenStable();
    return f;
  }
  const el = (f: { nativeElement: HTMLElement }) => f.nativeElement;
  const page: PendingPage = {
    rows: [row(), row({ ef: 'ef-2', status: 'UP_TO_DATE', baseline: 'v6', pendingTarget: null }), row({ ef: 'ef-3', status: 'UNKNOWN', baseline: null, pendingTarget: null, source: 'FANOUT_UNKNOWN' })],
    nextToken: 'tok2',
    totals: { upToDate: 10, updatePending: 5, unknown: 2 },
  };

  it('shows loading, then rows, with dev headers and size=50 (UI-22, NFR-14)', async () => {
    const f = await create();
    expect(el(f).querySelector('[aria-busy="true"]')).toBeTruthy();
    const r = http.expectOne('/api/v1/pending-updates?size=50');
    expect(r.request.headers.get('X-Dev-Firm')).toBe('firm-demo');
    expect(r.request.headers.get('X-Dev-User')).toBe('demo.user');
    r.flush(page);
    await f.whenStable();
    expect(el(f).querySelectorAll('ul[aria-label="Engagement Files"] li.card').length).toBe(3);
    expect(el(f).textContent).toContain('Update v3 → v6');
  });

  it('UI-25 FR-25 a 503 shows the error state and never "up to date"', async () => {
    const f = await create();
    http.expectOne('/api/v1/pending-updates?size=50').flush({ error: 'STATUS_UNAVAILABLE' }, { status: 503, statusText: 'x' });
    await f.whenStable();
    expect(el(f).querySelector('[role="alert"]')).toBeTruthy();
    expect(el(f).textContent).not.toContain('Up to date');
    expect(el(f).querySelector('.card')).toBeNull();
  });

  it('UI-25 FR-24 an UNKNOWN row shows unknown, not up to date', async () => {
    const f = await create();
    http.expectOne('/api/v1/pending-updates?size=50').flush({ rows: [page.rows[2]], nextToken: null, totals: { upToDate: 0, updatePending: 0, unknown: 1 } });
    await f.whenStable();
    const card = el(f).querySelector('li.card')!;
    expect(card.querySelector('.badge')!.textContent).toContain('Unknown');
    expect(card.textContent).not.toContain('Up to date');
  });

  it('UI-30 a view error leaves the page usable and Retry works', async () => {
    const f = await create();
    http.expectOne('/api/v1/pending-updates?size=50').error(new ProgressEvent('error'));
    await f.whenStable();
    (el(f).querySelector('[role="alert"] button') as HTMLButtonElement).click();
    http.expectOne('/api/v1/pending-updates?size=50').flush(page);
    await f.whenStable();
    expect(el(f).querySelectorAll('li.card').length).toBe(3);
    expect(el(f).querySelector('[role="alert"]')).toBeNull();
  });

  it('UI-41 NFR-41 a 404 FEATURE_OFF renders nothing', async () => {
    const f = await create();
    http.expectOne('/api/v1/pending-updates?size=50').flush({ error: 'FEATURE_OFF' }, { status: 404, statusText: 'x' });
    await f.whenStable();
    expect(el(f).textContent!.trim()).toBe('');
  });

  it('401 shows a not signed in note', async () => {
    const f = await create();
    http.expectOne('/api/v1/pending-updates?size=50').flush({}, { status: 401, statusText: 'x' });
    await f.whenStable();
    expect(el(f).textContent).toContain('not signed in');
  });

  it('FR-22 filter uses totals for counts, narrows the loaded page, and Next sends the token (NFR-14)', async () => {
    const f = await create();
    http.expectOne('/api/v1/pending-updates?size=50').flush(page);
    await f.whenStable();
    const buttons = [...el(f).querySelectorAll<HTMLButtonElement>('.filters button')];
    expect(buttons.map((b) => b.textContent!.trim())).toEqual(['All (17)', 'Update pending (5)', 'Up to date (10)', 'Unknown (2)']);
    expect(el(f).querySelector('.filters')!.getAttribute('aria-label')).toContain('loaded page only');
    buttons[3].click();
    await f.whenStable();
    expect(el(f).querySelectorAll('li.card').length).toBe(1);
    ([...el(f).querySelectorAll<HTMLButtonElement>('.pager button')].find((b) => b.textContent === 'Next')!).click();
    http.expectOne('/api/v1/pending-updates?size=50&token=tok2').flush({ ...page, nextToken: null });
  });
  const L = '/api/v1/pending-updates?size=50';
  const nextBtn = (f: { nativeElement: HTMLElement }) => [...el(f).querySelectorAll<HTMLButtonElement>('.pager button')].find((b) => b.textContent === 'Next')!;
  const prevBtn = (f: { nativeElement: HTMLElement }) => [...el(f).querySelectorAll<HTMLButtonElement>('.pager button')].find((b) => b.textContent === 'Previous')!;

  it('NFR-14 Previous replays the earlier tokens in order and is disabled on page 1', async () => {
    const f = await create();
    http.expectOne(L).flush({ ...page, nextToken: 't2' });
    await f.whenStable();
    expect(prevBtn(f).disabled).toBe(true);
    nextBtn(f).click();
    http.expectOne(`${L}&token=t2`).flush({ ...page, nextToken: 't3' });
    await f.whenStable();
    nextBtn(f).click();
    http.expectOne(`${L}&token=t3`).flush({ ...page, nextToken: null });
    await f.whenStable();
    expect(el(f).textContent).toContain('Page 3');
    prevBtn(f).click();
    http.expectOne(`${L}&token=t2`).flush({ ...page, nextToken: 't3' });
    await f.whenStable();
    expect(el(f).textContent).toContain('Page 2');
    prevBtn(f).click();
    http.expectOne(L).flush({ ...page, nextToken: 't2' });
    await f.whenStable();
    expect(el(f).textContent).toContain('Page 1');
    expect(prevBtn(f).disabled).toBe(true);
  });

  it('FR-22 an active filter shows the loaded-page note; an empty page with a nonzero count points to Next', async () => {
    const f = await create();
    http.expectOne(L).flush({ ...page, rows: [page.rows[1]] });
    await f.whenStable();
    expect(el(f).textContent).not.toContain('Filter applies to the loaded page');
    el(f).querySelectorAll<HTMLButtonElement>('.filters button')[1].click(); // Update pending (5)
    await f.whenStable();
    expect(el(f).textContent).toContain('Filter applies to the loaded page');
    expect(el(f).textContent).toContain('No rows of this status on this page. 5 in total; use Next to look further.');
  });

  it('an empty filtered page without a next token does not suggest Next', async () => {
    const f = await create();
    http.expectOne(L).flush({ ...page, rows: [page.rows[1]], nextToken: null });
    await f.whenStable();
    el(f).querySelectorAll<HTMLButtonElement>('.filters button')[1].click();
    await f.whenStable();
    expect(el(f).textContent).not.toContain('use Next');
    expect(el(f).textContent).toContain('No EFs on this page with this status.');
  });
});
