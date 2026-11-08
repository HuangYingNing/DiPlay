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
| `PASTEDB_API_KEY` | `PASTEDB_API_KEY` from `.private/pastedb.properties` |

In GitHub, open **Settings → Secrets and variables → Actions**, then add each file's value under its matching secret name. The `.private` folder and keystore are ignored by Git; never commit or publish them.

The app calls PasteDB directly, so anyone who downloads a public APK can reverse
engineer its PasteDB API key. After accepting that risk, add the repository Actions
variable `ALLOW_EMBEDDED_PASTEDB_KEY=true`. Both the script and workflow refuse a public
release without this explicit acknowledgement. Prefer replacing direct authentication
with a narrowly scoped server proxy before distributing the APK broadly.

After the secrets are configured, use the release script from a clean, pushed `main` branch:

```sh
scripts/publish-release.sh --dry-run
scripts/publish-release.sh --publish
```

The script reads the base version from `mobile/build.gradle.kts`, finds the next daily
sequence from GitHub Releases, and creates a version such as
`0.2.16-build20261108-001`. It then dispatches and watches the Android workflow. The
workflow runs the repository checks, builds the signed APK with CarPlay authentication
and PasteDB upload support, and publishes the APK plus `SHA256SUMS.txt`.

Use `--date YYYYMMDD`, `--sequence N`, or `--notes PATH` only when an explicit override
is required. The release signing key must be backed up securely. A different key cannot
update an APK signed with this one.
