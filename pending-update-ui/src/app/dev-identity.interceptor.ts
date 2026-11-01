import { HttpInterceptorFn } from '@angular/common/http';
import { environment } from '../environments/environment';

/**
 * DEV ONLY, NOT AUTH. Adds X-Dev-Firm and X-Dev-User so the demo backend knows
 * which firm to serve. These headers prove nothing about identity.
 */
export const devIdentityInterceptor: HttpInterceptorFn = (req, next) =>
  req.url.startsWith(environment.apiBase)
    ? next(req.clone({ setHeaders: { 'X-Dev-Firm': environment.devFirm, 'X-Dev-User': environment.devUser } }))
    : next(req);
