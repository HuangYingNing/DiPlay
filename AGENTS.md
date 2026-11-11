# AGENTS.md

Instructions for coding agents that work on DiPlay.

## Remembered rules

When a user prompt explicitly asks the agent to "请记住" a rule, record that rule in
this project-level `AGENTS.md` so that future AI agents follow it.

以后新增或修改的项目文档与源码注释一律使用中文。

每次从 PasteDB 获取到新的有效诊断日志后，必须将服务端返回的原始内容保存到本地 `.private/diagnostics/`，文件名注明采集日期和日志标识；该目录及日志原文不得提交或上传 Git。分析时可以另行生成脱敏摘要，但原始资料必须保留。

PasteDB 可绕过代理直接访问。读取 PasteDB 失败时，先检查代理环境变量并尝试不经代理直连；不得因为本地代理不可用就跳过取日志。

网络连接默认直连，不经过代理；直连失败后，才尝试使用已配置的代理服务器。

PasteDB is used for public diagnostic logs. The user accepts that the API key can be
recovered from the APK. Include only the AES-GCM encrypted key and its decryption key
in the APK through build-time injection. Keep both source values in Git-ignored
`.private/PASTEDB_API_KEY_CIPHERTEXT.txt` and
`.private/PASTEDB_API_KEY_DECRYPTION_KEY.txt`, or matching GitHub Actions Secrets.
Never commit either material or plaintext API keys. The client decrypts locally when
uploading a log; do not fetch the key from the HTTPS endpoint.

After each feature is complete, commit its tracked source changes and push the commit
to the configured Git remote. Write commit messages in Chinese, separate modification
items with the Chinese semicolon `；`, and end the message with the Chinese full stop
`。`. Never commit Git-ignored credentials, generated artifacts, or other local secrets.

Before invoking `scripts/publish-release.sh`, all program changes are already committed
and pushed. Do not compile the APK separately before calling it: the release script owns
the single release build by default. Use `--skip-build` only to publish an already-built,
signed APK whose embedded version matches the release being created.

## Checks

Run the CI command from `.github/workflows/android.yml` before you report a change as done:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

### JDK 25, Robolectric and ChaCha20

Robolectric 4.17 enables its Conscrypt provider by default. On JDK 25, initializing
`javax.crypto.Cipher` after Robolectric installs Conscrypt can fail while JDK 25
verifies its signed framework jars (`JarVerifier` / `DIGEST_AND_KEY_TYPE_NOT_SUPPORTED`).
This is a host test environment conflict, not evidence that Android's device-side
Conscrypt implementation is broken. A failed `Cipher.getInstance(...)` is currently
handled as an unavailable platform cipher, so related tests may then fail with null
platform state or misleading BouncyCastle fallback assertions.

For Robolectric tests that verify Android-version buffer behavior while using the host
JDK cipher, annotate the test with `@ConscryptMode(ConscryptMode.Mode.OFF)`. Keep
platform-vs-BouncyCastle cryptographic interoperability checks and authentication-tag
failure checks; do not weaken or skip them to make CI pass. Re-run the full CI command
above after changing the test provider setup.

## Adding or moving a setting

The Settings screen is grouped by driver goal, not by implementation.
`SettingsCategory`, `SettingsSection` and `SettingsInformationArchitecture` at the top of
`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` define the groups.
`SettingsLayoutPolicyTest` fails when a section has no category or has more than one.

### Choose the category

Ask which goal the driver has when they look for the setting. Use the first row that fits.

| Category | Put a setting here when it controls… | Examples |
| --- | --- | --- |
| Connection | how the iPhone connects and how DiPlay starts | connection setup, connect on open, start with the car, USB permissions, car hotspot automation, iPhone choice, Android permissions |
| Display | how CarPlay looks on the head-unit screen | day/night mode, picture, size, resolution, frame rate, dock, system bars, multi-window resolution |
| Audio | what the driver hears | media and navigation streams, music buffer |
| Navigation | location and turn-by-turn guidance | location to iPhone, BYD HUD and cluster guidance |
| Vehicle | how CarPlay fits this car and its driver | driving side, wheel keys (Siri, BYD joystick and map zoom), car button, gestures that conflict with the head unit |
| Diagnostics | troubleshooting evidence | diagnostic reports |
| Advanced | experimental, firmware-specific or risky behavior | dashboard map, split screen, screen rotation, side panel, HEVC video, audio focus, audio channel mapping, buffered music, vehicle data |
| Overview | nothing new | see "Overview" below |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: auto car hotspot needs ADB but appears only for car-hotspot users, so it is in Connection.

Mark an experimental setting or card with the title suffix ` (experimental)`, never `· experimental`, a dash, or an `Experimental …` prefix.
Each locale MUST use its existing form: `(تجريبي)`, `(experimental)`, `(sperimentale)`, `(экспериментально)`, `(експериментально)`, `(deneysel)`, `（实验性）`, `（實驗性）`, `（実験的機能）`, `(실험 기능)`.

### Add the code

1. To extend an existing card, add the control inside the `filteredSection(...)` block of a card in the correct category.
2. For a new card, add a `SettingsSection` entry and map it to exactly one category in `SettingsInformationArchitecture.sectionsByCategory`.
3. Build the new card with `filteredSection(content, SettingsSection.X, …)` inside `allSettingsSections()`.
   A plain `section(...)` call there MUST NOT be used: it renders on every category page.
4. A setting MUST NOT appear in two categories.
   Overview quick settings are the only duplicates. They MUST use the same persistence as the full control. Overview SHOULD NOT have more than four quick settings.
   A header shortcut MAY duplicate a Display setting when it uses the same persistence.
5. If the change reconnects CarPlay or applies at the next connection, the description MUST say so.
   A setting that only takes effect at the next connection MUST call `markReconnectNeeded()` after it saves.
   This shows the "Reconnect now" bar. It MUST NOT drop a running session without the driver's consent.
   Reconnect at once (`reconnectIfRunning()`) only after a dialog button that says "Apply and reconnect", or when the description says that the change reconnects CarPlay.
6. Add each new string to `common/src/main/res/values/` and to every `values-xx` locale folder.
   New Settings copy SHOULD use the `settings_` prefix. `SettingsTranslationsTest` fails when a `settings_*` string has no translation.
7. A control that opens a choice uses `button()` with the text `"Title · Value"`.
   That form renders as a setting row with the value and a chevron, and Search indexes the title.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Overview

Overview holds the connection status, links to the categories, quick settings, About and Language.
Do not add a new setting to Overview. Add it to its category. Then promote it to quick settings only if drivers change it often.

## Settings layout

Spacing, size and shape in the Settings screen MUST come from one place.

1. A gap between blocks MUST use a named dp constant, such as `SETTINGS_BLOCK_GAP_DP`.
   A new literal gap value MUST NOT be added.
2. A size MUST NOT be a pixel constant (`*_PX`). Use dp, and clamp to the available window width.
   Test a menu or panel on a narrow window and on a high-density screen.
3. Controls in one row MUST have the same height.
   Corner radius MUST follow the control height. A new button MUST NOT set its own radius.
4. Every `SettingsCategory` except Overview MUST have a link on Overview.
   `AdaptiveSettingsUiTest.overviewLinksToEveryOtherCategory` checks this.
5. A layout change MUST include compact and full screenshots with a large font scale.
   Add an Arabic (right-to-left) screenshot when the change touches row alignment.
