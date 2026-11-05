# 차곡 서버(FastAPI) 이미지. 빌드 컨텍스트는 레포 루트:
#   docker build -f deploy/Dockerfile -t chagok-api:local .
# 이미지에 들어가는 건 server/(설치용)와 fixtures/뿐이다. 키(.env)는 넣지 않는다.
# 키는 실행할 때 compose의 env_file(/opt/chagok/.env)로만 들어온다. .dockerignore도 같이 본다.
FROM python:3.12-slim

# 호스트 ubuntu 사용자(uid 1000)와 맞춘다. /opt/chagok/data(소유자 ubuntu)를 /data로 붙여 쓰기 때문이다.
ARG APP_UID=1000

ENV CHAGOK_FIXTURES=/app/fixtures \
    CHAGOK_DB=/data/chagok.sqlite3 \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_ROOT_USER_ACTION=ignore

WORKDIR /app

# 의존성·패키지 설치. fixtures보다 먼저 두어 fixtures만 바뀌면 이 층은 캐시를 쓴다.
COPY server/ /app/server/
RUN pip install --no-cache-dir /app/server \
 && rm -rf /app/server/build /app/server/*.egg-info

COPY fixtures/ /app/fixtures/

# 비루트 사용자. /data는 볼륨을 붙이지 않고 띄워도(로컬 확인) 쓸 수 있게 미리 만든다.
RUN groupadd --gid "${APP_UID}" chagok \
 && useradd --uid "${APP_UID}" --gid "${APP_UID}" --no-create-home --home-dir /app --shell /usr/sbin/nologin chagok \
 && install -d -o chagok -g chagok -m 750 /data

USER chagok

EXPOSE 8000

# 접근 로그를 끄므로 헬스체크 요청도 로그에 남지 않는다.
# compose의 caddy가 api healthy를 기다리므로 시작 구간에는 2초마다 확인한다(--start-interval, Docker 25+).
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --start-interval=2s --retries=3 \
  CMD ["python", "-c", "import sys, urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/v1/health', timeout=4).status == 200 else 1)"]

# --workers 1 은 반드시 지킨다: 카드 교체 세션을 이 프로세스 메모리에 둔다(워커가 둘이면 세션을 못 찾는다).
# --no-access-log: 접속 IP·경로를 남기지 않는다. 로그는 앱이 한 줄 JSON으로 따로 쓴다(chagok/obs.py).
# --proxy-headers --forwarded-allow-ips=*: 앞단 Caddy가 붙이는 X-Forwarded-* 를 믿는다(포트는 밖으로 열지 않는다).
CMD ["uvicorn", "chagok.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log", "--proxy-headers", "--forwarded-allow-ips=*"]
