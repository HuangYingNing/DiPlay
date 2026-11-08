#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/publish-release.sh [options]

Calculates BASE-buildYYYYMMDD-NNN and triggers the signed Android GitHub
Actions release workflow. Nothing is published unless --publish is supplied.

Options:
  --publish             Dispatch and watch the GitHub Actions release workflow.
  --dry-run             Print the resolved release without dispatching (default).
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

publish=false
release_date="$(date +%Y%m%d)"
sequence=""
notes_path=""
repo=""
release_ref="main"

while (($#)); do
  case "$1" in
    --publish) publish=true; shift ;;
    --dry-run) publish=false; shift ;;
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

base_version="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' mobile/build.gradle.kts | head -n 1)"
[[ -n "$base_version" ]] || fail "无法读取 mobile/build.gradle.kts 的基础版本号"
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
  while IFS= read -r tag; do
    if [[ "$tag" =~ ^v?${base_version//./\.}-build${release_date}-([0-9]{3})$ ]]; then
      value=$((10#${BASH_REMATCH[1]}))
      ((value > highest)) && highest=$value
    fi
  done < <(gh release list --repo "$repo" --limit 1000 --json tagName --jq '.[].tagName')
  sequence=$((highest + 1))
  ((sequence <= 999)) || fail "当天发布序号已超过 999"
fi

printf -v sequence_padded '%03d' "$sequence"
release_version="${base_version}-build${release_date}-${sequence_padded}"
tag="v${release_version}"

printf '基础版本：%s\n' "$base_version"
printf '发布版本：%s\n' "$release_version"
printf 'Git 标签：%s\n' "$tag"
printf '目标仓库：%s\n' "$repo"
printf '发布分支：%s\n' "$release_ref"
printf '发布说明：%s\n' "$notes_path"

if [[ "$publish" != true ]]; then
  printf '预演完成；未触发 GitHub Release。使用 --publish 执行发布。\n'
  exit 0
fi

[[ "$release_ref" == "main" ]] || fail "正式发布只允许 main 分支"
[[ "$(git branch --show-current)" == "$release_ref" ]] || fail "请切换到 $release_ref 分支后发布"
[[ -z "$(git status --porcelain)" ]] || fail "工作树不干净，请先提交所有修改"
command -v gh >/dev/null || fail "未安装 GitHub CLI (gh)"
gh auth status -h github.com >/dev/null 2>&1 || fail "GitHub CLI 未登录，请先运行 gh auth login -h github.com"
allow_embedded_key="$(gh variable get ALLOW_EMBEDDED_PASTEDB_KEY --repo "$repo" --json value --jq '.value' 2>/dev/null || true)"
[[ "$allow_embedded_key" == "true" ]] ||
  fail "发布公开 APK 前，必须确认密钥可被逆向提取并设置仓库变量 ALLOW_EMBEDDED_PASTEDB_KEY=true"

head_sha="$(git rev-parse HEAD)"
remote_sha="$(git ls-remote origin "refs/heads/$release_ref" | awk '{print $1}')"
[[ -n "$remote_sha" && "$head_sha" == "$remote_sha" ]] || fail "本地 $release_ref 必须与 origin/$release_ref 完全一致"
gh release view "$tag" --repo "$repo" >/dev/null 2>&1 && fail "Release 已存在：$tag"

dispatched_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
gh workflow run android.yml \
  --repo "$repo" \
  --ref "$release_ref" \
  -f publish_release=true \
  -f release_version="$release_version" \
  -f release_notes_path="$notes_path"

run_id=""
for _ in {1..30}; do
  run_id="$(gh run list --repo "$repo" --workflow android.yml --branch "$release_ref" \
    --event workflow_dispatch --commit "$head_sha" --limit 10 \
    --json databaseId,createdAt --jq ".[] | select(.createdAt >= \"$dispatched_at\") | .databaseId" | head -n 1)"
  [[ -n "$run_id" ]] && break
  sleep 2
done
[[ -n "$run_id" ]] || fail "已触发工作流，但未找到对应的运行记录"

printf 'GitHub Actions：%s/actions/runs/%s\n' "https://github.com/$repo" "$run_id"
gh run watch "$run_id" --repo "$repo" --exit-status
gh release view "$tag" --repo "$repo" --json url --jq '.url'
