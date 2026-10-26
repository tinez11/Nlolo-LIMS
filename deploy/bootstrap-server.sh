#!/usr/bin/env bash
# One-time setup of a fresh Ubuntu 24.04 server (2026-10-13, deployment pilot §8). Run once, as root:
#
#   scp deploy/bootstrap-server.sh deploy/server/.env.example root@SERVER:
#   ssh root@SERVER bash bootstrap-server.sh
#
# Leaves a server that can receive releases: Docker, a deploy user with key-only SSH, a firewall open on 22/80/443
# only, a filled .env with generated secrets, and the nightly backup in cron.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }

echo "== packages"
apt-get update
apt-get install -y ca-certificates curl ufw unattended-upgrades openssl

echo "== docker"
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
dpkg-reconfigure -f noninteractive unattended-upgrades

echo "== deploy user"
id deploy > /dev/null 2>&1 || adduser --disabled-password --gecos "" deploy
usermod -aG docker deploy
mkdir -p /home/deploy/.ssh
cp /root/.ssh/authorized_keys /home/deploy/.ssh/
chown -R deploy:deploy /home/deploy/.ssh
chmod 700 /home/deploy/.ssh
chmod 600 /home/deploy/.ssh/authorized_keys
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/; s/^#\?PermitRootLogin.*/PermitRootLogin prohibit-password/' \
  /etc/ssh/sshd_config
systemctl reload ssh

echo "== firewall"
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "== swap"
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "== platform directory"
P=/home/deploy/platform
mkdir -p "$P/backups"
if [ ! -f "$P/.env" ]; then
  cp /root/.env.example "$P/.env"
  secret() { openssl rand -base64 36 | tr -d '/+=' | cut -c1-32; }
  for key in POSTGRES_PASSWORD APP_DB_PASSWORD KEYCLOAK_DB_PASSWORD KC_ADMIN_PASSWORD \
             OBJECT_STORE_SECRET_KEY PORTAL_ADMIN_CLIENT_SECRET STAFF_ADMIN_CLIENT_SECRET MOBILE_MONEY_CALLBACK_HMAC_SECRET; do
    sed -i "s|^$key=.*|$key=$(secret)|" "$P/.env"
  done
  sed -i "s|^PLATFORM_TENANT_ID=.*|PLATFORM_TENANT_ID=$(cat /proc/sys/kernel/random/uuid)|" "$P/.env"
  chmod 600 "$P/.env"
  echo "   .env created with generated secrets"
else
  echo "   .env already exists, left alone"
fi
chown -R deploy:deploy "$P"

echo "== nightly backup"
( crontab -u deploy -l 2>/dev/null | grep -v backup.sh
  echo "30 2 * * * cd $P && ./backup.sh nightly >> backups/cron.log 2>&1" ) | crontab -u deploy -

cat <<'NEXT'

Done. Next, in order:
  1. Edit /home/deploy/platform/.env -- APP_DOMAIN, AUTH_DOMAIN, ACME_EMAIL, IMAGE_PREFIX,
     the SMS settings, and the BACKUP_S3_* settings.
  2. Point DNS at this server: an A record for each of APP_DOMAIN and AUTH_DOMAIN.
     Caddy fetches the certificates itself, and cannot until those records resolve.
  3. Let the deploy user read the registry:  su - deploy -c 'docker login ghcr.io'
     (a GitHub token with read:packages).
  4. From your machine:  deploy/release.sh v0.1.0
     It prints the first staff login's one-time password once. Change it at first sign-in.
NEXT
