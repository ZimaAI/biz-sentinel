#!/usr/bin/env bash
# Consistent backup of this application's SQL data and its shutdown-saved vectors.
# Only the Biz Sentinel backend is briefly stopped; existing host services stay up.
set -Eeuo pipefail
umask 077
[[ $EUID -eq 0 ]] || { echo 'Run with sudo.' >&2; exit 1; }
test -f /etc/biz-sentinel/.managed-by-biz-sentinel
exec 9>/run/lock/biz-sentinel-deploy.lock
flock -n 9
backup_dir="/var/backups/biz-sentinel/data-$(date +%Y%m%d-%H%M%S)"
install -d -m 0700 "$backup_dir"
was_running=false
if systemctl is-active --quiet biz-sentinel-backend; then
    was_running=true
    systemctl stop biz-sentinel-backend
fi
restore_backend() {
    if [[ $was_running == true ]]; then systemctl start biz-sentinel-backend; fi
}
trap restore_backend EXIT
docker exec biz-sentinel-mysql sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -u root --single-transaction --routines --triggers --events --databases biz_sentinel' \
    | gzip > "$backup_dir/database.sql.gz"
tar -czf "$backup_dir/application-data.tar.gz" -C /var/lib biz-sentinel
tar -czf "$backup_dir/application-config.tar.gz" -C /etc biz-sentinel
sha256sum "$backup_dir"/*.gz > "$backup_dir/SHA256SUMS"
echo "Backup created: $backup_dir"
echo 'This backup includes secrets. Keep it private and copy it to separate storage.'
