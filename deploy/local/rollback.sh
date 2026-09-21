#!/usr/bin/env bash
# Remove this deployment's public entry and stop only its own services.
# Database volume, uploaded files, certificates and configuration are retained.
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run with sudo.' >&2; exit 1; }
test -f /etc/biz-sentinel/.managed-by-biz-sentinel
exec 9>/run/lock/biz-sentinel-deploy.lock
flock -n 9
site=/etc/nginx/sites-enabled/biz-sentinel
if [[ -L "$site" ]]; then
    [[ $(readlink "$site") == /etc/nginx/sites-available/biz-sentinel ]] || exit 1
    rm "$site"
    if ! nginx -t; then
        ln -s /etc/nginx/sites-available/biz-sentinel "$site"
        echo 'Existing Nginx configuration has errors; deployment was not stopped.' >&2
        exit 1
    fi
    systemctl reload nginx
fi
systemctl disable --now biz-sentinel-frontend.service biz-sentinel-backend.service
docker compose --project-directory /etc/biz-sentinel/mysql --env-file /etc/biz-sentinel/mysql/.env \
    -f /etc/biz-sentinel/mysql/compose.yml stop
echo 'Biz Sentinel stopped. Its data and certificates are retained. Existing services were not stopped.'
