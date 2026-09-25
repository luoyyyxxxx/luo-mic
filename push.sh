#!/bin/bash
# ============================================================
#  luo mic —— 一键推送到 GitHub
#
#  用法：
#     bash push.sh              提交当前改动并推送
#     bash push.sh "提交说明"   自定义提交说明
#     bash push.sh --status     只看状态，不推送
#
#  说明：
#   · 凭据从 ~/.config/luoy-mic/github-token 读取（权限 600，不在仓库里）
#   · 推送时令牌只出现在这一条命令里，用完立刻把远端改回干净地址
#   · 工作仓库：~/dsh/.build-scratch/gh-luo-mic（luo mic 源码）
#               ~/dsh/.build-scratch/ghprofile  （GitHub 主页 README）
# ============================================================
set -euo pipefail

TOKEN_FILE="$HOME/.config/luoy-mic/github-token"
REPO_DIR="/home/luoy/dsh/.build-scratch/gh-luo-mic"
PROFILE_DIR="/home/luoy/dsh/.build-scratch/ghprofile"
MSG="${1:-更新仓库内容}"
STATUS_ONLY=0
[ "${1:-}" = "--status" ] && STATUS_ONLY=1

# ---- 凭据 ----
if [ ! -f "$TOKEN_FILE" ]; then
  echo "✘ 找不到凭据文件：$TOKEN_FILE"
  exit 1
fi
TOKEN="$(cat "$TOKEN_FILE")"
[ -n "$TOKEN" ] || { echo "✘ 凭据文件是空的"; exit 1; }

sync_repo() { # $1=目录 $2=owner/repo
  local dir="$1" slug="$2"
  echo
  echo "▸ $slug"
  cd "$dir"
  # 让 luo-mic 仓库始终跟随源目录的最新内容
  if [ "$slug" = "luoyyyxxxx/luo-mic" ] && [ -d /home/luoy/dsh/luo-mic ]; then
    rsync -a --delete \
      --exclude='build/' --exclude='dist/' --exclude='.gradle/' --exclude='__pycache__/' --exclude='*.pyc' \
      --exclude='.build-scratch' --exclude='*.log' \
      --exclude='*.exe' --exclude='*.zip' --exclude='*.sha256' --exclude='*.apk' \
      /home/luoy/dsh/luo-mic/ ./ >/dev/null
  fi

  local changes
  changes="$(git status --porcelain | wc -l)"
  echo "  待提交文件：$changes"
  if [ "$STATUS_ONLY" = "1" ]; then
    git status --short | sed 's/^/    /' | head -20
    return
  fi
  if [ "$changes" != "0" ]; then
    git add -A
    git commit -q -m "$MSG"
    echo "  已提交：$MSG"
  else
    echo "  没有新改动（仍会推送一次以确保远端最新）"
  fi

  git remote set-url origin "https://x-access-token:${TOKEN}@github.com/${slug}.git"
  if git push origin main 2>&1 | sed -E 's/ghp_[A-Za-z0-9]+/***/g; s#//[^@]*@#//***@#g' | tail -3; then
    echo "  ✔ 推送完成"
  else
    echo "  ✘ 推送失败（令牌失效？重新生成后更新 $TOKEN_FILE）"
  fi
  git remote set-url origin "https://github.com/${slug}.git"   # 立刻清掉令牌
}

echo "=============================================="
echo "  luo mic · 推送到 GitHub"
echo "=============================================="
sync_repo "$REPO_DIR"  "luoyyyxxxx/luo-mic"
sync_repo "$PROFILE_DIR" "luoyyyxxxx/luoyyyxxxx"

echo
echo "▸ 校验远端状态"
for slug in luoyyyxxxx/luo-mic luoyyyxxxx/luoyyyxxxx; do
  code=$(curl -sS -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$slug")
  echo "  https://github.com/$slug -> HTTP $code"
done
echo
echo "✔ 全部完成"
