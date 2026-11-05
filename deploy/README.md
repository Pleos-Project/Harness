# 차곡 서버 배포 (Lightsail 1대)

**구성**: Lightsail Ubuntu 24.04 인스턴스 1대에 Docker compose로 `api`(FastAPI, uvicorn 워커 1개)와 `caddy`(HTTPS 자동)를 띄우고, 하루 1회 갱신은 호스트 크론이 `docker compose exec`로 돌린다.

| 파일 | 하는 일 |
|---|---|
| `Dockerfile` | `api` 이미지. `server/`·`fixtures/`만 들어가고 비루트(uid 1000)로 돈다. 키는 넣지 않는다 |
| `compose.yaml` | `api`(포트는 밖에 열지 않음) + `caddy`(80·443·443/udp). 로그는 json-file 10MB×3 |
| `Caddyfile` | `{$CHAGOK_DOMAIN}` → `api:8000`. 접근 로그 없음. 인증서는 RSA 키 + ISRG Root X1 체인(아래 'TLS 체인') |
| `crontab` | `/etc/cron.d/chagok`. KST 04:10 `sources refresh`, 04:20 `prices refresh` (UTC로 적혀 있음) |
| `bootstrap.sh` | 인스턴스 시작 스크립트(user-data). Docker·스왑 2GB·디렉토리·로그 회전 |
| `deploy.sh` | PC에서 실행. 코드 보내기 → 교체 → 크론 → `up --build` → (첫 배포면) refresh → https 헬스 |
| `../.dockerignore` | 빌드 컨텍스트에서 `.env`·`app/`·`docs/` 등을 뺀다 |

## 리소스·비용

- 인스턴스 `pleos-server`, 고정 IP `pleos-server-ip`(13.209.135.60), SSH 키 페어 `pleos-server-key`(개인키 `~/.ssh/pleos-server.pem`, 효성 PC)
- AWS CLI 프로필 `--profile pleos` = IAM 사용자 `Pleos-server`(인라인 정책 `Pleos-lightsail` = `lightsail:*`만). 같은 계정의 다른 프로젝트 리소스는 건드리지 않는다
- 도메인 `13-209-135-60.sslip.io` → API `https://13-209-135-60.sslip.io`
- 번들 `micro_3_0`: 1GB 메모리, 월 7달러(크레딧으로 충당). 고정 IP는 인스턴스에 붙어 있는 동안 무료
- 메모리가 1GB라 서버에서 이미지를 빌드할 때를 대비해 bootstrap이 스왑 2GB를 만든다

## 서버 레이아웃

```
/opt/chagok/
  app/            코드. 배포마다 통째로 교체 (git archive 결과 + REVISION)
  app.prev/       직전 배포 코드 (롤백용, 한 벌만)
  .env            키 (TOURAPI_SERVICE_KEY 등). 유지. 600. --push-env 로만 바뀜
  compose.env     CHAGOK_DOMAIN=... (비밀 아님). 유지. deploy.sh가 씀
  data/           chagok.sqlite3 + .initial-refresh-done(첫 갱신 표식). 유지
  deploys.log     배포 기록 (시각 커밋 도메인)
/etc/cron.d/chagok          deploy/crontab
/var/log/chagok-cron.log    크론 출력 (/etc/logrotate.d/chagok 로 주 1회 회전, 4주 보관)
```

### CHAGOK_DOMAIN 을 compose에 넘기는 방법

키(`.env`)와 섞지 않으려고 **`/opt/chagok/compose.env`** 에 따로 둔다. `deploy.sh`가 `DOMAIN` 값으로 이 파일을 쓰고, compose는 항상 `--env-file`로 이 파일을 읽어 `caddy` 컨테이너의 `CHAGOK_DOMAIN` 환경변수로 넘긴다. Caddyfile은 `{$CHAGOK_DOMAIN}`으로 읽는다. `--env-file` 없이 compose를 부르면 `CHAGOK_DOMAIN 이 없다` 오류로 멈춘다.

서버에서 매번 길게 치지 않게 별칭을 만들어 둔다(아래 명령은 모두 이 별칭을 쓴다).

```bash
echo "alias dc='docker compose --env-file /opt/chagok/compose.env -f /opt/chagok/app/deploy/compose.yaml'" >> ~/.bashrc
source ~/.bashrc
```

