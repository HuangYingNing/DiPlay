#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/publish-release.sh [options]

Builds one signed release APK and publishes it to GitHub by default. The version is
vBASE-bYYMMDD-NNN. Use --skip-build to publish an already-built matching APK.

Options:
  --publish             Build and publish (default; retained for explicit calls).
  --dry-run             Print the resolved release without building or publishing.
  --skip-build          Skip Gradle and publish an existing signed APK.
  --apk PATH            APK used by --skip-build (default: release output path).
  --date YYYYMMDD       Override today's date.
  --sequence N          Override the automatically selected daily sequence.
  --notes PATH          Repository-relative Markdown release notes file.
  --repo OWNER/REPO     Override the repository inferred from origin.
  --ref BRANCH          Release branch; publishing requires main (default: main).
  -h, --help            Show this help.
EOF
}

fail() {
  printf '错误：%s\n' "$*" >&2
  exit 1
}

read_secret_file() {
  local variable_name="$1"
  local file_path="$2"
  if [[ -z "${!variable_name:-}" && -s "$file_path" ]]; then
    printf -v "$variable_name" '%s' "$(<"$file_path")"
    export "$variable_name"
  fi
}

decode_base64_file() {
  local source_file="$1"
  local destination_file="$2"
  if base64 --decode "$source_file" > "$destination_file" 2>/dev/null; then
    return
  fi
  base64 -D "$source_file" > "$destination_file"
}

sha256_file() {
  local file_path="$1"
  if command -v sha256sum >/dev/null; then
    sha256sum "$file_path" | awk '{print $1}'
  else
    shasum -a 256 "$file_path" | awk '{print $1}'
  fi
}

