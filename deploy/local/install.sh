#!/usr/bin/env bash
# Host-specific, additive deployment. Run after building both applications.
set -Eeuo pipefail
umask 027

[[ $EUID -eq 0 ]] || { echo 'Run this script with sudo in your terminal.' >&2; exit 1; }
DEPLOY_SOURCE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_CONFIG="$DEPLOY_SOURCE/deploy/local"
DEPLOY_STATE=/home/zima/.local/state/biz-sentinel-deploy
DEPLOY_DOMAIN=bizsentinel.zimagent.top
DEPLOY_NODE=/home/zima/.nvm/versions/node/v24.17.0/bin/node
DEPLOY_JAR="$DEPLOY_SOURCE/data-agent-management/target/spring-ai-alibaba-data-agent-management-1.0.0-SNAPSHOT.jar"
DEPLOY_FRONTEND="$DEPLOY_SOURCE/data-agent-frontend-nuxt/.output"
DEPLOY_STAMP=$(date +%Y%m%d-%H%M%S)
DEPLOY_BACKUP="/var/backups/biz-sentinel/$DEPLOY_STAMP"
DEPLOY_SITE=/etc/nginx/sites-available/biz-sentinel
DEPLOY_LINK=/etc/nginx/sites-enabled/biz-sentinel
exec 9>/run/lock/biz-sentinel-deploy.lock
flock -n 9 || { echo 'Another Biz Sentinel deployment is running.' >&2; exit 1; }

for tool in docker nginx certbot python3 htpasswd java curl systemctl openssl; do
    command -v "$tool" >/dev/null
done
test -s "$DEPLOY_JAR"
test -s "$DEPLOY_FRONTEND/server/index.mjs"
test -x "$DEPLOY_NODE"
docker info >/dev/null
docker compose version
nginx -t

if [[ ! -f /etc/biz-sentinel/.managed-by-biz-sentinel ]]; then
    for path in /etc/biz-sentinel /opt/biz-sentinel /var/lib/biz-sentinel "$DEPLOY_SITE" "$DEPLOY_LINK" \
        /etc/systemd/system/biz-sentinel-backend.service /etc/systemd/system/biz-sentinel-frontend.service; do
        [[ ! -e "$path" && ! -L "$path" ]] || { echo "Refusing to overwrite existing path: $path" >&2; exit 1; }
    done
    for account in biz-sentinel biz-sentinel-web; do
        ! getent passwd "$account" >/dev/null || { echo "Existing account: $account" >&2; exit 1; }
    done
    ! docker container inspect biz-sentinel-mysql >/dev/null 2>&1 || { echo 'Container name already exists.' >&2; exit 1; }
    ! docker volume inspect biz-sentinel_mysql-data >/dev/null 2>&1 || { echo 'Database volume already exists.' >&2; exit 1; }
    ! docker network inspect biz-sentinel_default >/dev/null 2>&1 || { echo 'Docker network name already exists.' >&2; exit 1; }
    if ss -H -lnt '( sport = :8065 or sport = :13000 or sport = :13307 )' | read -r _; then
        echo 'An intended application port is already occupied.' >&2; exit 1
    fi
fi

install -d -m 0700 "$DEPLOY_BACKUP"
cp -a /etc/nginx "$DEPLOY_BACKUP/nginx"
systemctl list-units --type=service --state=running --no-pager > "$DEPLOY_BACKUP/services-before.txt"
ss -lntp > "$DEPLOY_BACKUP/ports-before.txt"
docker ps --format '{{.Names}} {{.Image}} {{.Status}} {{.Ports}}' > "$DEPLOY_BACKUP/containers-before.txt"
curl --noproxy '*' --fail --silent --show-error --max-time 10 \
    --resolve algomotion.zimagent.top:443:127.0.0.1 https://algomotion.zimagent.top/ \
    -o "$DEPLOY_BACKUP/existing-site-before.html"

install -d -m 0755 /etc/biz-sentinel /opt/biz-sentinel /opt/biz-sentinel/releases /opt/biz-sentinel/runtime
touch /etc/biz-sentinel/.managed-by-biz-sentinel
for account in biz-sentinel biz-sentinel-web; do
    if ! getent passwd "$account" >/dev/null; then
        useradd --system --user-group --home-dir /nonexistent --shell /usr/sbin/nologin "$account"
    fi
done
install -d -m 0750 -o biz-sentinel -g biz-sentinel /var/lib/biz-sentinel \
    /var/lib/biz-sentinel/uploads /var/lib/biz-sentinel/vectorstore