> **주의**: `dc config`를 `-q` 없이 돌리면 `.env`의 키 값이 화면에 펼쳐진다. 문법 확인은 `dc config -q`만 쓴다. `docker inspect chagok-api-1`도 키를 보여 주니 출력을 채팅·이슈에 붙이지 않는다.

## 처음 배포

### Lightsail 준비 (처음 한 번, 11/5에 이렇게 만들었다)

```bash
P="--profile pleos"
( umask 077; aws lightsail create-key-pair $P --key-pair-name pleos-server-key     --query privateKeyBase64 --output text > ~/.ssh/pleos-server.pem )      # 개인키는 화면에 출력하지 않는다
aws lightsail create-instances $P --instance-names pleos-server --availability-zone ap-northeast-2a     --blueprint-id ubuntu_24_04 --bundle-id micro_3_0 --key-pair-name pleos-server-key --tags key=project,value=pleos
aws lightsail allocate-static-ip $P --static-ip-name pleos-server-ip
aws lightsail attach-static-ip $P --static-ip-name pleos-server-ip --instance-name pleos-server
aws lightsail put-instance-public-ports $P --instance-name pleos-server --port-infos   '[{"fromPort":22,"toPort":22,"protocol":"tcp"},{"fromPort":80,"toPort":80,"protocol":"tcp"},{"fromPort":443,"toPort":443,"protocol":"tcp"},{"fromPort":443,"toPort":443,"protocol":"udp"}]'
```

- **시작 스크립트(user-data)에 `bootstrap.sh`를 넣지 않는다.** Lightsail은 user-data를 자기 초기화 스크립트(`#!/bin/sh`) 뒤에 붙여 sh(dash)로 돌려서 bash 문법(`set -o pipefail`)에서 멈춘다(11/5 실제로 겪음). 첫 배포 때 `deploy.sh --bootstrap`이 ssh로 bash 실행한다.
- 윈도우 AWS CLI에 한글이 든 파일을 `file://`로 넘기면 cp949로 읽다 깨진다 → `AWS_CLI_FILE_ENCODING=UTF-8`을 앞에 붙인다.

전제: 위 준비 완료(인스턴스·고정 IP·방화벽 TCP 22·80·443 + UDP 443), SSH 개인키를 PC에 받아 둠.

1. **도메인 정하기.** 처음에는 `<고정IP를 대시로>.sslip.io`를 쓴다. sslip.io는 이름 안의 IP로 그대로 응답하는 공개 DNS라 도메인을 사지 않고도 Let's Encrypt 인증서를 받을 수 있다.
   예: 고정 IP `3.35.10.20` → `3-35-10-20.sslip.io`. `DOMAIN`을 생략하면 `deploy.sh`가 이 값을 만든다.
2. **키 준비.** 레포 루트 `.env`에 `TOURAPI_SERVICE_KEY` 등을 채운다(`.env.example` 참고). 이 파일은 커밋되지 않는다.
3. **배포** (레포 루트에서, Git Bash·macOS·Linux):

   ```bash
   HOST=13.209.135.60 SSH_KEY=~/.ssh/pleos-server.pem bash deploy/deploy.sh --bootstrap --push-env
   ```

   - `--bootstrap`: 새 인스턴스면 붙인다(Docker·스왑·디렉토리). 몇 번 돌려도 같은 결과라 다시 붙여도 된다.
   - 첫 배포라 `sources refresh`·`prices refresh`를 한 번 돌린다(TourAPI 호출이 많아 몇 분 걸릴 수 있다).
   - 끝에 `https://<도메인>/v1/health` 응답을 출력한다. 첫 인증서 발급에 1분쯤 걸린다.
4. 앱의 서버 주소를 `https://<도메인>`으로 맞춘다.

`git archive HEAD`로 묶으므로 **커밋한 것만** 배포된다. 커밋 안 된 변경이 있으면 경고만 하고 진행한다.

Windows 메모: Git Bash에서 돌린다. 키 경로는 `~/.ssh/pleos-server.pem`이나 `/c/Users/<나>/...` 꼴로 쓴다. SSH가 키 권한 경고를 내면 키를 `~/.ssh/` 아래로 옮기고 `chmod 600`을 한다.

