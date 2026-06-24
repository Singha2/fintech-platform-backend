package com.arthvritt.platform.infrastructure.security;

import com.arthvritt.platform.shared.error.PlatformException;
import org.springframework.http.HttpStatus;

/**
 * An inbound vendor webhook failed HMAC verification — a bad signature or a stale timestamp (outside the
 * replay window). 401, and — unlike most pre-auth failures — it DOES produce a business audit fact
 * ({@code <context>.WebhookSignature.Invalid}, B4 §5.1 / C10), because a signature failure is a
 * security-relevant event. The handler emits that envelope before mapping this to the 401 body.
 */
public class WebhookSignatureException extends PlatformException {

    public WebhookSignatureException(String message) {
        super("signature_invalid", message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.UNAUTHORIZED;
    }
}