install -d -m 0700 /etc/biz-sentinel/mysql
install -m 0644 "$DEPLOY_CONFIG/compose.yml" /etc/biz-sentinel/mysql/compose.yml
install -m 0644 "$DEPLOY_SOURCE/data-agent-management/src/main/resources/sql/schema.sql" /etc/biz-sentinel/mysql/schema.sql
install -m 0644 "$DEPLOY_CONFIG/application-production.yml" /etc/biz-sentinel/application-production.yml

# No passwords are printed or passed as process arguments.
python3 - <<'PY'
import os, pathlib, secrets, subprocess
root = pathlib.Path('/etc/biz-sentinel')
db_env = root / 'mysql' / '.env'
if not db_env.exists():
    db_env.write_text('MYSQL_ROOT_PASSWORD=' + secrets.token_hex(32) + '\nMYSQL_PASSWORD=' + secrets.token_hex(32) + '\n')
    db_env.chmod(0o600)
values = dict(line.split('=', 1) for line in db_env.read_text().splitlines())
backend = root / 'backend.env'
backend.write_text(
    'DATA_AGENT_DATASOURCE_URL=jdbc:mysql://127.0.0.1:13307/biz_sentinel?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false\n'
    'DATA_AGENT_DATASOURCE_USERNAME=biz_sentinel\n'
    'DATA_AGENT_DATASOURCE_PASSWORD=' + values['MYSQL_PASSWORD'] + '\n'
    'DATA_AGENT_DATASOURCE_SQL_INIT=never\n'
    'DATAAGENT_SANDBOX_DOCKER_HOST=unix:///var/run/docker.sock\n'
    'DATAAGENT_SANDBOX_IMAGE=agentscope-registry.ap-southeast-1.cr.aliyuncs.com/agentscope/runtime-sandbox-base:latest\n')
backend.chmod(0o600)
login = root / 'admin-login.txt'
if not login.exists():
    password = secrets.token_urlsafe(24)
    login.write_text('URL=https://bizsentinel.zimagent.top\nUSERNAME=admin\nPASSWORD=' + password + '\n')
    login.chmod(0o600)
if not (root / 'htpasswd').exists():
    password = dict(line.split('=', 1) for line in login.read_text().splitlines())['PASSWORD']
    subprocess.run(['htpasswd', '-ciB', str(root / 'htpasswd'), 'admin'], input=password + '\n', text=True, check=True)
PY
chown root:www-data /etc/biz-sentinel/htpasswd
chmod 0640 /etc/biz-sentinel/htpasswd
install -o zima -g zima -m 0600 /etc/biz-sentinel/admin-login.txt "$DEPLOY_STATE/admin-login.txt"

DEPLOY_RELEASE="/opt/biz-sentinel/releases/$DEPLOY_STAMP"
install -d -m 0755 "$DEPLOY_RELEASE/frontend"
install -m 0644 "$DEPLOY_JAR" "$DEPLOY_RELEASE/backend.jar"
cp -a "$DEPLOY_FRONTEND/." "$DEPLOY_RELEASE/frontend/"
chown -R root:root "$DEPLOY_RELEASE"
chmod -R a+rX "$DEPLOY_RELEASE"
install -m 0755 "$DEPLOY_NODE" /opt/biz-sentinel/runtime/node
readlink /opt/biz-sentinel/current > "$DEPLOY_BACKUP/previous-release.txt" || true
ln -s "$DEPLOY_RELEASE" /opt/biz-sentinel/current.new
mv -Tf /opt/biz-sentinel/current.new /opt/biz-sentinel/current
install -m 0644 "$DEPLOY_CONFIG/biz-sentinel-backend.service" /etc/systemd/system/
install -m 0644 "$DEPLOY_CONFIG/biz-sentinel-frontend.service" /etc/systemd/system/
systemd-analyze verify /etc/systemd/system/biz-sentinel-{backend,frontend}.service

docker compose --project-directory /etc/biz-sentinel/mysql --env-file /etc/biz-sentinel/mysql/.env \
    -f /etc/biz-sentinel/mysql/compose.yml up -d --wait --wait-timeout 360
