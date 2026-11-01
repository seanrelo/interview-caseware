package com.caseware.fanout.web;

import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.status.Caller;
import jakarta.servlet.http.HttpServletRequest;

/**
 * DEV ONLY, NOT AUTH. Builds the {@link Caller} from the plain headers {@code X-Dev-Firm} and
 * {@code X-Dev-User}, which any client can set. This class is the only place that reads them and
 * the only place a firm identity enters the web layer (NFR-33): a firm is never taken from a path,
 * query or body. A production resolver would read the firm and user from validated JWT claims
 * (signature, issuer, audience, expiry checked) and keep this same method contract.
 */
public class CallerResolver {

  public static final String FIRM_HEADER = "X-Dev-Firm";
  public static final String USER_HEADER = "X-Dev-User";

  /** @throws UnauthenticatedException when either header is missing or blank. */
  public Caller resolve(HttpServletRequest request) {
    String firm = request.getHeader(FIRM_HEADER);
    String user = request.getHeader(USER_HEADER);
    if (firm == null || firm.isBlank() || user == null || user.isBlank()) {
      throw new UnauthenticatedException();
    }
    return new Caller(new FirmId(firm.strip()), user.strip());
  }
}
