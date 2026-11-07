#!/usr/bin/env bash
# Полный прогон релиза:
#   сборка на ноде (ci/remote_build.sh, SHA-256 архива и APK, копия в ~/Downloads) →
#   MEGA (храним 2 версии) → site/release.json → коммит и push → сообщение в Discord.
# Секреты: .mega или claude.md (mega login/password), DISCORD_WEBHOOK (или из claude.md).
# Использование: ci/release.sh ["что нового"]
set -euo pipefail
cd "$(dirname "$0")/.."
notes="${1:-}"
PY="${MEGA_PY:-$HOME/.venvs/wallet-mega/bin/python}"
secrets="$( [ -f .mega ] && echo .mega || echo claude.md )"

ci/remote_build.sh
version="$(bash ci/version.sh)"
apk="dist/Shelly-$version.apk"
sha="$(cut -d' ' -f1 "$apk.sha256")"

export MEGA_LOGIN="$(sed -nE 's/^mega login:\s*(\S+).*$/\1/p' "$secrets" | head -1)"
export MEGA_PASSWORD="$(sed -nE 's/^mega password:\s*(\S+).*$/\1/p' "$secrets" | head -1)"
url="$("$PY" ci/mega_upload.py "$apk")"
echo "MEGA: $url"

tools/update_site.sh "$apk" "$url" >/dev/null
prev="$(python3 -I -c 'import json;p=json.load(open("site/release.json")).get("previous");print(p["version"] if p else "")')"
prev_url="$(python3 -I -c 'import json;p=json.load(open("site/release.json")).get("previous");print(p["url"] if p else "")')"

git add -A
git commit -q -m "release $version${prev:+ (previous $prev kept for rollback)}" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" || true
# Сайт — GitHub Pages; если репозитория ещё нет, релиз продолжается без публикации сайта.
site=""
if git push -q "${GIT_PUSH_URL:-git@github.com:relkin016/shelly_app.git}" HEAD:main; then
  site="https://relkin016.github.io/shelly_app/"
else
  echo "push не удался — сайт не обновлён" >&2
fi

webhook="${DISCORD_WEBHOOK:-$(cat claude.md CLAUDE.md 2>/dev/null | grep -oE 'https://discord.com/api/webhooks/[^ ]+' | head -1 || true)}"
notify() {
  echo "🔌 **Shelly $version** собран и выложен"
  [ -n "$notes" ] && echo "$notes"
  echo "APK: $url"
  echo "SHA-256: \`$sha\`"
  echo "Размер: $(( $(stat -c %s "$apk") / 1024 )) КБ · подпись проверена, x86 нет"
  [ -n "$prev" ] && echo "Откат: $prev — $prev_url"
  [ -n "$site" ] && echo "Сайт: $site" || echo "Сайт: не опубликован (нет репозитория на GitHub)"
}
notify | DISCORD_WEBHOOK="$webhook" python3 -I ci/discord.py
echo "готово: $version"
