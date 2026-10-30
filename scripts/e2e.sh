#!/usr/bin/env bash
# =====================================================================
#  ShortLink 端到端联调（Linux / macOS / WSL）
#  起容器 → 建链 → 列表 → 改链立即生效 → 封禁立即生效 → 缓存重建
#
#  用法：
#    bash scripts/e2e.sh               # 全流程
#    bash scripts/e2e.sh --skip-build  # 复用已有 jar
#    bash scripts/e2e.sh --skip-compose# 只跑断言，不动容器
#    bash scripts/e2e.sh --keep        # 跑完不销毁
#
#  依赖：JDK 17+、Maven、Docker + compose、curl
# =====================================================================
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_DIR/deploy/docker-compose.yml"
ADMIN_BASE="http://127.0.0.1:8082"
REDIRECT_BASE="http://127.0.0.1:8081"
STATS_BASE="http://127.0.0.1:8085"
JOB_BASE="http://127.0.0.1:8086"

SKIP_BUILD=0
SKIP_COMPOSE=0
KEEP_RUNNING=0
for arg in "$@"; do
  case "$arg" in
    --skip-build)   SKIP_BUILD=1 ;;
    --skip-compose) SKIP_COMPOSE=1 ;;
    --keep)         KEEP_RUNNING=1 ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

PASSED=0
FAILED=0

pass() { PASSED=$((PASSED+1)); echo -e "  \033[32m[PASS]\033[0m $1"; }
fail() { FAILED=$((FAILED+1)); echo -e "  \033[31m[FAIL]\033[0m $1 ${2:-}"; }
assert_eq() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "期望=$2 实际=$3"; fi; }

http_status() { curl -s -o /dev/null -w '%{http_code}' --max-redirs 0 "$1" || echo 000; }
http_location() {
  curl -s -o /dev/null -D - --max-redirs 0 "$1" | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r' | head -1
}
json_field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1; }

wait_ready() { # 名称 命令 超时秒
  local name="$1" probe="$2" timeout="${3:-180}" i=0
  while [ "$i" -lt "$timeout" ]; do
    if eval "$probe" >/dev/null 2>&1; then echo "  $name 已就绪"; return 0; fi
    sleep 2
    i=$((i+2))
  done
  echo "  $name 在 ${timeout}s 内未就绪" >&2
  return 1
}

create_link() { # longUrl -> JSON
  curl -s -X POST "$ADMIN_BASE/api/v1/links" -H 'Content-Type: application/json' \
    -d "{\"longUrl\":\"$1\",\"title\":\"e2e\"}"
}

update_link() { # gid longUrl title [expireTime]
  local gid="$1" url="$2" title="$3" expire="${4:-}"
  local body
  if [ -n "$expire" ]; then
    body="{\"longUrl\":\"$url\",\"title\":\"$title\",\"maxClick\":0,\"expireTime\":\"$expire\"}"
  else
    body="{\"longUrl\":\"$url\",\"title\":\"$title\",\"maxClick\":0}"
  fi
  curl -s -X PUT "$ADMIN_BASE/api/v1/links/$gid" -H 'Content-Type: application/json' -d "$body"
}

redis_hget() { # key field
  docker compose -f "$COMPOSE_FILE" exec -T redis redis-cli HGET "$1" "$2" 2>/dev/null | tr -d '\r' | head -1
}

echo ""
echo "=== 1/13 构建并启动 ==="
if [ "$SKIP_COMPOSE" -eq 1 ]; then
  echo "  --skip-compose：跳过构建与容器，直接测试已在运行的服务"
else
  if [ "$SKIP_BUILD" -eq 0 ]; then
    ( cd "$REPO_DIR" && mvn -q -DskipTests package )
  fi
  docker compose -f "$COMPOSE_FILE" up -d --build
fi

echo ""
echo "=== 2/13 等待服务就绪 ==="
ADMIN_OK=0; REDIRECT_OK=0
wait_ready "admin" "curl -sf $ADMIN_BASE/actuator/health" 180 && ADMIN_OK=1 || true
wait_ready "redirect" "curl -sf $REDIRECT_BASE/ping" 180 && REDIRECT_OK=1 || true
if [ "$ADMIN_OK" -ne 1 ] || [ "$REDIRECT_OK" -ne 1 ]; then
  echo "服务未就绪，最后 40 行日志："
  docker compose -f "$COMPOSE_FILE" logs --tail 40
  exit 1