find_aapt() {
  if command -v aapt >/dev/null; then
    command -v aapt
    return
  fi
  local sdk_root candidate
  for sdk_root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [[ -n "$sdk_root" && -d "$sdk_root/build-tools" ]] || continue
    candidate="$(find "$sdk_root/build-tools" -maxdepth 2 -type f -name aapt | sort | tail -n 1)"
    if [[ -n "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  return 1
}

publish=true
skip_build=false
release_date="$(date +%Y%m%d)"
sequence=""
notes_path=""
repo=""
release_ref="main"
apk_path="mobile/build/outputs/apk/release/mobile-release.apk"

while (($#)); do
  case "$1" in
    --publish) publish=true; shift ;;
    --dry-run) publish=false; shift ;;
    --skip-build) skip_build=true; shift ;;
    --apk) (($# >= 2)) || fail "--apk 缺少值"; apk_path="$2"; shift 2 ;;
    --date) (($# >= 2)) || fail "--date 缺少值"; release_date="$2"; shift 2 ;;
    --sequence) (($# >= 2)) || fail "--sequence 缺少值"; sequence="$2"; shift 2 ;;
    --notes) (($# >= 2)) || fail "--notes 缺少值"; notes_path="$2"; shift 2 ;;
    --repo) (($# >= 2)) || fail "--repo 缺少值"; repo="$2"; shift 2 ;;
    --ref) (($# >= 2)) || fail "--ref 缺少值"; release_ref="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "未知参数：$1" ;;
  esac
done

[[ "$release_date" =~ ^[0-9]{8}$ ]] || fail "日期必须是 YYYYMMDD"
date -j -f '%Y%m%d' "$release_date" '+%Y%m%d' >/dev/null 2>&1 ||
  date -d "$release_date" '+%Y%m%d' >/dev/null 2>&1 || fail "日期无效：$release_date"

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

configured_version="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' mobile/build.gradle.kts | head -n 1)"
[[ -n "$configured_version" ]] || fail "无法读取 mobile/build.gradle.kts 的基础版本号"
base_version="${configured_version#v}"
[[ "$base_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "基础版本号格式无效：$base_version"

if [[ -z "$repo" ]]; then
  origin_url="$(git remote get-url origin 2>/dev/null)" || fail "无法读取 origin"
  repo="$(printf '%s\n' "$origin_url" | sed -E 's#^https://github.com/##; s#^git@github.com:##; s#\.git$##')"
  [[ "$repo" == */* && "$repo" != "$origin_url" ]] || fail "无法从 origin 推导 GitHub 仓库"
fi
[[ "$repo" =~ ^[^/]+/[^/]+$ ]] || fail "仓库格式必须是 OWNER/REPO"

notes_path="${notes_path:-docs/UPDATE-NOTES-${base_version}.md}"
[[ "$notes_path" != /* && "$notes_path" != *..* && -s "$notes_path" ]] ||
  fail "发布说明必须是仓库内非空 Markdown 文件：$notes_path"

if [[ -n "$sequence" ]]; then
  [[ "$sequence" =~ ^[0-9]+$ && "$sequence" -ge 1 && "$sequence" -le 999 ]] ||
    fail "序号必须在 1 到 999 之间"
else
  command -v gh >/dev/null || fail "未安装 GitHub CLI (gh)"
  gh auth status -h github.com >/dev/null 2>&1 || fail "GitHub CLI 未登录，请先运行 gh auth login -h github.com"
  highest=0
  short_release_date="${release_date:2:6}"
  while IFS= read -r tag_name; do
    if [[ "$tag_name" =~ ^(v${base_version//./\.}-b${short_release_date}-|v?${base_version//./\.}-build${release_date}-)([0-9]{3})$ ]]; then
      value=$((10#${BASH_REMATCH[2]}))
      ((value > highest)) && highest=$value
    fi
  done < <(gh release list --repo "$repo" --limit 1000 --json tagName --jq '.[].tagName')
  sequence=$((highest + 1))
  ((sequence <= 999)) || fail "当天发布序号已超过 999"
fi

printf -v sequence_padded '%03d' "$sequence"
short_release_date="${release_date:2:6}"
release_version="v${base_version}-b${short_release_date}-${sequence_padded}"
tag="$release_version"
head_sha="$(git rev-parse HEAD)"
expected_apk_version="$release_version"

printf '基础版本：%s\n' "$base_version"
printf '发布版本：%s\n' "$release_version"
printf 'APK 版本：%s\n' "$expected_apk_version"
printf 'Git 标签：%s\n' "$tag"
printf '目标仓库：%s\n' "$repo"
printf '发布分支：%s\n' "$release_ref"
printf '发布说明：%s\n' "$notes_path"
printf '构建方式：%s\n' "$([[ "$skip_build" == true ]] && printf '复用现有 APK' || printf '单次 Gradle Release 构建')"

if [[ "$publish" != true ]]; then
  printf '预演完成；未编译 APK，也未创建 GitHub Release。\n'
  exit 0
fi

[[ "$release_ref" == "main" ]] || fail "正式发布只允许 main 分支"
[[ "$(git branch --show-current)" == "$release_ref" ]] || fail "请切换到 $release_ref 分支后发布"
[[ -z "$(git status --porcelain)" ]] || fail "工作树不干净；调用发布脚本前必须先提交修改"
command -v gh >/dev/null || fail "未安装 GitHub CLI (gh)"
gh auth status -h github.com >/dev/null 2>&1 || fail "GitHub CLI 未登录，请先运行 gh auth login -h github.com"

remote_sha="$(git ls-remote origin "refs/heads/$release_ref" | awk '{print $1}')"
[[ -n "$remote_sha" && "$head_sha" == "$remote_sha" ]] || fail "本地 $release_ref 必须与 origin/$release_ref 完全一致"
gh release view "$tag" --repo "$repo" >/dev/null 2>&1 && fail "Release 已存在：$tag"

temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

if [[ "$skip_build" != true ]]; then
  read_secret_file ANDROID_KEYSTORE_PASSWORD .private/ANDROID_KEYSTORE_PASSWORD.txt
  read_secret_file ANDROID_KEY_ALIAS .private/ANDROID_KEY_ALIAS.txt
  read_secret_file ANDROID_KEY_PASSWORD .private/ANDROID_KEY_PASSWORD.txt
  read_secret_file PASTEDB_API_KEY_CIPHERTEXT .private/PASTEDB_API_KEY_CIPHERTEXT.txt
  read_secret_file PASTEDB_API_KEY_DECRYPTION_KEY .private/PASTEDB_API_KEY_DECRYPTION_KEY.txt
  if [[ -z "${ANDROID_KEYSTORE_PATH:-}" && -s .private/ANDROID_KEYSTORE_BASE64.txt ]]; then
    ANDROID_KEYSTORE_PATH="$temporary_directory/diplay-release.jks"
    decode_base64_file .private/ANDROID_KEYSTORE_BASE64.txt "$ANDROID_KEYSTORE_PATH"
    export ANDROID_KEYSTORE_PATH
  fi
  DIPLAY_AUTH_ASSETS_DIR="${DIPLAY_AUTH_ASSETS_DIR:-$project_root/.private/runtime-assets}"
  export DIPLAY_AUTH_ASSETS_DIR
  [[ -s "${ANDROID_KEYSTORE_PATH:-}" ]] || fail "缺少 ANDROID_KEYSTORE_PATH 或 .private/ANDROID_KEYSTORE_BASE64.txt"
  [[ -n "${ANDROID_KEYSTORE_PASSWORD:-}" ]] || fail "缺少 Android 签名库密码"
  [[ -n "${ANDROID_KEY_ALIAS:-}" ]] || fail "缺少 Android 签名别名"
  [[ -n "${ANDROID_KEY_PASSWORD:-}" ]] || fail "缺少 Android 签名私钥密码"
  [[ -s "$DIPLAY_AUTH_ASSETS_DIR/offline-mfi/identity.pk8" ]] || fail "缺少 CarPlay identity.pk8"
  [[ -s "$DIPLAY_AUTH_ASSETS_DIR/offline-mfi/certificate.p7b" ]] || fail "缺少 CarPlay certificate.p7b"
  [[ -n "$PASTEDB_API_KEY_CIPHERTEXT" && -n "$PASTEDB_API_KEY_DECRYPTION_KEY" ]] || fail "缺少 PasteDB 加密密文或解密密钥"
  gradle_user_home="${GRADLE_USER_HOME:-$project_root/.gradle}"
  GRADLE_USER_HOME="$gradle_user_home" ./gradlew \
    :mobile:assembleRelease \
    -PDIPLAY_RELEASE_VERSION="$release_version"
fi

[[ -s "$apk_path" ]] || fail "找不到待发布 APK：$apk_path"
aapt_path="$(find_aapt)" || fail "找不到 aapt，无法验证 APK 内部版本"
apk_version="$($aapt_path dump badging "$apk_path" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -n 1)"
[[ "$apk_version" == "$expected_apk_version" ]] ||
  fail "APK 版本不匹配：需要 $expected_apk_version，实际 ${apk_version:-未知}"

asset_name="DiPlay-${release_version}.apk"
asset_path="$temporary_directory/$asset_name"
cp "$apk_path" "$asset_path"
checksum="$(sha256_file "$apk_path")"
checksum_file="$temporary_directory/SHA256SUMS.txt"
printf '%s  %s\n' "$checksum" "$asset_name" > "$checksum_file"

gh release create "$tag" \
  "$asset_path" \
  "$checksum_file" \
  --repo "$repo" \
  --target "$head_sha" \
  --title "DiPlay ${release_version}" \
  --notes-file "$notes_path" \
  --latest
