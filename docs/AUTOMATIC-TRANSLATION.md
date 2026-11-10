# Android string translation automation

The `Translate Android strings` GitHub Actions workflow fills missing keys in each
`common/src/main/res/values-*` locale from `common/src/main/res/values`. It runs when
English Android resources change on `main`, or when started manually. It only adds
missing keys, preserves format placeholders, and commits the generated locale files
with a Chinese commit message.

The workflow uses Azure Translator F0 and fails closed when credentials are missing.
Set the repository Actions secret `AZURE_TRANSLATOR_KEY` before running it. If you
create a regional or multi-service resource, also set the `AZURE_TRANSLATOR_REGION`
Actions variable. A single-service Translator resource using the Global region does not
require that variable:

```sh
gh secret set AZURE_TRANSLATOR_KEY --repo HuangYingNing/DiPlay
```

Create the Translator resource on the **F0 (Free)** tier. The script records successful
translation input characters in `scripts/translation_usage.json` and stops before
1,900,000 characters in a UTC month, leaving a 100,000-character margin below F0's
2,000,000-character monthly allowance. Counts include each target language separately.
The workflow sends only missing English string values and locale codes to Azure; it
does not send source files or other repository content. Never switch the Azure resource
to a paid tier without reviewing the budget guard.

The Google Cloud Translation API is not used by this workflow. Google Cloud currently
requires linking a billing account before enabling that API. To keep this automation
from using paid Google quota, do not enable billing or configure a Google key here.

For a local run, export `AZURE_TRANSLATOR_KEY` and `AZURE_TRANSLATOR_REGION`, then run:

```sh
python3 scripts/translate_android_strings.py
```

To list untranslated keys without sending text, run:

```sh
python3 scripts/translate_android_strings.py --check
```
