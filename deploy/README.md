# 차곡 서버 배포 (Lightsail 1대)

**구성**: Lightsail Ubuntu 24.04 인스턴스 1대에 Docker compose로 `api`(FastAPI, uvicorn 워커 1개)와 `caddy`(HTTPS 자동)를 띄우고, 하루 1회 갱신은 호스트 크론이 `docker compose exec`로 돌린다.

| 파일 | 하는 일 |
|---|---|
| `Dockerfile` | `api` 이미지. `server/`·`fixtures/`만 들어가고 비루트(uid 1000)로 돈다. 키는 넣지 않는다 |
| `compose.yaml` | `api`(포트는 밖에 열지 않음) + `caddy`(80·443·443/udp). 로그는 json-file 10MB×3 |
| `Caddyfile` | `{$CHAGOK_DOMAIN}` → `api:8000`. 접근 로그 없음 |
| `crontab` | `/etc/cron.d/chagok`. KST 04:10 `sources refresh`, 04:20 `prices refresh` (UTC로 적혀 있음) |
| `bootstrap.sh` | 인스턴스 시작 스크립트(user-data). Docker·스왑 2GB·디렉토리·로그 회전 |
| `deploy.sh` | PC에서 실행. 코드 보내기 → 교체 → 크론 → `up --build` → (첫 배포면) refresh → https 헬스 |
| `../.dockerignore` | 빌드 컨텍스트에서 `.env`·`app/`·`docs/` 등을 뺀다 |

## 리소스·비용

- 인스턴스 `chagok-api`, 고정 IP `chagok-ip`, AWS CLI 프로필 `--profile chagok`
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

전제: 인스턴스 `chagok-api`(Ubuntu 24.04, 시작 스크립트로 `bootstrap.sh`), 고정 IP `chagok-ip` 연결, 방화벽 TCP 22·80·443 + UDP 443 열림, SSH 개인키를 PC에 받아 둠.

1. **도메인 정하기.** 처음에는 `<고정IP를 대시로>.sslip.io`를 쓴다. sslip.io는 이름 안의 IP로 그대로 응답하는 공개 DNS라 도메인을 사지 않고도 Let's Encrypt 인증서를 받을 수 있다.
   예: 고정 IP `3.35.10.20` → `3-35-10-20.sslip.io`. `DOMAIN`을 생략하면 `deploy.sh`가 이 값을 만든다.
2. **키 준비.** 레포 루트 `.env`에 `TOURAPI_SERVICE_KEY` 등을 채운다(`.env.example` 참고). 이 파일은 커밋되지 않는다.
3. **배포** (레포 루트에서, Git Bash·macOS·Linux):

   ```bash
   HOST=3.35.10.20 SSH_KEY=~/.ssh/chagok.pem bash deploy/deploy.sh --push-env
   ```

   - 첫 부팅 직후라면 시작 스크립트(bootstrap)가 끝날 때까지 기다린 뒤 진행한다.
   - 시작 스크립트를 못 넣었으면 `--bootstrap`을 붙인다(서버에서 `sudo bash bootstrap.sh`).
   - 첫 배포라 `sources refresh`·`prices refresh`를 한 번 돌린다(TourAPI 호출이 많아 몇 분 걸릴 수 있다).
   - 끝에 `https://<도메인>/v1/health` 응답을 출력한다. 첫 인증서 발급에 1분쯤 걸린다.
4. 앱의 서버 주소를 `https://<도메인>`으로 맞춘다.

`git archive HEAD`로 묶으므로 **커밋한 것만** 배포된다. 커밋 안 된 변경이 있으면 경고만 하고 진행한다.

Windows 메모: Git Bash에서 돌린다. 키 경로는 `~/.ssh/chagok.pem`이나 `/c/Users/<나>/...` 꼴로 쓴다. SSH가 키 권한 경고를 내면 키를 `~/.ssh/` 아래로 옮기고 `chmod 600`을 한다.

## 재배포

```bash
HOST=3.35.10.20 SSH_KEY=~/.ssh/chagok.pem bash deploy/deploy.sh
```

도메인은 서버의 `compose.env`에 남아 있어 다시 줄 필요가 없다. 이미지가 바뀌면 `api` 컨테이너가 새로 떠서 **메모리에 둔 카드 세션이 사라진다**(앱은 다시 요청하면 된다). refresh는 첫 배포 때만 돈다. 강제로 돌리려면 `--refresh`를 붙인다.

## 키 갱신

```bash
# PC: 레포 루트 .env 를 고친 뒤
HOST=3.35.10.20 SSH_KEY=~/.ssh/chagok.pem bash deploy/deploy.sh --push-env
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
   HOST=3.35.10.20 SSH_KEY=~/.ssh/chagok.pem DOMAIN=api.example.com bash deploy/deploy.sh
   ```

3. 앱의 서버 주소를 바꾼다.

서버에서만 바꾸려면 `echo CHAGOK_DOMAIN=api.example.com > /opt/chagok/compose.env && dc up -d`.

## 롤백

PC에서 이전 커밋을 다시 배포하는 게 기본이다(`deploys.log`에 배포한 커밋이 남는다).

```bash
cat /opt/chagok/deploys.log                     # 서버에서 이전 커밋 확인
HOST=3.35.10.20 SSH_KEY=~/.ssh/chagok.pem bash deploy/deploy.sh --ref <이전 커밋>
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