## 재배포

```bash
HOST=13.209.135.60 SSH_KEY=~/.ssh/pleos-server.pem bash deploy/deploy.sh
```

도메인은 서버의 `compose.env`에 남아 있어 다시 줄 필요가 없다. 이미지가 바뀌면 `api` 컨테이너가 새로 떠서 **메모리에 둔 카드 세션이 사라진다**(앱은 다시 요청하면 된다). refresh는 첫 배포 때만 돈다. 강제로 돌리려면 `--refresh`를 붙인다.

## 키 갱신

```bash
# PC: 레포 루트 .env 를 고친 뒤
HOST=13.209.135.60 SSH_KEY=~/.ssh/pleos-server.pem bash deploy/deploy.sh --push-env
```

`.env`만 바꾸고 코드는 그대로 두고 싶으면 서버에서 직접 고친 뒤 `api`를 다시 만든다. 키는 컨테이너가 뜰 때 환경변수로 들어가므로 `restart`로는 반영되지 않는다.

```bash
nano /opt/chagok/.env        # 화면 공유 중이면 열지 않는다
dc up -d --force-recreate api
```

## 로그 보기

```bash
dc ps                              # 상태 (api는 healthy여야 한다)
dc logs --tail 100 -f api          # 앱의 한 줄 JSON 로그 (접속 IP·경로는 남기지 않는다)
dc logs --tail 100 caddy           # 인증서 발급·갱신 기록 (접근 로그는 꺼져 있다)
```

컨테이너 로그는 json-file 드라이버로 서비스마다 10MB×3까지만 남는다.

## 크론 확인

```bash
cat /etc/cron.d/chagok                       # 설치된 내용 (시각은 UTC: 19:10 UTC = 04:10 KST)
tail -n 50 /var/log/chagok-cron.log          # 실행 기록 (== 시각 / 출력 / == 종료 코드)
journalctl -u cron --since yesterday | grep -i chagok | tail   # cron이 실제로 돌렸는지
dc exec -T api python -m chagok.sources stats    # 후보 건수·마지막 갱신 시각
```

지금 바로 한 번 돌려 보려면 크론 줄과 같은 명령을 직접 친다.

```bash
dc exec -T api python -m chagok.sources refresh
dc exec -T api python -m chagok.prices refresh
```

`api`는 60초마다 DB의 갱신 시각을 확인해 후보를 다시 읽으므로 refresh 뒤에 재시작할 필요가 없다.

크론 로그 회전은 bootstrap이 `/etc/logrotate.d/chagok`에 넣는다(주 1회, 4주 보관, `copytruncate`). 시험해 보려면 `sudo logrotate -d /etc/logrotate.d/chagok`.

## 도메인 바꾸기

1. 새 도메인의 A 레코드를 고정 IP로 향하게 한다(sslip.io면 할 일 없음).
2. 새 도메인으로 다시 배포한다. `compose.env`가 바뀌어 caddy가 새로 뜨고 인증서를 새로 받는다.

   ```bash
   HOST=13.209.135.60 SSH_KEY=~/.ssh/pleos-server.pem DOMAIN=api.example.com bash deploy/deploy.sh
   ```

3. 앱의 서버 주소를 바꾼다.

서버에서만 바꾸려면 `echo CHAGOK_DOMAIN=api.example.com > /opt/chagok/compose.env && dc up -d`.

## TLS 체인

