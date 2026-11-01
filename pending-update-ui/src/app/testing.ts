import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { devIdentityInterceptor } from './dev-identity.interceptor';
import { ReadySummary, Row } from './models';

export const testProviders = () => [
  provideHttpClient(withInterceptors([devIdentityInterceptor])),
  provideHttpClientTesting(),
  provideRouter([]),
];

export const row = (over: Partial<Row> = {}): Row => ({
  ef: 'ef-1', template: 'Audit Standard', status: 'UPDATE_PENDING', baseline: 'v3', pendingTarget: 'v6',
  updatedAt: '2026-10-01T00:00:00Z', source: 'FANOUT', ...over,
});

export const readySummary = (over: Partial<ReadySummary> = {}): ReadySummary => ({
  state: 'READY', label: 'AI-generated', generationId: 'gen-1', generatedAt: '2026-10-02T10:00:00Z',
  model: 'model-x', promptVersion: 'p-3', baseline: 'v3', target: 'v6',
  statements: [{ text: 'The materiality section changed.', diffItemIds: ['d1'] }],
  diff: [{ id: 'd1', section: 'Materiality', change: 'Threshold wording replaced' }],
  ...over,
});
