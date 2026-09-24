#!/bin/bash
# ============================================================
#  luo mic —— 一键推送到 GitHub
#
#  用法（在 ~/dsh/luo-mic 目录下执行）：
#     bash push.sh                    提交当前改动并推送两个仓库
#     bash push.sh "修了 xxx"         自定义提交说明
#     bash push.sh --status           只看状态，不推送
#
#  说明：
#   · 凭据从 ~/.config/luoy-mic/github-token 读取（权限 600，不在仓库里）
#   · 令牌只在推送的那一条命令里出现，推完立刻把远端改回干净地址
#   · 两个仓库：
#       ~/dsh/luo-mic         → github.com/luoyyyxxxx/luo-mic
#       ~/dsh/github-profile  → github.com/luoyyyxxxx/luoyyyxxxx（GitHub 主页）
# ============================================================
set -euo pipefail

TOKEN_FILE="$HOME/.config/luoy-mic/github-token"
REPO_DIR="/home/luoy/dsh/luo-mic"
PROFILE_DIR="/home/luoy/dsh/github-profile"

MSG="更新仓库内容"
STATUS_ONLY=0
if [ "${1:-}" = "--status" ]; then
  STATUS_ONLY=1
elif [ -n "${1:-}" ]; then
  MSG="$1"
fi

# ---- 凭据 ----
if [ ! -f "$TOKEN_FILE" ]; then
  echo "✘ 找不到凭据文件：$TOKEN_FILE"
  echo "  请把 GitHub 令牌写进去（只有 repo 权限就够）："
  echo "    printf '%s' 'ghp_xxx' > $TOKEN_FILE && chmod 600 $TOKEN_FILE"
  exit 1
fi
TOKEN="$(tr -d '[:space:]' < "$TOKEN_FILE")"
[ -n "$TOKEN" ] || { echo "✘ 凭据文件是空的"; exit 1; }

mask() { sed -E 's/ghp_[A-Za-z0-9]+/***/g; s#//[^@]*@#//***@#g'; }

push_repo() { # $1=目录 $2=owner/repo
  local dir="$1" slug="$2"
  echo
  echo "▸ $slug"
  cd "$dir"
  local changes
  changes="$(git status --porcelain | wc -l)"
  echo "  待提交文件：$changes"
  if [ "$STATUS_ONLY" = "1" ]; then
    [ "$changes" != "0" ] && git status --short | sed 's/^/    /' | head -20
    return 0
  fi
  if [ "$changes" != "0" ]; then
    git add -A
    git commit -q -m "$MSG"
    echo "  已提交：$MSG"
  else
    echo "  没有新改动"
  fi

  git remote get-url origin >/dev/null 2>&1 \
    && git remote set-url origin "https://x-access-token:${TOKEN}@github.com/${slug}.git" \
    || git remote add origin "https://x-access-token:${TOKEN}@github.com/${slug}.git"
  # 网络不稳时重试 3 次（这台机器到 GitHub 偶发 TLS 中断）
  local rc=1 attempt
  for attempt in 1 2 3; do
    if git push -u origin main 2>&1 | mask | tail -3; then rc=0; break; fi
    [ "$attempt" = "3" ] || { echo "  第 $attempt 次推送失败，5 秒后重试…"; sleep 5; }
  done
  # 立刻把令牌从远端地址里清掉
  git remote set-url origin "https://github.com/${slug}.git"
  if [ "$rc" = "0" ]; then
    echo "  ✔ 推送完成"
  else
    echo "  ✘ 推送失败（令牌可能失效，重新生成后更新 $TOKEN_FILE）"
    return 1
  fi
}

echo "=============================================="
echo "  luo mic · 推送到 GitHub"
echo "=============================================="
push_repo "$REPO_DIR"    "luoyyyxxxx/luo-mic"
push_repo "$PROFILE_DIR" "luoyyyxxxx/luoyyyxxxx"

if [ "$STATUS_ONLY" = "0" ]; then
  echo
  echo "▸ 校验远端"
  for slug in luoyyyxxxx/luo-mic luoyyyxxxx/luoyyyxxxx; do
    code=$(curl -sS -o /dev/null -w "%{http_code}" "https://api.github.com/repos/$slug")
    echo "  https://github.com/$slug -> HTTP $code"
  done
  echo
  echo "✔ 全部完成"
fi