- **고른 것**: RSA 2048 키 + 최상위가 **ISRG Root X1**(또는 X1이 교차 서명한 인증서)인 체인. `Caddyfile` 전역 옵션 `key_type rsa2048`과 `cert_issuer acme { preferred_chains { root_common_name "ISRG Root X1" } }`.
- **이유**: Caddy 기본값(ECDSA 키)이면 Let's Encrypt가 `서버 → YE2 → ISRG Root YE → ISRG Root X2` 체인을 준다(11/5 첫 발급). X2는 최근 안드로이드에만 들어 있다. 앱이 도는 AAOS 에뮬레이터가 X2를 믿는지 모르므로 안드로이드 7.1.1부터 믿는 X1 쪽을 고른다.
- `root_common_name`은 체인 **마지막 인증서의 발급자(Issuer) CN**과 정확히 같은지로 고른다. 맞는 체인이 없으면 경고만 남기고 첫 체인을 쓴다.
- `cert_issuer`를 적으면 기본 발급자 목록(Let's Encrypt → ZeroSSL)을 대신해 Let's Encrypt만 쓴다.

### 키 종류·체인 설정을 바꾼 뒤

Caddy는 **이미 받아 둔 인증서가 유효하면 설정이 바뀌어도 다시 받지 않는다**(새 설정은 다음 갱신 때부터). 바로 바꾸려면 저장된 인증서를 옮겨 두고 caddy를 재시작한다. 이렇게 하면 발급이 1회 늘어난다. Let's Encrypt는 같은 이름 묶음에 **주 5회**까지만 발급하니 되풀이하지 않는다.

```bash
D=13-209-135-60.sslip.io
C=/data/caddy/certificates/acme-v02.api.letsencrypt.org-directory
B=/data/cert-backup/$D-$(date -u +%Y%m%dT%H%M)          # caddy_data 볼륨 안(인증서 저장 경로 밖)
dc exec -T caddy sh -c "mkdir -p /data/cert-backup && mv $C/$D $B"
dc restart caddy
dc logs --since 5m caddy | grep -iE 'obtain|certificate|chain|error' | tail
```

되돌리기(원하는 체인이 안 나왔을 때): 이전 커밋을 다시 배포해 `Caddyfile`을 되돌린 뒤, 옮겨 둔 인증서를 제자리에 놓고 caddy를 재시작한다.

```bash
dc exec -T caddy sh -c "rm -rf $C/$D && mv $B $C/$D"
dc restart caddy
```

### 확인 (서버 안에서)

회사·학교 방화벽이 HTTPS를 가로채 다시 서명하는 PC에서는 로컬 확인이 틀린 체인을 보여 준다. 서버에 ssh로 들어가 본다.

```bash
echo | openssl s_client -connect 127.0.0.1:443 -servername 13-209-135-60.sslip.io -showcerts 2>/dev/null \
  | awk '/BEGIN CERT/{c=""} {c=c $0 "\n"} /END CERT/{printf "%s", c | "openssl x509 -noout -subject -issuer"; close("openssl x509 -noout -subject -issuer")}'
echo | openssl s_client -connect 127.0.0.1:443 -servername 13-209-135-60.sslip.io 2>/dev/null \
  | openssl x509 -noout -text | grep 'Public Key Algorithm'     # rsaEncryption 이어야 한다
curl -fsS https://13-209-135-60.sslip.io/v1/health
```

마지막 줄의 발급자가 `ISRG Root X1`이면 된다(예: `... → ISRG Root YR`(발급자 X1) 또는 `... → R1x`(발급자 X1)).

## 롤백

PC에서 이전 커밋을 다시 배포하는 게 기본이다(`deploys.log`에 배포한 커밋이 남는다).

```bash
cat /opt/chagok/deploys.log                     # 서버에서 이전 커밋 확인
HOST=13.209.135.60 SSH_KEY=~/.ssh/pleos-server.pem bash deploy/deploy.sh --ref <이전 커밋>
```

PC를 쓸 수 없으면 서버에 남은 직전 코드(`app.prev`)로 바로 되돌린다.

```bash
cd /opt/chagok && mv app app.bad && mv app.prev app
dc up -d --build
dc restart caddy        # Caddyfile이 달랐을 때만 필요
```

`data/`의 SQLite는 롤백하지 않는다(코드만 되돌린다).

## 참고

- `--workers 1`은 바꾸지 않는다. 카드 교체 세션을 프로세스 메모리에 둔다.
- Caddy 이미지는 `caddy:2.11.8`로 고정했다(2026-11-05 Docker Hub 공식 이미지 최신 2.x). 올릴 때는 `compose.yaml`의 태그만 바꾸고 재배포한다.
- 인증서·ACME 계정은 `caddy_data` 볼륨에 있다. 지우면 다시 발급받는데 Let's Encrypt 발급 횟수 제한이 있으니 `dc down -v`는 쓰지 않는다.
- 서버 시간대는 UTC 그대로 둔다. 바꾸면 `crontab`의 시각도 고친다.
