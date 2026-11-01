/**
 * DEV ONLY, NOT AUTH. The X-Dev-Firm / X-Dev-User headers added by the dev-identity
 * interceptor are a development stand-in for real authentication. They carry no
 * proof of identity and must be removed (with the interceptor) before any real deployment.
 */
export const environment = {
  apiBase: '/api/v1',
  devFirm: 'firm-demo',
  devUser: 'demo.user',
};