docker image inspect mysql:8.0 --format '{{json .RepoDigests}}' > "$DEPLOY_BACKUP/mysql-image-digest.txt"
docker inspect biz-sentinel-mysql --format '{{json .HostConfig.PortBindings}}' > "$DEPLOY_BACKUP/mysql-port-bindings.json"
python3 - "$DEPLOY_BACKUP/mysql-port-bindings.json" <<'PY'
import json, sys
bindings = json.load(open(sys.argv[1]))
assert bindings == {'3306/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '13307'}]}, bindings
PY
systemctl daemon-reload
systemctl enable biz-sentinel-backend.service biz-sentinel-frontend.service
systemctl restart biz-sentinel-backend.service biz-sentinel-frontend.service

backend_ready=false
for ((attempt=0; attempt<90; attempt++)); do
    if curl --noproxy '*' -fsS --max-time 3 http://127.0.0.1:8065/api/agent/list >/dev/null; then
        backend_ready=true; break
    fi
    sleep 2
done
if [[ $backend_ready != true ]]; then
    echo 'Backend did not become ready. Inspect: sudo journalctl -u biz-sentinel-backend -n 100' >&2
    exit 1
fi
curl --noproxy '*' -fsS --max-time 10 http://127.0.0.1:13000/agent/new >/dev/null

install -d -m 0755 /var/www/biz-sentinel-acme/.well-known/acme-challenge
if [[ ! -f "$DEPLOY_SITE" ]]; then
    install -m 0644 "$DEPLOY_CONFIG/nginx-http.conf" "$DEPLOY_SITE"
fi
if [[ ! -L "$DEPLOY_LINK" ]]; then
    [[ ! -e "$DEPLOY_LINK" ]] || { echo 'Unexpected enabled site file; refusing to overwrite.' >&2; exit 1; }
    ln -s "$DEPLOY_SITE" "$DEPLOY_LINK"
    if ! nginx -t; then
        rm -f "$DEPLOY_LINK"
        echo 'New site validation failed; existing Nginx was not reloaded.' >&2; exit 1
    fi
    systemctl reload nginx
fi

if ! python3 - <<'PY'
import socket, sys
try:
    ips = {x[4][0] for x in socket.getaddrinfo('bizsentinel.zimagent.top', 80, family=socket.AF_INET)}
except socket.gaierror:
    ips = set()
if ips != {'36.151.151.229'}:
    print('DNS is not ready. Set A record bizsentinel -> 36.151.151.229, then rerun this script.', file=sys.stderr)
    sys.exit(1)
PY
then
    echo 'Applications are running on loopback. HTTPS remains pending.' >&2
    exit 2
fi

certbot certonly --non-interactive --agree-tos --webroot \
    -w /var/www/biz-sentinel-acme -d "$DEPLOY_DOMAIN" --cert-name "$DEPLOY_DOMAIN" \
    --deploy-hook 'nginx -t && systemctl reload nginx'
cp -a "$DEPLOY_SITE" "$DEPLOY_BACKUP/biz-sentinel-site-before-https"
install -m 0644 "$DEPLOY_CONFIG/nginx-https.conf" "$DEPLOY_SITE"
if ! nginx -t; then
    cp -a "$DEPLOY_BACKUP/biz-sentinel-site-before-https" "$DEPLOY_SITE"
    echo 'HTTPS validation failed; restored the previous site file.' >&2; exit 1
fi
systemctl reload nginx
certbot renew --cert-name "$DEPLOY_DOMAIN" --dry-run

curl --noproxy '*' -fsS --max-time 10 --resolve algomotion.zimagent.top:443:127.0.0.1 \
    https://algomotion.zimagent.top/ -o "$DEPLOY_BACKUP/existing-site-after.html"
cmp "$DEPLOY_BACKUP/existing-site-before.html" "$DEPLOY_BACKUP/existing-site-after.html"
systemctl is-active nginx mysql postgresql@16-main docker biz-sentinel-backend biz-sentinel-frontend
ss -lntp > "$DEPLOY_BACKUP/ports-after.txt"
docker ps --format '{{.Names}} {{.Image}} {{.Status}} {{.Ports}}' > "$DEPLOY_BACKUP/containers-after.txt"
date -Is > "$DEPLOY_STATE/installed-at.txt"
chown zima:zima "$DEPLOY_STATE/installed-at.txt"
echo "Installed: https://$DEPLOY_DOMAIN"
echo "Credentials (private file): $DEPLOY_STATE/admin-login.txt"
echo "Deployment evidence and Nginx backup: $DEPLOY_BACKUP"
echo 'Python sandbox execution is intentionally disabled: the backend has no Docker socket access.'
echo 'Configure model credentials in the authenticated UI before AI acceptance.'