fi

echo ""
echo "=== 3/13 核心链路 ==="
UNIQUE="$(date +%s)$RANDOM"
LONG_URL="https://example.com/e2e/$UNIQUE?a=1"
RESP="$(create_link "$LONG_URL")"
CODE="$(printf '%s' "$RESP" | json_field shortCode)"
GID="$(printf '%s' "$RESP" | json_field gid)"

if [ -n "$CODE" ] && [ ${#CODE} -eq 7 ]; then pass "创建接口返回 7 位短码 ($CODE)"; else fail "创建接口" "$RESP"; fi
assert_eq "跳转返回 302" "302" "$(http_status "$REDIRECT_BASE/$CODE")"
assert_eq "Location 指向原始长链" "$LONG_URL" "$(http_location "$REDIRECT_BASE/$CODE")"

DETAIL="$(curl -s "$ADMIN_BASE/api/v1/links/$GID")"
assert_eq "详情接口可查" "$CODE" "$(printf '%s' "$DETAIL" | json_field shortCode)"

echo ""
echo "=== 4/13 列表与改链（立即生效）==="
LIST="$(curl -s "$ADMIN_BASE/api/v1/links?size=5")"
if printf '%s' "$LIST" | grep -q '"items":\[\]'; then fail "列表返回数据" "$LIST"; else pass "列表返回数据"; fi
if printf '%s' "$LIST" | grep -q '"nextCursor"'; then pass "列表返回分页字段"; else fail "列表返回分页字段" "$LIST"; fi

# CODE 刚才被访问过，一定在 L1 里；直接改同一个码即可验证失效通道
NEW_URL="https://example.com/new/$UNIQUE"
UPDATED="$(update_link "$GID" "$NEW_URL" "updated")"
assert_eq "修改后 longUrl 已变" "$NEW_URL" "$(printf '%s' "$UPDATED" | json_field longUrl)"
assert_eq "改链后同一个短码立即跳新地址" "$NEW_URL" "$(http_location "$REDIRECT_BASE/$CODE")"
assert_eq "Redis 里的 longUrl 同步更新" "$NEW_URL" "$(redis_hget "sl:link:$CODE" longUrl)"

echo ""
echo "=== 5/13 错误分支 ==="
assert_eq "不存在的短码返回 404" "404" "$(http_status "$REDIRECT_BASE/zzzzzzz")"
assert_eq "格式非法返回 404"     "404" "$(http_status "$REDIRECT_BASE/abc")"

BAN_RESP="$(create_link "https://example.com/ban/$UNIQUE")"
BAN_GID="$(printf '%s' "$BAN_RESP" | json_field gid)"
BAN_CODE="$(printf '%s' "$BAN_RESP" | json_field shortCode)"
assert_eq "封禁前可正常跳转" "302" "$(http_status "$REDIRECT_BASE/$BAN_CODE")"
curl -s -X POST "$ADMIN_BASE/api/v1/links/$BAN_GID/disable" >/dev/null
assert_eq "封禁后同一个短码立即 403" "403" "$(http_status "$REDIRECT_BASE/$BAN_CODE")"
ENABLE_RESP="$(curl -s -X POST "$ADMIN_BASE/api/v1/links/$BAN_GID/enable")"
if printf '%s' "$ENABLE_RESP" | grep -q '"status":1'; then pass "解封接口返回 status=1"; else fail "解封接口" "$ENABLE_RESP"; fi

PAST="$(date -u -d '1 day ago' '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || date -v-1d '+%Y-%m-%dT%H:%M:%S')"
update_link "$GID" "$NEW_URL" "expired" "$PAST" >/dev/null
assert_eq "过期短链返回 410" "410" "$(http_status "$REDIRECT_BASE/$CODE")"

if curl -s "$REDIRECT_BASE/zzzzzzz" | grep -qi '<!doctype html>'; then pass "错误页是 HTML"; else fail "错误页是 HTML"; fi

echo ""
echo "=== 6/13 缓存重建（Redis 全丢后的恢复）==="
ORPHAN_RESP="$(create_link "https://example.com/orphan/$UNIQUE")"
ORPHAN_CODE="$(printf '%s' "$ORPHAN_RESP" | json_field shortCode)"
ORPHAN_LONG="$(printf '%s' "$ORPHAN_RESP" | json_field longUrl)"

docker compose -f "$COMPOSE_FILE" exec -T redis redis-cli FLUSHALL >/dev/null
sleep 0.5
assert_eq "FLUSHALL 后未缓存短码返回 404" "404" "$(http_status "$REDIRECT_BASE/$ORPHAN_CODE")"

# 版本号兜底：BAN_CODE 在 L1 里，FLUSHALL 把版本号也清了，轮询 1s 内应清空 L1
sleep 2
assert_eq "版本号兜底：FLUSHALL 后 L1 被清空（变 404）" "404" "$(http_status "$REDIRECT_BASE/$BAN_CODE")"

REBUILD="$(curl -s -X POST "$ADMIN_BASE/api/v1/admin/cache/rebuild?batchSize=500")"
SCANNED="$(printf '%s' "$REBUILD" | sed -n 's/.*"scanned":\([0-9]*\).*/\1/p')"
FAILED_WRITES="$(printf '%s' "$REBUILD" | sed -n 's/.*"failed":\([0-9]*\).*/\1/p')"
if [ -n "$SCANNED" ] && [ "$SCANNED" -gt 0 ]; then pass "重建接口扫描到 $SCANNED 条"; else fail "重建接口" "$REBUILD"; fi
assert_eq "重建无写入失败" "0" "${FAILED_WRITES:-unknown}"

assert_eq "重建后跳转恢复 302" "302" "$(http_status "$REDIRECT_BASE/$ORPHAN_CODE")"
assert_eq "重建后 Location 正确" "$ORPHAN_LONG" "$(http_location "$REDIRECT_BASE/$ORPHAN_CODE")"

echo ""
echo "=== 7/13 网关鉴权 ==="
GATEWAY_BASE="http://127.0.0.1:8080"

assert_eq "无 token 访问被拒绝（401）" "401" "$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY_BASE/api/v1/links")"
assert_eq "伪造 token 被拒绝（401）" "401" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer fake.token.value' "$GATEWAY_BASE/api/v1/links")"

LOGIN="$(curl -s -X POST "$GATEWAY_BASE/api/v1/auth/login" -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}')"
TOKEN="$(printf '%s' "$LOGIN" | json_field token)"
if [ -n "$TOKEN" ] && [ ${#TOKEN} -gt 20 ]; then pass "登录拿到 token"; else fail "登录" "$LOGIN"; fi

assert_eq "带 token 访问通过（200）" "200" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$GATEWAY_BASE/api/v1/links?size=1")"

ME="$(curl -s -H "Authorization: Bearer $TOKEN" "$GATEWAY_BASE/api/v1/auth/me")"
assert_eq "网关透传 X-User-Id" "1" "$(printf '%s' "$ME" | json_field userId)"

# 安全用例：客户端自己塞 X-User-Id 必须被网关剥掉，否则就是越权
SPOOF="$(curl -s -H "Authorization: Bearer $TOKEN" -H 'X-User-Id: 999' "$GATEWAY_BASE/api/v1/auth/me")"
assert_eq "客户端伪造的 X-User-Id 被剥离" "1" "$(printf '%s' "$SPOOF" | json_field userId)"

echo ""
echo "=== 8/13 IP 黑名单（网关 + redirect 双端生效）==="
curl -s -o /dev/null -X POST -H 'Content-Type: application/json' -d '{"ip":"127.0.0.1"}' "$ADMIN_BASE/api/v1/blacklist/ip"
# 黑名单是每 2s 拉一次全量快照，不是每请求查 Redis
sleep 3
assert_eq "拉黑后网关返回 403" "403" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$GATEWAY_BASE/api/v1/links?size=1")"
assert_eq "拉黑后 redirect 返回 403" "403" "$(curl -s -o /dev/null -w '%{http_code}' "$REDIRECT_BASE/zzzzzzz")"

curl -s -o /dev/null -X DELETE "$ADMIN_BASE/api/v1/blacklist/ip/127.0.0.1"
sleep 3
if [ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$GATEWAY_BASE/api/v1/links?size=1")" != "403" ]; then
  pass "解除后网关恢复（不再 403）"
else
  fail "解除后网关恢复（不再 403）"
fi

echo ""
echo "=== 9/13 限流（网关固定窗口 + redirect 单码热点保护）==="
# 清掉上一轮残留的网关限流计数
docker compose -f "$COMPOSE_FILE" exec -T redis redis-cli EVAL "local ks=redis.call('keys','sl:rate:*'); for i=1,#ks do redis.call('del',ks[i]) end; return #ks" 0 >/dev/null

HIT429=0
for _ in $(seq 1 700); do
  s="$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY_BASE/api/v1/links")"
  if [ "$s" = "429" ]; then HIT429=1; break; fi
done
if [ "$HIT429" -eq 1 ]; then pass "网关超过阈值后返回 429"; else fail "网关超过阈值后返回 429" "尝试 700 次仍未触发"; fi

# 单码热点保护：随机码，避免影响其他断言与跨轮次残留
RAND_CODE="$(head -c 64 /dev/urandom | base64 | tr -dc '0-9A-Za-z' | head -c 7)"
HIT_RD=0
for _ in $(seq 1 600); do
  s="$(curl -s -o /dev/null -w '%{http_code}' "$REDIRECT_BASE/$RAND_CODE")"
  if [ "$s" = "429" ]; then HIT_RD=1; break; fi
done
if [ "$HIT_RD" -eq 1 ]; then pass "redirect 单码超过阈值后触发 429"; else fail "redirect 单码超过阈值后触发 429" "尝试 600 次仍未触发"; fi

echo ""
echo "=== 10/13 幂等 / 批量 / 配额 / 审计 ==="

# 幂等：同一个 Idempotency-Key 提交两次，必须拿到同一个短码
IDEM_KEY="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen 2>/dev/null || date +%s%N)"
IDEM_BODY="{\"longUrl\":\"https://example.com/idem/$UNIQUE\"}"
IDEM1="$(curl -s -X POST "$ADMIN_BASE/api/v1/links" -H 'Content-Type: application/json' -H "Idempotency-Key: $IDEM_KEY" -d "$IDEM_BODY")"
IDEM2="$(curl -s -X POST "$ADMIN_BASE/api/v1/links" -H 'Content-Type: application/json' -H "Idempotency-Key: $IDEM_KEY" -d "$IDEM_BODY")"
assert_eq "幂等：两次提交返回同一个短码" "$(printf '%s' "$IDEM2" | json_field shortCode)" "$(printf '%s' "$IDEM1" | json_field shortCode)"

# 批量：一条合法 + 一条 javascript: 非法
BATCH_BODY="{\"items\":[{\"longUrl\":\"https://example.com/b1/$UNIQUE\"},{\"longUrl\":\"javascript:alert(1)\"}]}"
BATCH="$(curl -s -X POST "$ADMIN_BASE/api/v1/links/batch" -H 'Content-Type: application/json' -d "$BATCH_BODY")"
assert_eq "批量：1 条成功" "1" "$(printf '%s' "$BATCH" | json_field success)"
assert_eq "批量：1 条失败" "1" "$(printf '%s' "$BATCH" | json_field failed)"

# maxClick：只有配了上限的短链才走 Redis 配额计数，第 3 次应 403
LIMIT="$(curl -s -X POST "$ADMIN_BASE/api/v1/links" -H 'Content-Type: application/json' \
  -d "{\"longUrl\":\"https://example.com/limit/$UNIQUE\",\"maxClick\":2}")"
LIMIT_CODE="$(printf '%s' "$LIMIT" | json_field shortCode)"
S1="$(curl -s -o /dev/null -w '%{http_code}' "$REDIRECT_BASE/$LIMIT_CODE")"
S2="$(curl -s -o /dev/null -w '%{http_code}' "$REDIRECT_BASE/$LIMIT_CODE")"
S3="$(curl -s -o /dev/null -w '%{http_code}' "$REDIRECT_BASE/$LIMIT_CODE")"
assert_eq "maxClick：前 2 次 302、第 3 次 403" "302,302,403" "$S1,$S2,$S3"

# 审计：用 MYSQL_PWD 而不是 -p，避免 mysql 客户端把告警打到 stderr
AUDIT="$(docker compose -f "$COMPOSE_FILE" exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -N \
  -e "SELECT COUNT(*) FROM shortlink.t_operate_log" 2>/dev/null | tr -d '\r' | tail -1)"
if [ "${AUDIT:-0}" -gt 0 ] 2>/dev/null; then pass "操作审计有记录（$AUDIT 条）"; else fail "操作审计有记录" "实际 '$AUDIT'"; fi

# 审计要记真实操作人：通过网关创建（带 token），网关注入 X-User-Id
docker compose -f "$COMPOSE_FILE" exec -T redis redis-cli EVAL "local ks=redis.call('keys','sl:rate:*'); for i=1,#ks do redis.call('del',ks[i]) end; return #ks" 0 >/dev/null
GW_CREATED="$(curl -s -X POST "$GATEWAY_BASE/api/v1/links" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -d "{\"longUrl\":\"https://example.com/audit/$UNIQUE\"}")"
GW_GID="$(printf '%s' "$GW_CREATED" | json_field gid)"

CREATOR="$(docker compose -f "$COMPOSE_FILE" exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -N \
  -e "SELECT creator_id FROM shortlink.t_short_link WHERE gid='$GW_GID'" 2>/dev/null | tr -d '\r' | tail -1)"
assert_eq "短链记录了真实创建人（网关透传 X-User-Id）" "1" "$CREATOR"

OPERATOR="$(docker compose -f "$COMPOSE_FILE" exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -N \
  -e "SELECT operator_id FROM shortlink.t_operate_log WHERE biz_id='$GW_GID' AND action='CREATE'" 2>/dev/null | tr -d '\r' | tail -1)"
assert_eq "审计记录了真实操作人" "1" "$OPERATOR"

echo ""
echo "=== 11/13 统计查询（日聚合 + 实时 + 网关路由）==="
YESTERDAY="$(date -d 'yesterday' +%Y-%m-%d 2>/dev/null || date -v-1d +%Y-%m-%d)"
TODAY="$(date +%Y-%m-%d)"

# 聚合任务（T+1）还没实现，直接往统计库写一行昨天的数据验证查询链路
docker compose -f "$COMPOSE_FILE" exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot \
  -e "DELETE FROM shortlink_stats.t_stats_link_daily WHERE short_code='$CODE'" 2>/dev/null
docker compose -f "$COMPOSE_FILE" exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot \
  -e "INSERT INTO shortlink_stats.t_stats_link_daily (short_code, gid, stat_date, pv, uv) VALUES ('$CODE','','$YESTERDAY',100,40)" 2>/dev/null

# 今天的实时值
docker compose -f "$COMPOSE_FILE" exec -T redis redis-cli SET "sl:stats:pv:$CODE:$TODAY" 7 >/dev/null
docker compose -f "$COMPOSE_FILE" exec -T redis redis-cli PFADD "sl:stats:uv:$CODE:$TODAY" u1 u2 u3 >/dev/null

TREND="$(curl -s "$STATS_BASE/api/v1/stats/links/$CODE?from=$YESTERDAY&to=$TODAY")"
assert_eq "趋势返回 2 个点" "2" "$(printf '%s' "$TREND" | grep -o '"date"' | wc -l | tr -d ' ')"
assert_eq "昨天的 PV 来自聚合表" "100" "$(printf '%s' "$TREND" | sed -n 's/.*"date":"'"$YESTERDAY"'","pv":\([0-9]*\).*/\1/p')"
assert_eq "今天的 PV 来自 Redis 实时" "7" "$(printf '%s' "$TREND" | sed -n 's/.*"date":"'"$TODAY"'","pv":\([0-9]*\).*/\1/p')"

RT="$(curl -s "$STATS_BASE/api/v1/stats/links/$CODE/realtime")"
assert_eq "实时接口返回 PV=7" "7" "$(printf '%s' "$RT" | sed -n 's/.*"pv":\([0-9]*\).*/\1/p')"
assert_eq "实时 UV 由 HLL 估算为 3" "3" "$(printf '%s' "$RT" | sed -n 's/.*"uv":\([0-9]*\).*/\1/p')"

BAD="$(curl -s -o /dev/null -w '%{http_code}' "$STATS_BASE/api/v1/stats/links/$CODE?from=2026-01-01&to=2026-12-31")"
case "$BAD" in 4*) pass "超 90 天区间被拒绝（$BAD）";; *) fail "超 90 天区间被拒绝" "实际 $BAD";; esac

