# ShortLink · 高并发短链平台

读多写少的微服务实战项目：短码生成、302 跳转、异步统计、多级缓存一致性。

- 设计文档：[docs/shortlink-design.md](docs/shortlink-design.md)
- 模块实现规划：[docs/module-plan.md](docs/module-plan.md)
- 写链路工作流程：[docs/write-path.md](docs/write-path.md)
- 变更记录：[docs/changelog.md](docs/changelog.md)

## 模块一览

| 模块 | 端口 | 职责 | 依赖 MySQL |
|---|---|---|---|
| `shortlink-gateway` | 8080 | 路由、JWT、IP 限流、TraceId 注入 | 否 |
| `shortlink-admin` | 8082 | 短链 CRUD、分组、批量导入、发号、风控 | 是 |
| `shortlink-redirect` | 8081 | 短码解析 + 302 跳转（WebFlux） | **否（硬约束）** |
| `shortlink-stats-api` | 8083 | 统计查询 | 是（统计库） |
| `shortlink-stats-consumer` | 8084 | Kafka 消费、聚合、写 ClickHouse | 否 |
| `shortlink-job` | 8085 | 过期清理、预热、对账 | 是 |

公共库（`shortlink-lib`）：

| 模块 | 说明 |
|---|---|
| `shortlink-common-core` | 零依赖：Result / ErrorCode / BizException / TraceContext / ULID |
| `shortlink-common-web` | Servlet 栈：全局异常、TraceId Filter |
| `shortlink-common-webflux` | Reactive 栈：全局异常、TraceId WebFilter |
| `shortlink-common-cache` | Redis key 契约、Caffeine 工厂 |
| `shortlink-common-mq` | Topic 常量、事件模型、发布抽象 |
| `shortlink-component-codec` | Base62 + Feistel FPE + 短码编解码 |
| `shortlink-component-idgen` | 号段发号器（双 Buffer + 乐观锁） |
| `shortlink-component-bloom` | Redis Bitmap 布隆过滤器 |
| `shortlink-component-idempotent` | 幂等守卫 |
| `shortlink-component-ratelimit` | 本地令牌桶 |

## 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17+（升 21 只需改父 POM 的 `java.version`） |
| Maven | 3.9+ |
| MySQL | 8.0+ |
| Redis | 7.x |
| Kafka | 3.x（V3 起需要） |
| ClickHouse | 24.x（V3 起需要） |

## Docker 部署（推荐）

### 一键启动

```bash
# 1. 先在宿主机打 jar（镜像只拷 jar，不在容器里构建依赖）
mvn -DskipTests package

# 2. 起全套
docker compose -f deploy/docker-compose.yml up -d --build

# 3. 等四个服务都 healthy（首次启动 MySQL 要跑初始化脚本，约 30s）
docker compose -f deploy/docker-compose.yml ps
```

| 服务 | 端口 | 依赖 | 说明 |
|---|---|---|---|
| `mysql` | 3306 | — | 首次启动自动执行 `sql/01-schema.sql`、`02-init-data.sql` |
| `redis` | 6379 | — | `allkeys-lru`，512MB 上限（缓存可丢，能从 MySQL 重建） |
| `admin` | 8082 | mysql, redis | 写侧 |
| `redirect` | 8081 | redis | 读侧，**不连 MySQL** |

### 端到端联调

```powershell
powershell -File scripts/e2e.ps1          # Windows
```

```bash
bash scripts/e2e.sh                        # Linux / macOS / WSL
```

脚本自动完成：构建 → 起容器 → 等就绪 → 建链 → 断言 302/Location → 列表/改链 → 404/403/410 → 缓存重建 → 网关鉴权 → IP 黑名单 → 限流 → 幂等/批量/配额/审计 → 统计查询 → 定时任务清理 → Prometheus 指标，最后 `down -v` 清理。共 **62 项断言**。

常用开关：

| PowerShell | Bash | 作用 |
|---|---|---|
| `-SkipBuild` | `--skip-build` | 复用已有 jar，不重新打包 |
| `-KeepRunning` | `--keep` | 跑完不销毁容器，方便排查 |
| `-SkipCompose` | `--skip-compose` | 只跑断言，不动容器（服务已在运行时用） |

`-SkipCompose` 也适用于**不用 Docker 跑应用**的场景：本地 `java -jar` 起好 admin/redirect，只让 MySQL、Redis 在容器里，同样能跑完整套断言。

### 常用操作

