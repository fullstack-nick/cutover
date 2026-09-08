# HTTP boundary responses

`problem-responses-1788891930309` passed on 8 September 2026 at 20:25:38 Europe/Berlin, using the clean `c6486a6` application images. All seven checks read the raw HTTP response and assert `application/problem+json`, the status, problem fields and the expected code. They do not rely on the scenario client's fallback error wrapper.

| Request | Status | Problem code |
| --- | --- | --- |
| Missing bearer token | 401 | `AUTHENTICATION_REQUIRED` |
| Invalid bearer token | 401 | `AUTHENTICATION_REQUIRED` |
| Malformed bearer credential | 401 | `AUTHENTICATION_REQUIRED` |
| Operator at the platform administration boundary | 403 | `ACCESS_DENIED` |
| Missing required request key | 400 | `MALFORMED_REQUEST` |
| Malformed JSON body | 400 | `MALFORMED_JSON` |
| Oversized body at the local proxy | 413 | `PAYLOAD_TOO_LARGE` |

Authentication responses retain a sanitized Bearer challenge. Core order, reservation and idempotency counts remain unchanged. The role check uses a real operator PKCE session and a managed ephemeral service forward. Other cases use the ordinary local proxy. Full cryptographic, site, source and equipment-certificate validation has separate authentication evidence; these seven checks establish the boundary response contract.

Reproduce with `node tools/scenario-driver/problem-responses-smoke.mjs` on a running demo. [Runtime provenance](runtime-runs.json) includes exact image identities, sanitized observed response fields and the original result hash. The [backend results](backend-checks.json) include the four supporting security-filter checks within the full 190-test verification.
