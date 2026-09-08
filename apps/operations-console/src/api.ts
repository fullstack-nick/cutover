import Keycloak from 'keycloak-js';
import type { components } from './api.generated';
export type Order = components['schemas']['order-view'];
export type OrderPage = components['schemas']['order-page'];
export type Equipment = components['schemas']['equipment-view'];
export type Task = components['schemas']['task-list'][number];
export type Command = components['schemas']['command-view'];
export type ExecutionTask = components['schemas']['execution-task-list'][number];
export type ZoneRoute = components['schemas']['zone-route-list'][number];
export type Migration = components['schemas']['migration-view'];
export type MigrationList = components['schemas']['migration-list'];
export type Receipt = components['schemas']['receipt-view'];
export type ReceiptPage = components['schemas']['receipt-page'];
export type ReturnCounters = components['schemas']['return-counters'];
export type ReturnTask = components['schemas']['return-task-list'][number];
export const identity = new Keycloak({ url: '/identity', realm: 'cutover', clientId: 'operations-console' });
export async function initializeIdentity() {
  const view = new URLSearchParams(location.search).get('view');
  if (view && ['overview', 'orders', 'equipment', 'migrations', 'returns'].includes(view)) sessionStorage.setItem('cutover.view', view);
  const authenticated = await identity.init({ onLoad: 'check-sso', pkceMethod: 'S256', checkLoginIframe: false, redirectUri: `${location.origin}/callback` });
  const remembered = sessionStorage.getItem('cutover.view');
  if (authenticated && remembered && ['overview', 'orders', 'equipment', 'migrations', 'returns'].includes(remembered)) history.replaceState({}, '', `/?view=${remembered}`);
  return authenticated;
}
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}
export async function get<T>(path: string): Promise<T> {
  return request<T>(path);
}
export async function post<T>(path: string, body: unknown, key: string): Promise<T> {
  return request<T>(path, body, key);
}
async function request<T>(path: string, payload?: unknown, key?: string): Promise<T> {
  if (!identity.authenticated) throw new ApiError(401, 'SIGN_IN_REQUIRED', 'Sign in to view site operations.');
  try { await identity.updateToken(30); }
  catch {
    if (!identity.token || identity.isTokenExpired()) throw new ApiError(401, 'SESSION_EXPIRED', 'Your local session expired. Sign in again to continue.');
    // A still-valid in-memory token remains useful during an identity outage.
  }
  const response = await fetch(path, { method: payload === undefined ? 'GET' : 'POST', body: payload === undefined ? undefined : JSON.stringify(payload), headers: { Authorization: `Bearer ${identity.token}`, Accept: 'application/json', ...(payload === undefined ? {} : { 'Content-Type': 'application/json', 'Idempotency-Key': key! }) }, signal: AbortSignal.timeout(8000), cache: 'no-store' });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new ApiError(response.status, body.code ?? `HTTP_${response.status}`, body.detail ?? `The service returned ${response.status}.`);
  }
  return response.json() as Promise<T>;
}
export const sitePath = (site: string) => `/api/v1/sites/${encodeURIComponent(site)}`;
