# ShortLink · 高并发短链平台

读多写少的微服务实战项目：短码生成、302 跳转、异步统计、多级缓存一致性。

- 设计文档：[docs/shortlink-design.md](docs/shortlink-design.md)
- 模块实现规划：[docs/module-plan.md](docs/module-plan.md)

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

脚本自动完成：构建 → 起容器 → 等就绪 → 建链 → 断言 302/Location → 404/403/410 → HTML 错误页 → Prometheus 指标，最后 `down -v` 清理。

常用开关：`-SkipBuild`（复用已有 jar）、`-KeepRunning`（跑完不销毁，方便排查）。

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

### 4. 验证跳转（V1 已实现）

```bash
CODE=aB3xK9z    # 换成上一步返回的 shortCode

# 正常跳转：302 + Location + no-store + Content-Length: 0
curl -i "http://127.0.0.1:8081/$CODE"

# 不存在的短码：404 + HTML 错误页
curl -i http://127.0.0.1:8081/aaaaaaa

# 格式非法：404，且不会访问 Redis
curl -i http://127.0.0.1:8081/abc

# 停用后：403（改了 Redis 要等 L1 TTL 过期或重启才生效）
redis-cli HSET "sl:link:$CODE" status 0
curl -i "http://127.0.0.1:8081/$CODE"

# Redis 故障：503（不是 404 —— "查不到"和"查不了"必须区分）
# 注意：L1 命中时仍会正常跳转，属预期行为
```

## 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 骨架 | 19 个 Maven 模块、依赖边界、编译通过 | ✅ |
| SQL | `sql/01-schema.sql`、`02-init-data.sql`、`03-clickhouse.sql` | ✅ |
| V1 写侧 | admin 创建短链 + 详情（取号 → FPE 编码 → 落库 → 写 Redis → 加布隆） | ✅ 编译通过，未运行 |
| V1 读侧 | redirect 完整链路（格式校验 → L1 → Redis → 状态判定 → 302/HTML） | ✅ 编译通过 + 18 个单测，未运行 |
| V2~V5 | 统计、网关、分片、一致性 | ⬜ |

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
