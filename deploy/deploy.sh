#!/usr/bin/env bash
# 차곡 서버 배포. 개발 PC(Windows Git Bash·macOS·Linux)에서 레포 안 어디서든 실행한다.
#
#   HOST=13.209.135.60 SSH_KEY=~/.ssh/pleos-server.pem DOMAIN=api.rangeleft.com bash deploy/deploy.sh [--push-env]
#   bash deploy/deploy.sh --host 13.209.135.60 --key ~/.ssh/pleos-server.pem [--domain ...] [옵션]
#
# 입력(환경변수 또는 인자)
#   HOST / --host       고정 IP(pleos-server-ip)
#   SSH_KEY / --key     SSH 개인키 경로
#   DOMAIN / --domain   지금은 api.rangeleft.com. 생략하면 서버에 저장된 값(/opt/chagok/compose.env), 그것도 없으면 <IP를 대시로>.sslip.io(임시용)
#   SSH_USER / --user   기본 ubuntu
#   REF / --ref         배포할 커밋. 기본 HEAD. 커밋 안 된 변경은 배포되지 않는다(git archive). 롤백에도 쓴다
# 옵션
#   --push-env    로컬 레포 루트 .env 를 서버 /opt/chagok/.env 로 보낸다(권한 600). 내용은 출력하지 않는다
#   --refresh     첫 배포가 아니어도 sources·prices refresh 를 1회 돌린다
#   --bootstrap   배포 전에 deploy/bootstrap.sh 를 서버에서 sudo로 돌린다(user-data를 못 넣었을 때)
#
# 순서: 아카이브 → (.env) → 코드 교체(.env·data 유지) → 크론 설치 → compose up --build → 첫 배포면 refresh → https 헬스 확인
set -euo pipefail

say()  { printf '\n== %s\n' "$*"; }
warn() { printf '경고: %s\n' "$*" >&2; }
die()  { printf '오류: %s\n' "$*" >&2; exit 1; }
usage() { sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; }

HOST=${HOST:-}
SSH_KEY=${SSH_KEY:-}
DOMAIN=${DOMAIN:-}
SSH_USER=${SSH_USER:-ubuntu}
REF=${REF:-HEAD}
PUSH_ENV=0
FORCE_REFRESH=0
RUN_BOOTSTRAP=0

while [ $# -gt 0 ]; do
  case "$1" in
    --host)      HOST=${2:?--host 뒤에 값이 필요}; shift 2 ;;
    --key)       SSH_KEY=${2:?--key 뒤에 값이 필요}; shift 2 ;;
    --domain)    DOMAIN=${2:?--domain 뒤에 값이 필요}; shift 2 ;;
    --user)      SSH_USER=${2:?--user 뒤에 값이 필요}; shift 2 ;;
    --ref)       REF=${2:?--ref 뒤에 값이 필요}; shift 2 ;;
    --push-env)  PUSH_ENV=1; shift ;;
    --refresh)   FORCE_REFRESH=1; shift ;;
    --bootstrap) RUN_BOOTSTRAP=1; shift ;;
    -h|--help)   usage; exit 0 ;;
    *)           usage >&2; die "모르는 인자: $1" ;;
  esac
done