```bash
# 改了 DDL 必须重建数据卷，否则初始化脚本不会重跑
docker compose -f deploy/docker-compose.yml down -v

# 跟踪单个服务日志
docker compose -f deploy/docker-compose.yml logs -f redirect

# 关掉 L1 做基准压测
L1_ENABLED=false docker compose -f deploy/docker-compose.yml up -d redirect
```

### 配置

```bash
cp deploy/.env.example deploy/.env    # 改端口 / 密码 / 域名
```

不创建 `.env` 时使用 compose 里的默认值。

### 两个坑

1. **镜像里的 jar 只有几十 KB** → `repackage` 没生效。父 POM 不是 `spring-boot-starter-parent`，`spring-boot-maven-plugin` 的 `repackage` 必须显式绑定 `<executions>`（已在根 POM 配好）。薄 jar 会导致 `java -jar` 报 `no main manifest attribute`。
2. **初始化脚本只在数据卷为空时执行**。改完 SQL 不 `down -v`，改动不会生效。

## 快速开始（不用 Docker）

### 1. 起中间件并初始化库

```bash
mysql -uroot -p < sql/01-schema.sql      # 建库建表
mysql -uroot -p < sql/02-init-data.sql   # 号段起点 / 默认域名 / 默认分组
```

`02-init-data.sql` 末尾有一条自检 SQL：号段起点若小于 `62^6`，短码会变成 6 位，脚本会直接提示 ERROR。

### 2. 编译并启动

```bash
mvn clean package -DskipTests

# 跳转服务：不依赖 MySQL，Redis 没起也能启动
mvn -pl shortlink-redirect spring-boot:run
curl -i http://127.0.0.1:8081/ping     # 期望 200 pong

# 管理服务：需要 MySQL + Redis
mvn -pl shortlink-admin spring-boot:run
```

### 3. 创建一条短链（V1 已实现）

```bash
curl -X POST http://127.0.0.1:8082/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/a/b?x=1","title":"测试"}'
```

期望返回：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "gid": "01JCTX3K9M2Q7V8N5R4T6B0W1A",
    "shortCode": "aB3xK9z",
    "shortUrl": "https://s.example.com/aB3xK9z",
    "longUrl": "https://example.com/a/b?x=1",
    "status": 1,
    "expireTime": null,
    "createTime": "2026-10-24T15:20:00"
  },
  "traceId": "0af7651916cd43dd"
}
```

验证三件事：

```bash
# 1) 元数据落库
mysql -uroot -p shortlink -e "select short_code, left(long_url,40) from t_short_link order by id desc limit 3;"

# 2) 缓存已写入（redirect 的唯一数据源）
redis-cli HGETALL sl:link:aB3xK9z

# 3) 详情接口
curl http://127.0.0.1:8082/api/v1/links/01JCTX3K9M2Q7V8N5R4T6B0W1A
```

### 4. 验证跳转（V1/V2 已实现）

```bash
CODE=aB3xK9z    # 换成上一步返回的 shortCode
GID=01JCTX3K... # 换成上一步返回的 gid

# 正常跳转：302 + Location + no-store + Content-Length: 0
curl -i "http://127.0.0.1:8081/$CODE"

# 不存在的短码：404 + HTML 错误页
curl -i http://127.0.0.1:8081/aaaaaaa

# 格式非法：404，且不会访问 Redis
curl -i http://127.0.0.1:8081/abc

# 封禁：走接口，秒级生效（这条请求刚把短码读进 L1，封禁后立刻变 403）
curl -X POST "http://127.0.0.1:8082/api/v1/links/$GID/disable"
curl -i "http://127.0.0.1:8081/$CODE"      # 403
curl -X POST "http://127.0.0.1:8082/api/v1/links/$GID/enable"