docker compose -f "$COMPOSE_FILE" exec -T redis redis-cli EVAL "local ks=redis.call('keys','sl:rate:*'); for i=1,#ks do redis.call('del',ks[i]) end; return #ks" 0 >/dev/null
GW_STATS="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$GATEWAY_BASE/api/v1/stats/links/$CODE/realtime")"
assert_eq "网关能把 /api/v1/stats 路由到 stats-api" "200" "$GW_STATS"

echo ""
echo "=== 12/13 定时任务（过期清理）==="
OLD_CODE="$(head -c 64 /dev/urandom | base64 | tr -dc '0-9A-Za-z' | head -c 7)"
OLD_GID="01JOB$(head -c 64 /dev/urandom | base64 | tr -dc '0-9A-Za-z' | head -c 21)"

# API 不允许创建过去的时间，只能直接 SQL 造一条 100 天前过期的短链
docker compose -f "$COMPOSE_FILE" exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -e \
  "INSERT INTO shortlink.t_short_link (gid, short_code, long_url, long_url_hash, domain_id, group_id, title, creator_id, status, expire_time, max_click, click_count, version, create_time, update_time, del_flag) VALUES ('$OLD_GID','$OLD_CODE','https://example.com/expired',MD5('https://example.com/expired'),1,0,'expired',0,1,NOW() - INTERVAL 100 DAY,0,0,0,NOW(),NOW(),0)" 2>/dev/null

