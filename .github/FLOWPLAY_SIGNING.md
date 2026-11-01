# FlowPlay permanent signing and safer updates

## Why this is necessary

Every public GitHub Actions build of FlowPlay v2.6–v2.8 was signed using a
fresh, disposable Android debug certificate because the private signing secrets
were not configured. Android will reject an APK update whose certificate does
not match the currently installed app, even if the package ID and version code
are correct. **The first transition to a permanent certificate requires a
one-time export / uninstall / reinstall / restore** of the test-signed app.

Google Play Protect's **App scan recommended** message is separate. It means
Google has not seen/scanned an APK, **not** that malware was detected.
A consistent private certificate and developer verification can improve release
trust but cannot guarantee removal of Google-controlled Play Protect prompts.
Do not disable Play Protect or bypass its scans.

## Set up a permanent private signing certificate (Windows)

**Important: The signing key must NEVER be committed to this public GitHub
repository.** Generate it on your own Windows PC, retain an offline backup,
and save the password in a password manager.

1. Install Android Studio (its bundled JDK provides `keytool`) and the
   [GitHub CLI](https://cli.github.com/). For GitHub CLI on Windows:
   `winget install --id GitHub.cli --exact`
2. Download [scripts/setup-flowplay-signing.ps1](../scripts/setup-flowplay-signing.ps1)
   from this repository and inspect the script before running it. Run in
   PowerShell with `./setup-flowplay-signing.ps1` from its downloaded folder.
   If your PowerShell execution policy prevents running a downloaded script,
   inspect and unblock the file through its Windows Properties; do not change
   system-wide security settings.
3. Sign in to GitHub when asked. Enter and confirm a **new strong password**.
   The script creates `Documents/FlowPlaySigning/flowplay-release.p12` on
   your PC and puts its contents and password into encrypted **GitHub Actions
   repository secrets**, not the source repository:
   - `FLOWPLAY_SIGNING_KEYSTORE_BASE64`
   - `FLOWPLAY_SIGNING_STORE_PASSWORD`
   - `FLOWPLAY_SIGNING_KEY_ALIAS`
   - `FLOWPLAY_SIGNING_KEY_PASSWORD`
   - `FLOWPLAY_SIGNING_CERT_SHA256`
4. **Back up the original .p12 key file** somewhere private and durable. Keep
   its password. Re-running the setup script reuses the existing key instead
   of silently generating a new one.
5. In GitHub → **Actions → Build FlowPlay v2 Reliability APK → Run workflow**
   (branch `flowplay-v2-reliability`), run the workflow once. Its
   `Verify APK certificate` step now checks the release certificate SHA256
   against the secret you saved. Successful releases use a fixed package ID
   `org.schabi.newpipe.continuous.flowplay` and a strictly increasing version
   code.

Until these five secrets are present, GitHub Actions **will not publish another
debug-signed APK as a stable FlowPlay update**. This prevents repeatedly
offering releases that are guaranteed to fail an install-over upgrade.

## First migration from v2.8's temporary certificate

1. In the existing app: **Settings → Backup and restore → Export**.
   Save the exported database outside the app, and confirm you can find it.
2. Generate and build the first permanently signed release as above.
3. Uninstall the **old test-signed FlowPlay**. Android cannot replace it in
   place with the new signing key; uninstalling erases local data.
4. Install the new GitHub release. If Google Play Protect offers to scan the
   APK, use **Scan app** and review its result.
5. Restore your database from the backup.

Once the permanent certificate is configured, subsequent APKs signed by that
**same certificate** with greater version codes should update normally, without
another uninstall. Android may still show security or installation confirmations.

## Security notes

- Don't publish or email the .p12 file and password.
- Back up the signing key privately; losing it means losing install-over updates.
- If a certificate fingerprint mismatch occurs, the workflow deliberately
  rejects the release rather than creating an incompatible update.
- Google's [Android developer verification](https://developer.android.com/developer-verification)
  and Play distribution are separate from APK signing. They require action from
  the app owner and don't automatically guarantee that Play Protect will never
  prompt to scan a sideloaded APK.
