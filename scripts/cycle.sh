#!/usr/bin/env bash
#
# 전체 사이클 스모크용 기동 스크립트.
# 인프라(core) -> Mongo index -> user-service -> 나머지 서비스 순으로 띄운다.
# ai-service의 LLM 후보는 local 프로파일이 같은 머신의 Ollama 하나로 둔다. 그 서버와 모델이 준비됐는지 먼저 본다.
# 절차와 확인 항목은 docs/전체-사이클-스모크.md에 있다.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INFRA="$ROOT/scripts/infra.sh"
APP="$ROOT/scripts/app.sh"
PROFILE="${SPRING_PROFILES_ACTIVE:-local}"

# 셸에 이미 export된 값을 우선하고, 없는 키만 파일에서 채운다.
load_env() {
  local line key
  [[ -f "$1" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] || continue
    key="${line%%=*}"
    # 값이 빈 줄(KEY=)은 건너뛴다. 빈 문자열을 export하면 Spring이 그것을 값으로 채택해 yaml의 ${KEY:default}가 default 대신 빈 값이 된다.
    [[ -n "${line#*=}" ]] || continue
    [[ -n "${!key+x}" ]] || export "$line"
  done <"$1"
}

load_env "$ROOT/.env.$PROFILE"
load_env "$ROOT/.env"
export SPRING_PROFILES_ACTIVE="$PROFILE"

MONGO_CONTAINER="${MONGO_CONTAINER:-kachi-mongo}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kachi-kafka}"
USER_SERVICE_URL="${KACHI_USER_SERVICE_BASE_URL:-http://localhost:8080}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"

# user-service 다음에 띄우는 서비스. 넷은 기동 시 user-service 주소만 필요하고 실제 호출은 요청이 있을 때 한다.
FOLLOWERS=(collector-service ai-service notification-routing notification-service notification-worker)

usage() {
  cat <<'USAGE'
Usage: ./scripts/cycle.sh <start|stop|status>

  start   인프라(mysql redis mongo kafka) -> Mongo index -> user-service -> 나머지 5개 순으로 띄운다.
  stop    서비스 6개를 내리고 인프라를 내린다.
  status  서비스와 인프라 상태를 본다.

  ai-service의 LLM 후보는 local 프로파일(application-local.yaml)이 Ollama 하나로 둔다. env로 덮는 값은 없다.
  Ollama 서버(localhost:11434)와 모델 qwen3.8:27b는 미리 준비한다. 다른 주소는 KACHI_AI_LLM_PROVIDERS_OLLAMA_BASEURL로 준다.
USAGE
}

wait_for_health() {
  local url="$1/actuator/health"
  local name="$2"
  local i
  for ((i = 0; i < HEALTH_TIMEOUT; i++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "$name is healthy."
      return 0
    fi
    sleep 1
  done
  echo "$name did not become healthy in ${HEALTH_TIMEOUT}s: $url" >&2
  return 1
}

# compose의 kafka에는 healthcheck가 없어 --wait가 broker를 기다리지 않는다. 토픽 목록이 나올 때까지 기다린다.
wait_for_kafka() {
  local i
  for ((i = 0; i < 60; i++)); do
    if docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
      echo "kafka is ready."
      return 0
    fi
    sleep 1
  done
  echo "kafka did not become ready in 60s" >&2
  return 1
}

# Ollama 서버가 떠 있고 모델을 받아 뒀는지 본다. 없으면 ai-service가 기동은 되지만 요약마다 실패한다.
# 모델은 ai-service의 LlmModel.OLLAMA_QWEN3_27B.code와 같은 값이다. 후보 모델은 코드에 있어 env로 바꾸지 않는다.
wait_for_ollama() {
  local base="${KACHI_AI_LLM_PROVIDERS_OLLAMA_BASEURL:-http://localhost:11434/v1}"
  local model="qwen3.8:27b"
  if ! curl -fsS "${base%/v1}/api/tags" >/dev/null 2>&1; then
    echo "ollama is not running at ${base%/v1}. run 'ollama serve' first." >&2
    return 1
  fi
  if ! curl -fsS "${base%/v1}/api/tags" | grep -q "\"name\":\"$model\""; then
    echo "ollama model $model is not pulled. run 'ollama pull $model' first." >&2
    return 1
  fi
  echo "ollama is ready ($model)."
}

# 어댑터의 auto-index-creation이 만들지 않는 index는 스크립트로 만든다. 여러 번 실행해도 같은 결과다.
create_mongo_indexes() {
  local script
  for script in notification-indexes.js notification-routing-indexes.js; do
    docker exec -i "$MONGO_CONTAINER" mongosh --quiet <"$ROOT/infra/mongo/$script" >/dev/null \
      && echo "mongo index applied: $script" \
      || { echo "mongo index failed: $script" >&2; return 1; }
  done
}

start() {
  "$INFRA" core start || return 1
  wait_for_kafka || return 1
  create_mongo_indexes || return 1

  "$APP" user-service start
  wait_for_health "$USER_SERVICE_URL" user-service || return 1

  # ai-service의 events·relay·scheduler와 Ollama 후보는 local 프로파일이 켠다. 여기서는 Ollama 준비만 본다.
  wait_for_ollama || return 1

  local service
  for service in "${FOLLOWERS[@]}"; do
    "$APP" "$service" start
  done
  wait_for_health "http://localhost:8082" collector-service
  wait_for_health "http://localhost:8083" ai-service
  wait_for_health "http://localhost:8086" notification-routing
  wait_for_health "http://localhost:8084" notification-service
  wait_for_health "http://localhost:8085" notification-worker
}

stop() {
  "$APP" all stop
  "$INFRA" core stop
}

status() {
  "$APP" all status
  "$INFRA" core status
}

[[ $# -eq 1 ]] || { usage; exit 1; }
case "$1" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  *) usage; exit 1 ;;
esac