JOB_RESP="$(curl -s -X POST "$JOB_BASE/api/v1/admin/jobs/expired-cleanup")"
if printf '%s' "$JOB_RESP" | grep -q '"executed":true'; then pass "任务执行成功"; else fail "任务执行成功" "$JOB_RESP"; fi

FLAG="$(docker compose -f "$COMPOSE_FILE" exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -N \
  -e "SELECT del_flag FROM shortlink.t_short_link WHERE short_code='$OLD_CODE'" 2>/dev/null | tr -d '\r' | tail -1)"
assert_eq "过期短链被软删（del_flag=1）" "1" "$FLAG"

DETAIL="$(curl -s -o /dev/null -w '%{http_code}' "$ADMIN_BASE/api/v1/links/$OLD_GID")"
assert_eq "清理后详情接口返回 404" "404" "$DETAIL"

echo ""
echo "=== 13/13 可观测性 ==="
METRICS="$(curl -s "$REDIRECT_BASE/actuator/prometheus")"
if printf '%s' "$METRICS" | grep -q 'shortlink_redirect_total'; then pass "暴露结果计数"; else fail "暴露结果计数"; fi
if printf '%s' "$METRICS" | grep -q 'shortlink_redirect_latency'; then pass "暴露延迟指标"; else fail "暴露延迟指标"; fi
if printf '%s' "$METRICS" | grep -q 'shortlink_cache_evict_total'; then pass "暴露缓存失效计数"; else fail "暴露缓存失效计数"; fi
if printf '%s' "$METRICS" | grep -q 'shortlink_redirect_ratelimit_rejected'; then pass "暴露本地限流计数"; else fail "暴露本地限流计数"; fi

echo ""
echo "=== 结果 ==="
echo "  通过 $PASSED / 失败 $FAILED"

if [ "$SKIP_COMPOSE" -eq 1 ]; then
  echo "  --skip-compose：不动容器，请自行停止服务"
elif [ "$KEEP_RUNNING" -eq 0 ]; then
  echo "清理容器与数据卷..."
  docker compose -f "$COMPOSE_FILE" down -v >/dev/null
else
  echo "容器保留中：docker compose -f $COMPOSE_FILE down -v 手动清理"
fi

[ "$FAILED" -eq 0 ]
