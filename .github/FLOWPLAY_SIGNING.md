# FlowPlay stable package and signing

FlowPlay v2 uses the fixed Android suffix `.continuous.flowplay` and increasing
version codes. Older branch-specific APKs require a one-time backup/migration.

For subsequent **install-over** upgrades, configure GitHub repository
Settings → Secrets and variables → Actions with:
- `FLOWPLAY_SIGNING_KEYSTORE_BASE64`: a privately generated Android keystore, base64 encoded
- `FLOWPLAY_SIGNING_STORE_PASSWORD`: keystore password
- `FLOWPLAY_SIGNING_KEY_ALIAS`: key alias
- `FLOWPLAY_SIGNING_KEY_PASSWORD`: key password

**Do not commit a private signing key to the public repository.**
Back it up securely. Without these secrets the CI uses a disposable Android
debug key. The APK builds, but upgrading over a previous build is NOT guaranteed.
Once configured, keep the same keystore for every future release.

Settings → Updates checks the latest FlowPlay GitHub release, compares
version codes, and opens the APK download. Android still requires the user's
approval to install; this is not silent updating.