# ---------------------------------------------------------------- 입력 확인
[ -n "$HOST" ] || die "HOST(고정 IP)가 필요하다. 예: HOST=13.209.135.60"
[[ $HOST =~ ^[A-Za-z0-9.-]+$ ]] || die "HOST 형식이 이상하다: $HOST"
[ -n "$SSH_KEY" ] || die "SSH_KEY(개인키 경로)가 필요하다"
SSH_KEY=${SSH_KEY/#\~/$HOME}
[ -f "$SSH_KEY" ] || die "SSH_KEY 파일이 없다: $SSH_KEY"
[[ $SSH_USER =~ ^[a-z_][a-z0-9_-]*$ ]] || die "SSH_USER 형식이 이상하다: $SSH_USER"
if [ -n "$DOMAIN" ]; then
  [[ $DOMAIN =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || die "DOMAIN 형식이 이상하다: $DOMAIN"
fi

REPO_ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel) || die "git 레포 안에서 실행해야 한다"
cd "$REPO_ROOT"
REV=$(git rev-parse --verify --short=12 "${REF}^{commit}") || die "커밋을 찾을 수 없다: $REF"
git cat-file -e "${REV}:deploy/compose.yaml" 2>/dev/null || die "$REF 에 deploy/compose.yaml 이 없다(배포 파일이 들어오기 전 커밋)"
if [ "$REF" = HEAD ] && [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  warn "커밋 안 된 변경이 있다. 배포되는 건 HEAD($REV)뿐이다"
fi

SSH_OPTS=(-i "$SSH_KEY" -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 -o ServerAliveInterval=30)
TARGET="$SSH_USER@$HOST"
rsh() { ssh "${SSH_OPTS[@]}" "$TARGET" "$@"; }
rcp() { scp -q "${SSH_OPTS[@]}" "$@"; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------- 0. 서버 준비 확인
say "서버 연결 $TARGET (첫 부팅이면 시작 스크립트가 끝날 때까지 기다린다)"
rsh 'command -v cloud-init >/dev/null 2>&1 && cloud-init status --wait >/dev/null 2>&1; true'

if [ "$RUN_BOOTSTRAP" = 1 ]; then
  say "bootstrap.sh 실행 (sudo)"
  # stdin으로 넘기면 apt 등이 스크립트를 읽어 버릴 수 있어 파일로 보내 돌린다.
  git show "${REV}:deploy/bootstrap.sh" > "$TMP/bootstrap.sh"
  rcp "$TMP/bootstrap.sh" "$TARGET:chagok-bootstrap.sh"
  rsh 'rc=0; sudo -n bash ~/chagok-bootstrap.sh </dev/null || rc=$?; rm -f ~/chagok-bootstrap.sh; exit $rc'
fi

# 출력 = 서버에 저장된 도메인(비밀 아님). 그 밖의 출력은 stderr로.
CURRENT_DOMAIN=$(rsh 'bash -s' <<'REMOTE'
main() {
  set -euo pipefail
  H=/opt/chagok
  if [ ! -d "$H/app" ] || [ ! -w "$H" ]; then
    echo "$H 가 없거나 쓸 수 없다. bootstrap.sh 가 돌았는지 확인(deploy.sh --bootstrap)" >&2; exit 1
  fi
  command -v docker >/dev/null 2>&1 || { echo "docker 가 없다. bootstrap.sh 확인(deploy.sh --bootstrap)" >&2; exit 1; }
  install -d -m 700 "$H/.upload"
  if [ -f "$H/compose.env" ]; then sed -n 's/^CHAGOK_DOMAIN=//p' "$H/compose.env" | head -n 1; fi
}
main "$@" </dev/null
REMOTE
) || die "서버 확인 실패"

if [ -z "$DOMAIN" ]; then
  if [ -n "$CURRENT_DOMAIN" ]; then
    DOMAIN=$CURRENT_DOMAIN
  elif [[ $HOST =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    DOMAIN="${HOST//./-}.sslip.io"
  else
    die "DOMAIN 을 정해야 한다(HOST가 IP가 아니라 sslip.io 기본값을 만들 수 없다)"
  fi
fi
[[ $DOMAIN =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || die "서버에 저장된 DOMAIN 형식이 이상하다: $DOMAIN"
echo "커밋 $REV · 도메인 $DOMAIN"

# ---------------------------------------------------------------- 1. 아카이브 → scp
say "1/6 코드 묶기(git archive $REF) · 보내기"
git archive --format=tar.gz -o "$TMP/app.tar.gz" "$REV"
rcp "$TMP/app.tar.gz" "$TARGET:/opt/chagok/.upload/app.tar.gz"

# ---------------------------------------------------------------- (선택) .env
if [ "$PUSH_ENV" = 1 ]; then
  say ".env 보내기 (내용은 출력하지 않는다)"
  [ -f "$REPO_ROOT/.env" ] || die "레포 루트에 .env 가 없다(.env.example 참고)"
  grep -qE '^TOURAPI_SERVICE_KEY=.+' "$REPO_ROOT/.env" || warn "TOURAPI_SERVICE_KEY 가 비어 있다. sources refresh 가 실패한다"
  # 윈도우 편집기의 BOM·CRLF를 걷어 낸 사본을 보낸다(compose env_file이 키 이름을 잘못 읽지 않게).
  ( umask 077; LC_ALL=C sed "1s/^$(printf '\357\273\277')//" "$REPO_ROOT/.env" | tr -d '\r' > "$TMP/env" )
  rcp "$TMP/env" "$TARGET:/opt/chagok/.upload/env"
  rsh 'chmod 600 /opt/chagok/.upload/env && mv -f /opt/chagok/.upload/env /opt/chagok/.env'
  rm -f "$TMP/env"
fi

# ---------------------------------------------------------------- 2~5. 서버에서
say "2~5/6 코드 교체 · 크론 · compose up --build · (첫 배포면) refresh"
rsh bash -s -- "$DOMAIN" "$REV" "$FORCE_REFRESH" <<'REMOTE'
# 스크립트를 stdin으로 받으므로 함수로 감싸 전부 읽은 뒤 돌린다(docker가 stdin을 먹지 않게 </dev/null).
main() {
  set -euo pipefail
  DOMAIN=$1 REV=$2 FORCE_REFRESH=$3
  H=/opt/chagok
  ME=$(id -un)
  DOCKER=docker
  docker info >/dev/null 2>&1 || DOCKER="sudo -n docker"   # docker 그룹이 아직 안 먹었을 때
  [ -f "$H/.env" ] || { echo "$H/.env 가 없다. 처음이면 --push-env 로 보낸다" >&2; exit 1; }

  # 2. 코드 교체. .env·compose.env·data 는 그대로 둔다. 직전 코드는 app.prev 로 남긴다(롤백용).
  rm -rf "$H/app.new"
  mkdir -p "$H/app.new"
  tar -xzf "$H/.upload/app.tar.gz" -C "$H/app.new"
  printf '%s\n' "$REV" > "$H/app.new/REVISION"
  rm -rf "$H/app.prev"
  if [ -d "$H/app" ]; then mv "$H/app" "$H/app.prev"; fi
  mv "$H/app.new" "$H/app"
  rm -f "$H/.upload/app.tar.gz"
  printf 'CHAGOK_DOMAIN=%s\n' "$DOMAIN" > "$H/compose.env.new"
  mv -f "$H/compose.env.new" "$H/compose.env"

  # 3. 크론
  sudo -n install -o root -g root -m 644 "$H/app/deploy/crontab" /etc/cron.d/chagok
  [ -e /var/log/chagok-cron.log ] || sudo -n install -o "$ME" -g "$ME" -m 640 /dev/null /var/log/chagok-cron.log
  [ "$(date +%Z)" = UTC ] || echo "경고: 서버 시간대가 UTC가 아니다($(date +%Z)). deploy/crontab 시각은 UTC 기준" >&2

  # 4. 띄우기
  DC="$DOCKER compose --env-file $H/compose.env -f $H/app/deploy/compose.yaml"
  $DC up -d --build --remove-orphans </dev/null
  # Caddyfile 은 파일 바인드 마운트라 재시작해야 새 파일을 본다(경로가 새 app/ 으로 바뀌었기 때문).
  if [ -f "$H/app.prev/deploy/Caddyfile" ] && ! cmp -s "$H/app.prev/deploy/Caddyfile" "$H/app/deploy/Caddyfile"; then
    echo "Caddyfile 이 바뀌어 caddy 재시작"
    $DC restart caddy </dev/null
  fi

  # 5. 첫 배포(표식 파일 없음)거나 --refresh 면 1회 갱신. 실패해도 배포는 계속하고 다음 배포 때 다시 돈다.
  MARK="$H/data/.initial-refresh-done"
  if [ "$FORCE_REFRESH" = 1 ] || [ ! -e "$MARK" ]; then
    ok=1
    echo "-- sources refresh"
    flock -w 3600 "$H/.cron.lock" $DC exec -T api python -m chagok.sources refresh </dev/null || ok=0
    echo "-- prices refresh"
    flock -w 3600 "$H/.cron.lock" $DC exec -T api python -m chagok.prices refresh </dev/null || ok=0
    if [ "$ok" = 1 ]; then
      touch "$MARK"
    else
      echo "경고: refresh 일부가 실패했다. 다음 배포 때 다시 돈다(또는 --refresh)" >&2
    fi
  fi

  printf '%s %s %s\n' "$(date -Iseconds)" "$REV" "$DOMAIN" >> "$H/deploys.log"
  $DOCKER image prune -f >/dev/null 2>&1 </dev/null || true
  $DC ps </dev/null
}
main "$@" </dev/null
REMOTE

# ---------------------------------------------------------------- 6. https 헬스
say "6/6 https://$DOMAIN/v1/health (첫 배포면 인증서 발급에 1분쯤 걸린다)"
CURL_OPTS=(-fsS --max-time 10)
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) CURL_OPTS+=(--ssl-no-revoke) ;; esac
for _ in $(seq 1 40); do
  if body=$(curl "${CURL_OPTS[@]}" "https://$DOMAIN/v1/health" 2>/dev/null); then
    echo "$body"
    say "배포 끝: $REV → https://$DOMAIN"
    exit 0
  fi
  sleep 3
done
die "헬스 확인 실패. 서버에서 로그 확인: dc logs --tail 100 caddy api (deploy/README.md)"
