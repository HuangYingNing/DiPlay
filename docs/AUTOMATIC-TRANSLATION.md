# Android string translation automation

The `Translate Android strings` GitHub Actions workflow fills missing keys in each
`common/src/main/res/values-*` locale from `common/src/main/res/values`. It runs when
English Android resources change on `main`, daily, or when started manually. It only
adds missing keys, preserves format placeholders, and commits generated locale files.

The workflow uses Google Cloud Translation Basic (v2). Configure the repository Actions
secret `GOOGLE_TRANSLATE_API_KEY` with an API key restricted to the Cloud Translation
API. Do not put the key in source, workflow files, logs, or Git history.

Google Cloud's project quota is set to 15,000 general-model characters per day. The
script uses a lower 14,000-character daily ceiling and stops at 450,000 characters in a
Pacific-time calendar month, leaving headroom below the 500,000-character monthly free
allowance. It records successful input characters in `scripts/translation_usage.json`;
counts include every target language separately. Daily scheduled runs continue any
remaining translations after the daily limit resets. The workflow sends only missing
English string values and locale codes to Google; it does not send source files or other
repository content.

The per-project daily quota and script ledger do not cap use by other projects sharing
the billing account, and asynchronous batch translation has separate rate limits. Keep
the key restricted to this API and monitor billing-account usage. Google Cloud budgets
send alerts but do not stop charges.

For a local run, export `GOOGLE_TRANSLATE_API_KEY`, then run:

```sh
python3 scripts/translate_android_strings.py
```

To list untranslated keys without sending text:

```sh
python3 scripts/translate_android_strings.py --check
```
