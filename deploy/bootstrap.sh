#!/bin/bash
# 차곡 서버 인스턴스 준비. root로 bash에서 돈다. PC에서  deploy/deploy.sh --bootstrap  (ssh로 sudo bash 실행).
#   Lightsail 시작 스크립트(user-data)로는 넣지 않는다: Lightsail이 자기 #!/bin/sh 스크립트 뒤에 붙여 sh(dash)로 돌려
#   bash 문법에서 멈춘다(11/5 실제로 겪음). sh로 불리면 아래에서 알리고 멈춘다.
#   대상: Ubuntu 24.04 LTS. 몇 번 돌려도 결과가 같다(이미 된 단계는 건너뛴다).
#   수동 실행: sudo bash bootstrap.sh   또는 PC에서  deploy/deploy.sh --bootstrap
#
# 하는 일
#   1. Docker Engine + compose plugin (Docker 공식 apt 저장소)
#   2. ubuntu 사용자를 docker 그룹에
#   3. /opt/chagok/{app,data} (소유자 ubuntu), 크론 로그 파일과 회전 설정
#   4. 2GB 스왑 파일 (1GB 인스턴스에서 이미지 빌드할 때 대비)
# 하지 않는 일: ufw(Lightsail 방화벽을 쓴다), 시간대 변경(UTC 유지, deploy/crontab 참고), 키 설치(deploy.sh --push-env)
if [ -z "${BASH_VERSION:-}" ]; then echo "[chagok-bootstrap] bash로 실행해야 한다: deploy/deploy.sh --bootstrap" >&2; exit 1; fi
set -euo pipefail

APP_USER=ubuntu
CHAGOK_HOME=/opt/chagok
SWAPFILE=/swapfile
SWAP_MB=2048

export DEBIAN_FRONTEND=noninteractive
# 첫 부팅 때 unattended-upgrades가 apt 잠금을 잡고 있을 수 있어 기다린다.
APT=(apt-get -y -o DPkg::Lock::Timeout=600)

log() { echo "[chagok-bootstrap] $*"; }

if ! id "$APP_USER" >/dev/null 2>&1; then
  echo "[chagok-bootstrap] $APP_USER 사용자가 없다. Lightsail Ubuntu 이미지인지 확인" >&2
  exit 1
fi

# ---------------------------------------------------------------- 1. Docker
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  log "Docker 설치"
  "${APT[@]}" update
  "${APT[@]}" install ca-certificates curl
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  # shellcheck disable=SC1091
  CODENAME="$(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")"
  cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${CODENAME}
Components: stable
Signed-By: /etc/apt/keyrings/docker.asc
EOF
  "${APT[@]}" update
  "${APT[@]}" install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
else
  log "Docker 이미 있음: $(docker --version)"
fi
systemctl enable --now docker >/dev/null 2>&1 || true

# ---------------------------------------------------------------- 2. docker 그룹
if ! id -nG "$APP_USER" | tr ' ' '\n' | grep -qx docker; then
  log "$APP_USER 를 docker 그룹에 추가 (다음 로그인부터 적용)"
  usermod -aG docker "$APP_USER"
fi

# ---------------------------------------------------------------- 3. 디렉토리·로그
install -d -o "$APP_USER" -g "$APP_USER" -m 755 "$CHAGOK_HOME" "$CHAGOK_HOME/app"
install -d -o "$APP_USER" -g "$APP_USER" -m 750 "$CHAGOK_HOME/data"
# 소유자가 바뀌어 있었으면 되돌린다(내용은 건드리지 않는다).
chown "$APP_USER:$APP_USER" "$CHAGOK_HOME" "$CHAGOK_HOME/app" "$CHAGOK_HOME/data"

if [ ! -e /var/log/chagok-cron.log ]; then
  install -o "$APP_USER" -g "$APP_USER" -m 640 /dev/null /var/log/chagok-cron.log
fi
cat > /etc/logrotate.d/chagok <<'EOF'
# 차곡 크론 로그 (deploy/crontab). 주 1회, 4주 보관.
/var/log/chagok-cron.log {
	weekly
	rotate 4
	compress
	delaycompress
	missingok
	notifempty
	copytruncate
}
EOF
chmod 644 /etc/logrotate.d/chagok

# ---------------------------------------------------------------- 4. 스왑 2GB
if ! swapon --show=NAME --noheadings | grep -qx "$SWAPFILE"; then
  if [ ! -f "$SWAPFILE" ]; then
    log "스왑 ${SWAP_MB}MB 만들기"
    fallocate -l "${SWAP_MB}M" "$SWAPFILE" || dd if=/dev/zero of="$SWAPFILE" bs=1M count="$SWAP_MB" status=none
    chmod 600 "$SWAPFILE"
    mkswap "$SWAPFILE" >/dev/null
  fi
  chmod 600 "$SWAPFILE"
  swapon "$SWAPFILE"
fi
grep -qE "^${SWAPFILE}[[:space:]]" /etc/fstab || echo "${SWAPFILE} none swap sw 0 0" >> /etc/fstab
# 메모리가 남을 땐 스왑을 덜 쓰게
echo 'vm.swappiness=10' > /etc/sysctl.d/99-chagok.conf
sysctl -q -p /etc/sysctl.d/99-chagok.conf || true

log "끝. docker $(docker --version | awk '{print $3}' | tr -d ,), compose $(docker compose version --short), 스왑 $(swapon --show=SIZE --noheadings | head -n1)"