# Redis 故障：503（不是 404 —— "查不到"和"查不了"必须区分）
# 注意：L1 命中时仍会正常跳转，属预期行为
```

> **别直接改 Redis 来测封禁**（`redis-cli HSET ... status 0`）。V2 的失效通知是 admin 在写缓存后发出的，
> 手改 Redis 不会触发通知，L1 里还是旧值。要测就走接口。

### 5. 管理接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/links` | 创建（同分组同长链自动复用；支持 `Idempotency-Key`） |
| POST | `/api/v1/links/batch` | 批量创建（≤500 条，逐条返回失败原因） |
| GET | `/api/v1/links` | 列表，游标分页 |
| GET | `/api/v1/links/{gid}` | 详情 |
| PUT | `/api/v1/links/{gid}` | 修改（全量替换可变字段） |
| POST | `/api/v1/links/{gid}/disable` | 封禁（幂等） |
| POST | `/api/v1/links/{gid}/enable` | 解封 |
| POST | `/api/v1/admin/cache/rebuild` | 用 MySQL 重建 Redis 缓存 |
| POST | `/api/v1/auth/login` | 登录换 JWT（网关白名单，唯一免鉴权接口） |
| GET | `/api/v1/auth/me` | 回显当前身份（验证网关注入的 `X-User-Id`） |
| POST | `/api/v1/blacklist/ip` | 拉黑 IP（网关与 redirect 双端生效） |
| DELETE | `/api/v1/blacklist/ip/{ip}` | 解除拉黑 |
| GET | `/api/v1/blacklist/ip` | 查看黑名单 |
| GET | `/api/v1/stats/links/{shortCode}` | 日趋势 PV/UV（`from`/`to`，默认最近 7 天，最长 90 天） |
| GET | `/api/v1/stats/links/{shortCode}/realtime` | 今日实时 PV/UV（Redis） |

```bash
# 列表：第一页
curl 'http://127.0.0.1:8082/api/v1/links?size=20'
# 下一页：把上次返回的 nextCursor 带回来
curl 'http://127.0.0.1:8082/api/v1/links?size=20&cursor=1024'
# 过滤：分组 + 状态 + 关键字（标题模糊 / 短码、gid 精确）
curl 'http://127.0.0.1:8082/api/v1/links?groupId=1&status=0&keyword=双11'

# 修改（expireTime 传 null 表示改成永久，不是"不修改"）
curl -X PUT 'http://127.0.0.1:8082/api/v1/links/{gid}' \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/new","title":"新标题","expireTime":null,"maxClick":0}'

# 封禁 / 解封
curl -X POST 'http://127.0.0.1:8082/api/v1/links/{gid}/disable'
curl -X POST 'http://127.0.0.1:8082/api/v1/links/{gid}/enable'
```

> **改完是秒级生效**：admin 写完 Redis 会 `INCR sl:cache:ver` 并 `PUBLISH sl:cache-sync`，redirect 订阅后立即失效本地缓存。
> 万一消息丢了，版本号轮询会拿"版本增量"和"实收消息数"对账，对不上才全量失效（≤2s）。
> 细节见 [docs/shortlink-design.md](docs/shortlink-design.md) 的 5.3.3。

**缓存重建**（Redis 重启/被清空后恢复，否则全站 404）：

```bash
curl -X POST 'http://127.0.0.1:8082/api/v1/admin/cache/rebuild?batchSize=2000'
# {"code":0,"data":{"scanned":12345,"written":12345,"failed":0,"elapsedMs":3200}}
```

### 6. 网关（鉴权 + 限流）

网关 `:8080` 是管理面的唯一入口。**redirect 不走网关**——跳转流量直连，否则网关要扛全量 QPS。

```bash
# 1) 没 token：401
curl -i http://127.0.0.1:8080/api/v1/links

# 2) 登录换 token（这是唯一的免鉴权接口）
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

# 3) 带 token：200，且网关会把 X-User-Id 注入给下游
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/links?size=1
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/auth/me
# {"code":0,"data":{"userId":"1","username":"admin"},...}
```

| 机制 | 实现 | 说明 |
|---|---|---|
| 鉴权 | `JwtAuthGlobalFilter`（HS256 验签） | 失败 401；校验 issuer 与过期时间 |
| 身份透传 | 网关注入 `X-User-Id` / `X-Username` | 下游不再重复解析 JWT |
| 防伪造 | 网关**先剥离**客户端自带的身份头再注入 | 否则客户端塞个 `X-User-Id: 999` 就是越权 |
| 限流 | Redis + Lua 固定窗口，按 IP 600 次/分钟 | 返回 429 + `Retry-After`；Redis 故障时放行 |

执行顺序：**限流(-100) → 鉴权(-90) → 路由**。限流在前，因为未认证的洪水也该被挡住，而且一次 Redis 调用比 HMAC 计算便宜。

> 开发账号：`admin / admin123`（口令在配置里存的是 SHA-256，不是明文）。生产必须换成用户表 + BCrypt 并走配置中心。

### 7. 幂等、批量与配额

