# Android string translation automation

The `Translate Android strings` GitHub Actions workflow fills missing keys in each
`common/src/main/res/values-*` locale from `common/src/main/res/values`. It runs when
English Android resources change on `main`, or when started manually. It only adds
missing keys, preserves format placeholders, and commits the generated locale files
with a Chinese commit message.

The workflow uses the official Google Cloud Translation Basic v2 REST API. Configure
the repository Actions secret `GOOGLE_TRANSLATE_API_KEY` before running it:

```sh
gh secret set GOOGLE_TRANSLATE_API_KEY --repo HuangYingNing/DiPlay
```

The key must belong to a Google Cloud project with Cloud Translation enabled. Google
Cloud may charge for API usage. The workflow sends English resource string values and
locale codes to Google; it does not send source files or other repository content.

For a local run, export `GOOGLE_TRANSLATE_API_KEY` in the shell and run:

```sh
python3 scripts/translate_android_strings.py
```

To list untranslated keys without sending text, run:

```sh
python3 scripts/translate_android_strings.py --check
```
