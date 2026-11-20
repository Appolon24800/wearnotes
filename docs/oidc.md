# OIDC and Pocket ID

WearNotes accepts any OpenID Connect provider. Pocket ID works through its
normal discovery document and ID token verification.

Create an OIDC client in Pocket ID with a public client ID and the scopes
`openid profile email`.

Configure the server with:

```bash
WEARNOTES_OIDC_ISSUER=https://id.example.com
WEARNOTES_OIDC_CLIENT_ID=<pocket-id-client-id>
WEARNOTES_OIDC_CLIENT_SECRET=<optional-secret>
WEARNOTES_OIDC_REDIRECT_URL=<native-app-redirect-url>
```

The server discovers Pocket ID at:

```text
https://id.example.com/.well-known/openid-configuration
```

The native client completes Authorization Code with PKCE in a browser Custom
Tab, then sends the ID token to:

```text
POST /v1/auth/oidc/device
{
  "idToken": "..."
}
```

The server verifies the token signature, issuer, audience, and expiry through
Pocket ID's discovery keys. It returns a normal WearNotes device token. The
device token can be revoked independently of the Pocket ID session.

The client must not embed an OIDC client secret in the APK. Pocket ID's passkey
screen stays in the browser. Pair the watch from the phone, then use its
device token for direct Wi-Fi sync.