```bash
# 幂等：同一个 Idempotency-Key 提交两次，返回同一个短码（不会建出两条）
curl -X POST http://127.0.0.1:8082/api/v1/links \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 8f14e45f-ea6b-4c1a-9c3d-2b7e5f0a1c22' \
  -d '{"longUrl":"https://example.com/a","title":"幂等测试"}'

# 批量：单次最多 500 条，逐条返回失败原因（下标 + 长链 + 原因）
curl -X POST http://127.0.0.1:8082/api/v1/links/batch \
  -H 'Content-Type: application/json' \
  -d '{"items":[{"longUrl":"https://a.com"},{"longUrl":"javascript:alert(1)"}]}'
# {"data":{"total":2,"success":1,"failed":1,"failures":[{"index":1,"longUrl":"javascript:alert(1)","reason":"..."}]}}

# 配额：maxClick 配了上限的短链，超过后返回 403
curl -X POST http://127.0.0.1:8082/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/limited","maxClick":2}'
```

| 能力 | 实现要点 |
|---|---|
| 幂等 | Redis 占位 + 结果回存 24h；重复请求**返回首次结果**而不是 409；并发重复会轮询等待最多 3s |
| 批量 | 逐条复用单条创建逻辑（去重/风控/缓存/审计完全一致），单条失败不影响其他条 |
| 配额 | 只对配了 `maxClick` 的短链做 `INCR sl:click:max:{code}`，普通短链零额外开销 |
| 审计 | 每次写操作落 `t_operate_log`（before/after 快照 + 操作人 + traceId）；审计失败不影响业务 |

> 配额用 `INCR` 而不是"先读后写"：并发下两个请求可能都读到上限前一个值，结果多放过去一批。原子自增最多在边界上多放 1~2 次，可接受。
> Redis 故障时**放行**——不能因为计数器挂了把正常用户挡在门外。

### 8. 统计链路（V3）

```
跳转 302 ──► ClickTracker（有界队列，满即丢）
                 │  独立线程批量发送
                 ▼
              Kafka: sl-click
                 │  批量消费 + 手动 ack
                 ▼
        ClickProcessor ──► Redis: INCR pv / PFADD uv（实时看板，T+0）
                 │
                 └────────► ClickHouse: link_click_detail（明细）
                                 └─► mv_link_daily（AggregatingMergeTree）
```

| 环节 | 关键取舍 |
|---|---|
| 埋点 | **有界队列 + 独立线程**。`KafkaTemplate.send` 在元数据不可用时会阻塞（`max.block.ms` 默认 60s），一次 Kafka 抖动就能让跳转假死——所以绝不能在请求线程里发 |
| 队列满 | 丢弃并计数，不阻塞。统计丢一条 << 用户跳不过去 |
| 去重 | Redis `SETNX eventId`（1h）。Kafka 至少一次投递，不去重 PV 会系统性偏高 |
| 落库失败 | **撤销去重标记** + 不提交位点。否则重投的消息被判重跳过，数据静默丢失 |
| 批量写 | 500 条 / 1s。ClickHouse 最怕一条一条 insert（每次生成一个 part，会报 `Too many parts`） |
| 实时 UV | Redis HyperLogLog（12KB，误差 0.81%）。Set 存精确值的话日活百万就是几十 MB/天 |

**开关**：`STATS_ENABLED=true` 才启用埋点，默认关闭。没有 Kafka 时采集器自动降级为禁用（`publisher == null`），跳转链路零影响。

**当前状态**：代码与单测完成（UA 解析 10、处理器 6、列一致性 4、埋点队列 5），但**这台机器拉不到 Kafka / ClickHouse 镜像**，所以没有端到端证据。ClickHouse DDL 见 `sql/03-clickhouse.sql`。

### 9. 统计查询（stats-api，:8085）

```bash
# 最近 7 天（默认区间）
curl 'http://127.0.0.1:8085/api/v1/stats/links/abc1234'

# 指定区间（最长 90 天）
curl 'http://127.0.0.1:8085/api/v1/stats/links/abc1234?from=2026-10-01&to=2026-10-27'

# 今日实时（HLL 估算 UV）
curl 'http://127.0.0.1:8085/api/v1/stats/links/abc1234/realtime'
```

```json
{"code":0,"data":{"shortCode":"abc1234","from":"2026-10-26","to":"2026-10-27","days":2,
  "totalPv":107,"avgUv":21,"peakUv":40,
  "points":[
    {"date":"2026-10-26","pv":100,"uv":40,"uvExact":0,"source":"DB"},
    {"date":"2026-10-27","pv":7,"uv":3,"uvExact":0,"source":"REALTIME"}]}}
```

