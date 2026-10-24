#!/usr/bin/env bash
# =====================================================================
#  ShortLink 端到端联调（Linux / macOS / WSL）
#
#  用法：
#    bash scripts/e2e.sh              # 全流程
#    bash scripts/e2e.sh --skip-build # 复用已有 jar
#    bash scripts/e2e.sh --keep       # 跑完不销毁
#
#  依赖：JDK 17+、Maven、Docker + compose、curl
# =====================================================================
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_DIR/deploy/docker-compose.yml"
ADMIN_BASE="http://127.0.0.1:8082"
REDIRECT_BASE="http://127.0.0.1:8081"

SKIP_BUILD=0
KEEP_RUNNING=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    --keep)       KEEP_RUNNING=1 ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

PASSED=0
FAILED=0

pass() { PASSED=$((PASSED+1)); echo -e "  \033[32m[PASS]\033[0m $1"; }
fail() { FAILED=$((FAILED+1)); echo -e "  \033[31m[FAIL]\033[0m $1 ${2:-}"; }
assert_eq() { # 名称 期望 实际
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "期望=$2 实际=$3"; fi
}
assert_true() { if [ "$2" = "true" ]; then pass "$1"; else fail "$1" "${3:-}"; fi; }

http_status() { # url -> 状态码，不跟随重定向
  curl -s -o /dev/null -w '%{http_code}' --max-redirs 0 "$1" || echo 000
}
http_location() {
  curl -s -o /dev/null -D - --max-redirs 0 "$1" | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r' | head -1
}
json_field() { # json 字段名 -> 值（依赖紧凑 JSON）
  sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1
}

wait_ready() { # 名称 命令 超时秒
  local name="$1" probe="$2" timeout="${3:-180}" i=0
  while [ "$i" -lt "$timeout" ]; do
    if eval "$probe" >/dev/null 2>&1; then
      echo "  $name 已就绪"
      return 0
    fi
    sleep 2
    i=$((i+2))
  done
  echo "  $name 在 ${timeout}s 内未就绪" >&2
  return 1
}

echo ""
echo "=== 1/5 构建并启动 ==="
if [ "$SKIP_BUILD" -eq 0 ]; then
  ( cd "$REPO_DIR" && mvn -q -DskipTests package )
fi
docker compose -f "$COMPOSE_FILE" up -d --build

echo ""
echo "=== 2/5 等待服务就绪 ==="
ADMIN_OK=0; REDIRECT_OK=0
wait_ready "admin" "curl -sf $ADMIN_BASE/actuator/health" 180 && ADMIN_OK=1 || true
wait_ready "redirect" "curl -sf $REDIRECT_BASE/ping" 180 && REDIRECT_OK=1 || true
if [ "$ADMIN_OK" -ne 1 ] || [ "$REDIRECT_OK" -ne 1 ]; then
  echo "服务未就绪，最后 40 行日志："
  docker compose -f "$COMPOSE_FILE" logs --tail 40
  exit 1
fi

echo ""
echo "=== 3/5 核心链路 ==="
UNIQUE="$(date +%s)$RANDOM"
LONG_URL="https://example.com/e2e/$UNIQUE?a=1"
RESP="$(curl -s -X POST "$ADMIN_BASE/api/v1/links" \
  -H 'Content-Type: application/json' \
  -d "{\"longUrl\":\"$LONG_URL\",\"title\":\"e2e\"}")"
CODE="$(printf '%s' "$RESP" | json_field shortCode)"
GID="$(printf '%s' "$RESP" | json_field gid)"

if [ -n "$CODE" ] && [ ${#CODE} -eq 7 ]; then pass "创建接口返回 7 位短码 ($CODE)"; else fail "创建接口" "$RESP"; fi

STATUS="$(http_status "$REDIRECT_BASE/$CODE")"
LOCATION="$(http_location "$REDIRECT_BASE/$CODE")"
assert_eq "跳转返回 302" "302" "$STATUS"
assert_eq "Location 指向原始长链" "$LONG_URL" "$LOCATION"

DETAIL="$(curl -s "$ADMIN_BASE/api/v1/links/$GID")"
DETAIL_CODE="$(printf '%s' "$DETAIL" | json_field shortCode)"
assert_eq "详情接口可查" "$CODE" "$DETAIL_CODE"

echo ""
echo "=== 4/5 错误分支 ==="
assert_eq "不存在的短码返回 404" "404" "$(http_status "$REDIRECT_BASE/zzzzzzz")"
assert_eq "格式非法返回 404"     "404" "$(http_status "$REDIRECT_BASE/abc")"

DISABLED_RESP="$(curl -s -X POST "$ADMIN_BASE/api/v1/links" -H 'Content-Type: application/json' \
  -d "{\"longUrl\":\"https://example.com/d/$UNIQUE\"}")"
DISABLED_CODE="$(printf '%s' "$DISABLED_RESP" | json_field shortCode)"
docker compose -f "$COMPOSE_FILE" exec -T redis redis-cli HSET "sl:link:$DISABLED_CODE" status 0 >/dev/null
assert_eq "停用短链返回 403" "403" "$(http_status "$REDIRECT_BASE/$DISABLED_CODE")"

EXPIRED_RESP="$(curl -s -X POST "$ADMIN_BASE/api/v1/links" -H 'Content-Type: application/json' \
  -d "{\"longUrl\":\"https://example.com/e/$UNIQUE\"}")"
EXPIRED_CODE="$(printf '%s' "$EXPIRED_RESP" | json_field shortCode)"
docker compose -f "$COMPOSE_FILE" exec -T redis redis-cli HSET "sl:link:$EXPIRED_CODE" expireTime 1 >/dev/null
assert_eq "过期短链返回 410" "410" "$(http_status "$REDIRECT_BASE/$EXPIRED_CODE")"

if curl -s "$REDIRECT_BASE/zzzzzzz" | grep -qi '<!doctype html>'; then
  pass "错误页是 HTML"
else
  fail "错误页是 HTML"
fi

echo ""
echo "=== 5/5 可观测性 ==="
METRICS="$(curl -s "$REDIRECT_BASE/actuator/prometheus")"
if printf '%s' "$METRICS" | grep -q 'shortlink_redirect_total'; then pass "暴露结果计数"; else fail "暴露结果计数"; fi
if printf '%s' "$METRICS" | grep -q 'shortlink_redirect_latency'; then pass "暴露延迟指标"; else fail "暴露延迟指标"; fi

echo ""
echo "=== 结果 ==="
echo "  通过 $PASSED / 失败 $FAILED"

if [ "$KEEP_RUNNING" -eq 0 ]; then
  echo "清理容器与数据卷..."
  docker compose -f "$COMPOSE_FILE" down -v >/dev/null
else
  echo "容器保留中：docker compose -f $COMPOSE_FILE down -v 手动清理"
fi

[ "$FAILED" -eq 0 ]
