#!/usr/bin/env bash
# Update an existing Commerce deployment in /opt/biz-sentinel.
# Run from a local terminal with sudo; no credentials are printed.
set -Eeuo pipefail
umask 027

[[ $EUID -eq 0 ]] || { echo '请在本机终端使用 sudo 执行此脚本。' >&2; exit 1; }

SOURCE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
JAR="$SOURCE/data-agent-management/target/spring-ai-alibaba-data-agent-management-1.0.0-SNAPSHOT.jar"
FRONTEND="$SOURCE/data-agent-frontend-nuxt/.output"
ROOT=/opt/biz-sentinel
SITE=/etc/nginx/sites-available/biz-sentinel
STAMP=$(date +%Y%m%d-%H%M%S)
REV=$(git -C "$SOURCE" rev-parse --short HEAD)
RELEASE="$ROOT/releases/${STAMP}-${REV}"
BACKUP="/var/backups/biz-sentinel/update-${STAMP}"

test -s "$JAR" || { echo "后端 jar 不存在，请先执行 Maven package: $JAR" >&2; exit 1; }
test -s "$FRONTEND/server/index.mjs" || { echo "前端产物不存在，请先执行 pnpm build: $FRONTEND" >&2; exit 1; }
test -L "$ROOT/current" || { echo "未找到 Commerce 当前 release: $ROOT/current" >&2; exit 1; }
test -f "$SITE" || { echo "未找到 Nginx 站点配置: $SITE" >&2; exit 1; }

PREVIOUS=$(readlink -f "$ROOT/current")
install -d -m 0700 "$BACKUP"
cp -a "$SITE" "$BACKUP/nginx-site"
printf '%s\n' "$PREVIOUS" > "$BACKUP/previous-release.txt"
install -d -m 0755 "$RELEASE/frontend"
install -m 0644 "$JAR" "$RELEASE/backend.jar"
cp -a "$FRONTEND/." "$RELEASE/frontend/"
chown -R root:root "$RELEASE"
chmod -R a+rX "$RELEASE"

python3 - "$SITE" <<'PY'
import pathlib
import sys

site = pathlib.Path(sys.argv[1])
lines = site.read_text().splitlines()
lines = [line for line in lines if not line.lstrip().startswith(('auth_basic ', 'auth_basic_user_file '))]
text = '\n'.join(lines) + '\n'
text = text.replace('client_max_body_size 10m;', 'client_max_body_size 34m;')
site.write_text(text)
PY
if ! nginx -t; then
    cp -a "$BACKUP/nginx-site" "$SITE"
    exit 1
fi

switch_release() {
    local target=$1
    local link="$ROOT/current.${STAMP}"
    ln -s "$target" "$link"
    mv -Tf "$link" "$ROOT/current"
}

changed=1
rollback() {
    if [[ $changed -eq 1 ]]; then
        echo '更新失败，正在恢复上一版本。' >&2
        systemctl stop biz-sentinel-backend biz-sentinel-frontend || true
        switch_release "$PREVIOUS"
        cp -a "$BACKUP/nginx-site" "$SITE"
        systemctl daemon-reload || true
        systemctl start biz-sentinel-backend biz-sentinel-frontend || true
        nginx -t && systemctl reload nginx || true
    fi
}
trap rollback ERR

systemctl stop biz-sentinel-backend biz-sentinel-frontend
switch_release "$RELEASE"
systemctl daemon-reload
systemctl start biz-sentinel-backend biz-sentinel-frontend
systemctl reload nginx

for attempt in $(seq 1 90); do
    if curl --noproxy '*' -fsS --max-time 3 http://127.0.0.1:8065/api/commerce/v1/health >/dev/null; then
        break
    fi
    [[ $attempt -lt 90 ]] || { echo '后端在 180 秒内未就绪。' >&2; exit 1; }
    sleep 2
done

python3 "$SOURCE/deploy/local/verify.py"
printf '已更新 Commerce release: %s\n备份: %s\n' "$RELEASE" "$BACKUP"
changed=0
