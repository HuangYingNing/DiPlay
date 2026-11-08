# Release APK signing

The GitHub Actions workflow can build and sign a release APK when all required repository Actions secrets are configured:

| Secret | Local value file |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `.private/ANDROID_KEYSTORE_BASE64.txt` |
| `ANDROID_KEYSTORE_PASSWORD` | `.private/ANDROID_KEYSTORE_PASSWORD.txt` |
| `ANDROID_KEY_ALIAS` | `.private/ANDROID_KEY_ALIAS.txt` |
| `ANDROID_KEY_PASSWORD` | `.private/ANDROID_KEY_PASSWORD.txt` |
| `DIPLAY_AUTH_IDENTITY_BASE64` | Base64 of `.private/runtime-assets/offline-mfi/identity.pk8` |
| `DIPLAY_AUTH_CERTIFICATE_BASE64` | Base64 of `.private/runtime-assets/offline-mfi/certificate.p7b` |
| `PASTEDB_KEY_ENDPOINT` | HTTPS URL from `.private/PASTEDB_KEY_ENDPOINT.txt` |

In GitHub, open **Settings → Secrets and variables → Actions**, then add each file's value under its matching secret name. The `.private` folder and keystore are ignored by Git; never commit or publish them.

The APK does not embed the PasteDB API key. The key endpoint is a private build input,
injected from `PASTEDB_KEY_ENDPOINT` and used only when the driver uploads a diagnostic report.

For a local release, provide the same values through environment variables or the local
files in the table. `DIPLAY_AUTH_ASSETS_DIR` defaults to `.private/runtime-assets`, and
the PasteDB endpoint defaults to `.private/PASTEDB_KEY_ENDPOINT.txt`.

Use the release script from a clean, committed and pushed `main` branch. Publishing is
the default behavior:

```sh
scripts/publish-release.sh --dry-run
scripts/publish-release.sh
```

The script reads the base version from `mobile/build.gradle.kts`, finds the next daily
sequence from GitHub Releases, and creates a version such as
`v0.2.16-b261108-001`. Because the script requires committed and pushed source, it
does not repeat the development CI checks. It performs exactly one signed Release APK
build, then publishes the APK plus `SHA256SUMS.txt` directly to GitHub. `--publish`
remains available as an explicit synonym for the default.

To publish an existing signed APK without running Gradle again, its embedded version
must match the release version exactly. The source commit is stored separately in the
APK and is submitted only with an uploaded diagnostic log:

```sh
scripts/publish-release.sh --skip-build --apk /absolute/path/to/DiPlay.apk
```

Use `--date YYYYMMDD`, `--sequence N`, or `--notes PATH` only when an explicit override
is required. Do not pre-build before a normal invocation; the script performs the one
required build. The release signing key must be backed up securely. A different key
cannot update an APK signed with this one.