三个设计点：

| 点 | 做法 | 原因 |
|---|---|---|
| 今天的数据 | 用 Redis 实时值覆盖 | 日聚合是 T+1 的，今天的行还不存在 |
| 覆盖规则 | 取 `max` 而不是直接覆盖 | 已部分聚合时覆盖会让曲线"倒退" |
| **不提供 totalUv** | 只给 `totalPv` / `avgUv` / `peakUv` | **UV 是去重人数，跨天相加没有意义**（同一人三天访问，三天 UV 各 +1，相加得 3 个"人"）。区间真实 UV 要回明细做 `uniqExact` |

数据源分工：MySQL `shortlink_stats.t_stats_link_daily`（T+1 聚合）+ Redis（今天）+ ClickHouse（明细/TopN，未接入）。Redis 故障时降级为"只有 DB 数据"，不会整个接口 500。

## 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 骨架 | 19 个 Maven 模块、依赖边界、编译通过 | ✅ |
| SQL | `sql/01-schema.sql`、`02-init-data.sql`、`03-clickhouse.sql` | ✅ |
| V1 写侧 | admin 创建 / 列表 / 详情 / 修改 / 启停（取号 → FPE 编码 → 落库 → 写 Redis） | ✅ 编译通过 + 28 个风控单测 |
| V1 读侧 | redirect 完整链路（格式校验 → L1 → Redis → 状态判定 → 302/HTML） | ✅ 编译通过 + 18 个单测 |
| V2 一致性 | Pub/Sub 失效 + 版本号兜底 + 缓存重建 + AOF | ✅ 改链/封禁秒级生效 |
| V4 网关 | JWT 鉴权 + 身份透传 + IP 限流（Lua 固定窗口） | ✅ 401/200/429 全部验证 |
| V4 风控 | IP 黑名单（Redis Set + 本地快照）+ redirect 本地限流 + 访问日志 | ✅ 双端 403、单码 429 验证 |
| V4 写入 | 幂等（`Idempotency-Key`）+ 批量导入 + maxClick 配额 + 操作审计 | ✅ 重复提交同码、配额第 3 次 403 验证 |
| 端到端 | MySQL + Redis + admin + redirect + gateway 全在容器内 | ✅ **62 项断言全通过** |
| V3 统计 | 埋点 → Kafka → 消费 → ClickHouse 明细 / Redis 实时 | 🟡 代码 + 单测通过，**消费链路待镜像** |
| V3 查询 | stats-api：日趋势（DB 聚合 + 实时补今天）+ 实时接口 | ✅ 端到端验证（含网关路由） |
| V3 其余 | 维度 TopN、大盘报表 | ⬜ 依赖 ClickHouse / 聚合任务 |
| job | 过期清理 + 审计归档 + 分布式锁 + 手动触发 | ✅ 端到端验证（软删 + 缓存失效）|
| V3/V5 | 统计、分片 | ⬜ |

### 压测基线（单机 Docker Desktop，32 线程 / 10s / 2000 条短码）

| 配置 | QPS | P50 | P99 |
|---|---|---|---|
| L1 开 + 失效通道开 | **13,287** | 1.72ms | 12.97ms |
| L1 开 + 失效通道关 | 13,136 | 1.70ms | 15.80ms |
| L1 关 | 10,149 | 2.31ms | 16.52ms |

L1 带来约 **+31% QPS**；失效通道的开销在噪声范围内（&lt;1%）。
复现：`java scripts/LoadTest.java --codes 2000 --threads 32 --seconds 10`

> 这是开发机 + Docker Desktop 的数字，不代表生产容量。换成裸机或更多副本会明显更高。

详细实现顺序与验收标准见 [docs/module-plan.md](docs/module-plan.md)。

## 依赖红线

`shortlink-redirect` 必须保持“无 DB 依赖”。以下任意一项出现在它的依赖树里，说明架构已被破坏：

```bash
mvn -pl shortlink-redirect dependency:tree \
  | grep -E "mysql-connector|mybatis|spring-boot-starter-web:|spring-boot-starter-jdbc"
# 期望：无输出
```

## 版本说明

父 POM 中的 Spring Boot / Spring Cloud / Spring Cloud Alibaba 版本组合**必须按官方兼容矩阵核对**后再升级，三者存在严格对应关系，错配的典型症状是启动时报 `NoClassDefFoundError` 或自动装配静默失效。
