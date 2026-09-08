# Local identity unavailable

New sign-in or token refresh can fail while the static console and existing business records remain available. Inspect the local `/identity` response and the `keycloak` Deployment in `cutover-platform`. Distinguish an unhealthy identity process from a stopped loopback forward, unavailable application PostgreSQL or exhausted host resources.

```powershell
./scripts/forward.ps1 Start -Target console
kubectl --kubeconfig .local/kubeconfig --context kind-cutover -n cutover-platform get deployment keycloak
kubectl --kubeconfig .local/kubeconfig --context kind-cutover -n cutover-platform get pod -l app.kubernetes.io/name=keycloak
```

Verify the Cutover labels and use the generated local configuration. Start the preserved demo if it was deliberately stopped. If an explicit scoped experiment left only Keycloak at zero replicas, restore that owned Deployment to its original one replica and wait for readiness. An unavailable database must recover on its existing PVC before identity can recover. Do not reimport a fresh realm or regenerate credentials over existing application state.

APIs validate tokens independently. A valid token with a cached signing key may work until its expiry and allowed clock skew. An unknown signing key fails closed when keys cannot be retrieved. Service-to-service calls eventually pause when their service token cannot be refreshed. No indefinite identity-outage availability is promised.

The browser shows sign-in/refresh failure instead of treating it as an empty business result. Restore the identity path, then sign in again or allow a successful refresh. Confirm the expected user, role and site. An operator still cannot perform supervisor recovery, and a service's generic role cannot bypass the explicit intake client boundary.

`node tools/scenario-driver/authentication-smoke.mjs` checks actual PKCE, token expiry/refreshed access, site boundaries, untrusted signatures/audiences, shadow intake denial, broker source identity and equipment certificates. Production-decoder component tests separately verify trusted-signature issuer/time conditions and unavailable JWKS. This does not claim that a Keycloak outage is transparent to users.

If an established user loses a role/site unexpectedly, retain the authorization failure and inspect the administrator-managed profile. Do not edit site claims in the browser, relax issuer/audience checks, enable a password grant or share another account's token. Passwords, signing material and bearer tokens stay out of logs and public reports.
