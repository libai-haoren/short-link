# 模块实现规划

> 配套文档：[短链平台详细设计](shortlink-design.md) · [README](../README.md)
> 版本：v1.0 ｜ 日期：2026-10-24 ｜ 对应代码状态：骨架完成（19 个 Maven 模块）

---

## 目录

- [0. 总览与构建顺序](#0-总览与构建顺序)
- [1. 分层原则](#1-分层原则)
- [2. 公共库模块](#2-公共库模块)
  - [2.1 common-core](#21-shortlink-common-core)
  - [2.2 common-web](#22-shortlink-common-web)
  - [2.3 common-webflux](#23-shortlink-common-webflux)
  - [2.4 common-cache](#24-shortlink-common-cache)
  - [2.5 common-mq](#25-shortlink-common-mq)
  - [2.6 component-codec](#26-shortlink-component-codec)
  - [2.7 component-idgen](#27-shortlink-component-idgen)
  - [2.8 component-bloom](#28-shortlink-component-bloom)
  - [2.9 component-idempotent](#29-shortlink-component-idempotent)
  - [2.10 component-ratelimit](#210-shortlink-component-ratelimit)
- [3. 服务模块](#3-服务模块)
  - [3.1 gateway](#31-shortlink-gateway)
  - [3.2 admin](#32-shortlink-admin)
  - [3.3 redirect](#33-shortlink-redirect)
  - [3.4 stats-consumer](#34-shortlink-stats-consumer)
  - [3.5 stats-api](#35-shortlink-stats-api)
  - [3.6 job](#36-shortlink-job)
- [4. 跨模块约定](#4-跨模块约定)
- [5. 里程碑与验收](#5-里程碑与验收)
- [6. 本次骨架已验证的事项](#6-本次骨架已验证的事项)
- [7. 风险与待决](#7-风险与待决)

---

## 0. 总览与构建顺序

### 0.1 依赖拓扑

```
                    ┌──────────────────┐
                    │   common-core    │  零依赖，一切的基础
                    └────────┬─────────┘
          ┌──────────────────┼──────────────────┐
          ▼                  ▼                  ▼
   ┌─────────────┐   ┌─────────────┐   ┌──────────────┐
   │ common-web  │   │common-webflux│   │common-cache  │
   │  (Servlet)  │   │ (Reactive)   │   │  (Redis+Caffeine)
   └──────┬──────┘   └──────┬───────┘   └──────┬───────┘
          │                 │                  │
          │                 │        ┌─────────┴────────┐
          │                 │        ▼                  ▼
          │                 │  ┌───────────┐   ┌────────────────┐
          │                 │  │ common-mq │   │component-idempotent│
          │                 │  └─────┬─────┘   └────────────────┘
          │                 │        │
          ▼                 ▼        ▼
   ┌──────────┐     ┌────────────┐  ┌──────────────┐
   │  admin   │     │  redirect  │  │stats-consumer│
   └──────────┘     └────────────┘  └──────────────┘
```

关键约束：**admin 与 redirect 之间没有任何 Maven 依赖**。它们只通过 Redis（数据面）和 Kafka（控制面）通信。这条不变量是整个架构可拆分的前提。

### 0.2 构建顺序（拓扑序，Maven 会自动排，这里说明"人该怎么读/怎么写"）

```
1. common-core          ← 所有模块的契约
2. component-codec      ← 纯算法，可独立验证
3. component-idgen      ← 依赖 core
4. common-web / webflux ← 横切
5. common-cache         ← key 契约 + 本地缓存
6. component-bloom / idempotent / ratelimit
7. common-mq            ← 事件模型
8. admin                ← 第一个可写数据的服务
9. redirect             ← 第一个可读数据的服务
10. stats-consumer / api
11. gateway / job
```

### 0.3 完成度矩阵

| 模块 | 代码状态 | 说明 |
|---|---|---|
| `common-core` | ✅ 可用 | 6 个类全部实现，零依赖已验证 |
| `common-web` | 🟡 基础可用 | 异常处理 + TraceId 已实现；Jackson 配置、统一响应待补 |
| `common-webflux` | 🟡 基础可用 | 同上；404 错误页方案待定（见 7.2） |
| `common-cache` | 🟡 基础可用 | key 契约与 Caffeine 工厂已实现；二级缓存抽象待写 |
| `common-mq` | 🟢 V3 就绪 | 事件模型 + Kafka 发布器（自动装配，永不抛异常）|
| `component-codec` | ✅ 完成 | 已通过 10 万号唯一性 + 20 万随机往返验证 |
| `component-idgen` | ✅ 核心完成 | 已通过 8 线程 16 万号并发验证；Spring 装配待补 |
| `component-bloom` | 🟡 核心完成 | Redis 实现已写；预热、扩容、单测待补 |
| `component-idempotent` | 🟡 骨架 | 幂等守卫可用；结果缓存与等待逻辑待补 |
| `component-ratelimit` | ✅ 核心完成 | 令牌桶已验证；AOP 接入待 V4 |
| `gateway` | 🟢 V4 可用 | JWT 鉴权 + 身份透传 + 防伪造剥离 + IP 限流（Lua）已实现并端到端验证 |
| `admin` | 🟢 V4 可用 | 创建 / 列表 / 详情 / 修改 / 启停 / 批量 / 幂等 / 审计已实现，**编译通过 + 28 个风控单测**；分组、域名 CRUD 待补 |
| `redirect` | 🟢 V4 可用 | 完整读链路 + 本地限流 + 黑名单 + 异步埋点（有界队列，不拖慢跳转）+ 32 个单测 |
| `stats-consumer` | 🟡 V3 代码完成（未跑真环境） | 消费/去重/UA 解析/批量写 CH/实时计数已实现，20 个单测；**缺 Kafka + ClickHouse 无法端到端** |
| `stats-api` | 🟢 V3 可用 | 日趋势（DB 聚合 + Redis 实时补今天）+ 实时接口，已端到端验证；TopN / 大盘待做（依赖 ClickHouse） |
| `job` | 🟢 可用 | 过期短链清理 + 审计归档 + Redis 分布式锁 + 手动触发，端到端验证；对账/预热待做 |

### 0.4 V1 阶段的依赖调整

按"只引入当前阶段真正需要的依赖"原则，V1 从各模块移除了以下依赖。它们都在 POM 里留了注释，接入阶段照注释加回即可：

| 依赖 | 移除范围 | 原因 | 何时加回 |
|---|---|---|---|
| `spring-cloud-starter-alibaba-nacos-discovery` | admin | V1 只有 admin + redirect 两个进程，直连即可；没有注册中心时它会导致启动失败 | V4 |
| `micrometer-registry-prometheus` | admin | V1 用 `/actuator/health` 冒烟即可，没有 Grafana 时只会多一行连接失败日志 | V4 |
| `spring-boot-starter-validation` | admin | V1 用 `UrlValidator` 手写校验（URL 合法性无法用注解表达），没用到 Bean Validation | V4（DTO 增多后） |
| `spring-boot-configuration-processor` | common-web / common-webflux | 只是 IDE 配置提示，且当前没有 `@ConfigurationProperties` | V2 |
| `lombok` 版本 1.18.34 → 1.18.32 | 父 POM | 本地仓库只有 1.18.32，为了让离线构建可验证；两者对 JDK 17 行为一致 | 联网后可升回 |

---

## 1. 分层原则

拆模块不是按"技术名词"切，而是按**依赖方向**和**变更频率**切。三条判据：

| 判据 | 应用 |
|---|---|
| **谁依赖它** | `common-web` 被 admin 依赖、不能被 redirect 依赖，所以必须和 `common-webflux` 分开 |
| **改它要不要发版** | `common-core` 的错误码加一个枚举值，所有服务都要重发；所以它必须稳定、禁止塞业务逻辑 |
| **能不能单独验证** | `component-codec` 零依赖，可以脱离 Spring 直接跑算法测试；这类模块要尽量"纯" |

反面案例（骨架里刻意避开的）：

- ❌ 把所有工具类塞一个 `common-utils` —— 半年后会变成什么都往里放的垃圾桶
- ❌ `common` 里放 MyBatis 实体 —— redirect 会被迫拉入 JDBC 驱动
- ❌ 组件里直接 `new` 配置对象 —— 组件只暴露构造参数，配置绑定放在服务层

---

## 2. 公共库模块

### 2.1 shortlink-common-core

**职责**：全项目唯一的契约层，零第三方依赖。

**包结构**

```
com.shortlink.common.core
├── result/      Result<T>, ErrorCode
├── exception/   BizException
├── trace/       TraceContext
├── util/        Ulid
└── constant/    HeaderConstants
```

**核心类**

| 类 | 状态 | 设计要点 |
|---|---|---|
| `Result<T>` | ✅ | 不可变（final 字段 + 私有构造），`ok/fail` 静态工厂；`withTraceId` 返回新实例 |
| `ErrorCode` | ✅ | 5 位码（前 3 位对齐 HTTP 状态）；**只追加不复用**，否则线上监控规则会错乱 |
| `BizException` | ✅ | 只表达"可预期失败"；系统异常直接抛，由全局处理器兜底 |
| `TraceContext` | ✅ | ThreadLocal；WebFlux 不走它，改走 exchange attribute |
| `Ulid` | ✅ | 26 字符 Crockford Base32；时间前缀让 MySQL 插入近似顺序 |
| `HeaderConstants` | ✅ | 三个跨服务头名集中定义，避免网关/下游写歪 |

**实现顺序 / 待办**

1. ✅ 六个类落地
2. ⬜ `PageResult<T>`：游标分页结构（`cursor` + `hasMore` + `list`），管理端列表用
3. ⬜ `Assert`：前置条件校验工具（`Assert.notNull`、`Assert.isTrue`），减少业务代码里的 if-throw
4. ⬜ `Retry`：轻量重试工具（仅用于缓存双写失败重试）

**明确不做的事**

- ❌ 不放 `JsonUtils`：那需要 Jackson，直接破坏零依赖前提。序列化归 `common-cache` / `common-mq`
- ❌ 不放日期工具：`java.time` 够用，引入 Hutool 只会让依赖树变脏

**验收标准**

```bash
mvn -pl shortlink-lib/shortlink-common-core dependency:tree
# 期望：只有 junit-jupiter (test)，无任何 compile 依赖
```

---

### 2.2 shortlink-common-web

**职责**：Servlet（MVC）栈的横切能力。admin / stats-api / job 使用。

**包结构**

```
com.shortlink.common.web
├── GlobalExceptionHandler      # @RestControllerAdvice
├── TraceIdFilter               # OncePerRequestFilter
└── CommonWebAutoConfiguration  # @AutoConfiguration
```

**核心类**

| 类 | 状态 | 设计要点 |
|---|---|---|
| `GlobalExceptionHandler` | ✅ | 业务异常→对应 HTTP 状态；参数校验错误提取首条字段错误；未知异常统一 500 且堆栈只进日志 |
| `TraceIdFilter` | ✅ | `finally` 清理 ThreadLocal + MDC，否则 Tomcat 线程复用会串号 |
| `CommonWebAutoConfiguration` | ✅ | Filter 用 `new` 而非注入，避免 Boot 二次注册导致 traceId 生成两次 |

**实现顺序 / 待办**

1. ✅ 异常处理器 + TraceId + 自动装配
2. ⬜ `JacksonConfig`：`Long` 序列化为 String（前端 JS 精度只有 53 位，雪花/ULID 会丢精度）、`LocalDateTime` 统一格式、`null` 字段策略
3. ⬜ `WebMvcConfig`：跨域（CORS）配置、静态资源
4. ⬜ `ApiLogAspect`：慢接口打点（>500ms 记 WARN），不打印出入参（有泄露风险）
5. ⬜ `@ControllerAdvice` 补充 `HttpRequestMethodNotSupportedException`、`NoHandlerFoundException`

**取舍说明**

不做全局 `ResponseBodyAdvice` 包装。原因：controller 已经显式返回 `Result`，再包一层会让 SpringDoc 生成的文档失真（显示成 `Result<Result<T>>`），而且重试/网关侧解析也会多一层。

**验收标准**

- 任意 controller 抛 `BizException`，响应体为 `{"code":40401,...,"traceId":"..."}` 且 HTTP 状态为 404
- 响应头带 `X-Trace-Id`，日志里同一请求的 traceId 一致

---

### 2.3 shortlink-common-webflux

**职责**：Reactive 栈的横切能力。只有 redirect 使用。

**包结构**

```
com.shortlink.common.webflux
├── TraceIdWebFilter
├── GlobalWebExceptionHandler
└── CommonWebfluxAutoConfiguration
```

**核心类**

| 类 | 状态 | 设计要点 |
|---|---|---|
| `TraceIdWebFilter` | ✅ | 写 exchange attribute；**必须是纯 CPU 操作**，一旦做 IO 就阻塞事件循环 |
| `GlobalWebExceptionHandler` | ✅ | WebFlux 下 `@RestControllerAdvice` 仍然有效，但只覆盖控制器内抛出的异常 |
| `CommonWebfluxAutoConfiguration` | ✅ | `@ConditionalOnWebApplication(REACTIVE)`，与 MVC 版本互斥 |

**实现顺序 / 待办**

1. ✅ 三个类落地
2. ⬜ **redirect 专用错误页**：浏览器访问短链时 404/410 应返回静态 HTML，而不是 JSON。方案是 redirect 自己实现一个 `RedirectErrorHandler`（`ErrorWebExceptionHandler`），common-webflux 的 JSON 处理器只服务内部 API
3. ⬜ 替换 `DefaultErrorWebExceptionHandler`，统一 404/415 等容器层错误

**关键差异（必须在代码评审里强调）**

| 维度 | MVC | WebFlux |
|---|---|---|
| traceId 传递 | ThreadLocal | exchange attribute |
| 异常捕获范围 | 全部（filter 之前的不算） | 仅控制器内 |
| 阻塞调用 | 允许 | **禁止**（EventLoop 线程只有 CPU 核数个） |

**验收标准**

- `/ping` 返回 `pong` 且响应头有 `X-Trace-Id`
- 并发 1000 请求下不出现 `BlockingOperationError`

---

### 2.4 shortlink-common-cache

**职责**：Redis key 的唯一出口 + 本地缓存工厂 + （V2）二级缓存抽象。

**包结构**

```
com.shortlink.common.cache
├── CacheKeys          # key 契约
├── LocalCaches        # Caffeine 工厂
└── TwoLevelCache      # ⬜ V2
```

**核心类**

| 类 | 状态 | 设计要点 |
|---|---|---|
| `CacheKeys` | ✅ | 与设计文档 4.5 一一对应；业务代码禁止拼字符串 |
| `LocalCaches` | ✅ | `Expiry` 实现 TTL 抖动；`expireAfterRead` 不续期（否则改短链要等重启才生效） |

**实现顺序 / 待办**

1. ✅ key 契约 + Caffeine 工厂
2. ⬜ `RedisConfig`：`StringRedisTemplate` 的 key 用 String、value 用 JSON（不要用 JDK 序列化，跨语言不可读、体积大 3 倍）
3. ⬜ `TwoLevelCache<K,V>`：
   - `get`：L1 → L2 → 回填 L1
   - `put`：**双写而不是双删**（redirect 连不上 DB，删了就只能 404）
   - 逻辑过期：value 内嵌 `expireAt`，过期时返回旧值 + 异步刷新
   - singleflight：`ConcurrentHashMap<String, CompletableFuture>` 合并并发回源
4. ⬜ 分层命中率指标（`shortlink_cache_hit_ratio{layer="l1|l2"}`）

**验收标准**

- 单元测试覆盖：命中 L1、命中 L2 回填 L1、都未命中、逻辑过期返回旧值
- 命中率能通过 Micrometer 上报

---

### 2.5 shortlink-common-mq

**职责**：Topic 契约 + 事件模型 + 发布抽象。

**包结构**

```
com.shortlink.common.mq
├── Topics
├── EventPublisher
└── event/  ClickEvent, CacheSyncEvent
```

**核心类**

| 类 | 状态 | 设计要点 |
|---|---|---|
| `Topics` | ✅ | 四个 topic 常量，与设计文档 4.6 对应 |
| `ClickEvent` | ✅ | 只存 ipHash 不存明文 IP；`eventTime` 由 redirect 生成（消费端生成会随积压漂移） |
| `CacheSyncEvent` | ✅ | 带 `version` 防乱序覆盖；带 `payload` 让 redirect 能直接 put，省一次 Redis 回查 |
| `EventPublisher` | ✅ | 接口化，便于单测与后续换 MQ |

**实现顺序 / 待办**

1. ✅ 契约层
2. ⬜ `KafkaEventPublisher`：异步发送、失败回调计数、`acks=1`（统计可丢）
3. ⬜ `BroadcastConsumer`：每实例唯一 groupId + `assign()` 全分区，模拟广播消费
4. ⬜ 幂等消费基类：`eventId` + Caffeine 本地去重（1 分钟窗口）+ Redis 兜底
5. ⬜ JSON 序列化器：显式指定，不依赖默认的 String 序列化

**验收标准**

- 广播消费：起 2 个实例，各收到 100% 消息
- 重复投递同一条消息，PV 只 +1

---

### 2.6 shortlink-component-codec

**职责**：短码编解码。**当前项目完成度最高的模块**。

**包结构**

```
com.shortlink.component.codec
├── Base62
├── FeistelPermutation
└── ShortCodeCodec
```

**核心类**

| 类 | 状态 | 说明 |
|---|---|---|
| `Base62` | ✅ | 62 进制，反查表加速；溢出检查 |
| `FeistelPermutation` | ✅ | 42 bit / 4 轮 / 21+21 分组；轮函数用 SplitMix64 finalizer |
| `ShortCodeCodec` | ✅ | 号段号 → 7 位短码，含 cycle-walking 与逆 cycle-walking |

**关键数字（可直接写进简历）**

| 项 | 值 |
|---|---|
| 短码空间 | `62^7 - 62^6 = 3,464,814,370,624`（约 3.46 万亿） |
| 号段起点 | `62^6 = 56,800,235,584`（保证恒为 7 位，不补零） |
| cycle-walking 期望迭代 | 约 1.27 次 |
| 实测性能 | 20 万次编解码 45 ms（约 225 ns/次） |

**实现顺序 / 待办**

1. ✅ 三个类 + JUnit 测试
2. ⬜ 密钥管理：从 Nacos 配置中心读取，支持轮换（换 key 等于换一套短码空间，必须配合灰度）
3. ⬜ JMH 基准：确认 `encode` 在 10 万 QPS 下的 CPU 占用
4. ⬜ 标注线程安全：`ShortCodeCodec` 无状态（`FeistelPermutation` 只读 `key`），可安全复用单例

**验收标准**

- `ShortCodeCodecTest` 全绿（已有 5 个用例）
- 10 万连续号无碰撞、长度恒为 7、往返一致

---

### 2.7 shortlink-component-idgen

**职责**：号段发号，为短码提供连续、唯一、无冲突的输入。

**包结构**

```
com.shortlink.component.idgen
├── SegmentAllocator        # 接口 + Segment record
├── DbSegmentAllocator      # MySQL 乐观锁实现
└── SegmentIdGenerator      # 双 Buffer
```

**核心类**

| 类 | 状态 | 设计要点 |
|---|---|---|
| `SegmentAllocator` | ✅ | 接口化后 generator 零外部依赖，单测可用内存实现 |
| `DbSegmentAllocator` | ✅ | 乐观锁 `WHERE version=?`；失败重读重试 3 次。不用 `FOR UPDATE`（锁持有久、易死锁） |
| `SegmentIdGenerator` | ✅ | 剩余 20% 异步预取；预取在锁外；失败不影响当前号段 |

**实现顺序 / 待办**

1. ✅ 三个类 + 并发验证
2. ⬜ Spring 自动装配：`@AutoConfiguration` + `@Bean`，从配置读 `bizTag/step`
3. ⬜ 指标：当前号段剩余量（Gauge）、取段次数（Counter）、预取失败次数（Counter）
4. ⬜ 多 bizTag 支持：`Map<String, SegmentIdGenerator>`，短码与分组 ID 可共用一套机制
5. ⬜ Redis 兜底：DB 不可用时用 Redis `INCRBY` 顶上（可选，V5）

**已知限制**

重启会丢弃当前号段剩余号（最多 `step` 个）。这是用"最多浪费 1000 个号"换"实现简单、无持久化游标"，对短码场景完全可接受——反正短码经过 FPE 后本来就不连续。

**验收标准**

- 8 线程 × 2 万并发取号无重复（已通过）
- DB 断开后仍能发完当前号段的剩余号

---

### 2.8 shortlink-component-bloom

**职责**：拦截不存在的短码，保护 Redis 与下游。

**包结构**

```
com.shortlink.component.bloom
├── BloomFilter          # 接口
└── RedisBloomFilter     # Redis Bitmap 实现
```

**核心类**

| 类 | 状态 | 设计要点 |
|---|---|---|
| `BloomFilter` | ✅ | 语义明确：false 一定不存在，true 可能存在 |
| `RedisBloomFilter` | 🟡 | 双重哈希（Kirsch-Mitzenmacher）；`addAll` 用 pipeline；参数 14 bit/元素、10 个哈希 |

**容量推导（照抄设计文档，面试常问）**

```
m = -n·ln(p) / (ln2)² ≈ 14.4n  bit
k = (m/n)·ln2 ≈ 10
n=1000 万, p=0.001  →  约 1.44 亿 bit ≈ 18MB
```

**实现顺序 / 待办**

1. 🟡 Redis 实现已写，**未跑过单测**
2. ⬜ 单测：用 Testcontainers 起 Redis，验证误判率 &lt; 0.2%
3. ⬜ 预热任务：从 DB 分批扫 `short_code` 灌入（放 `job`）
4. ⬜ 可扩展布隆：分层实现（每层容量翻倍，查询查所有层）
5. ⬜ 误判率监控：抽样统计"布隆说存在但 Redis 不存在"的比例

**必须记住的三个坑**

| 坑 | 后果 | 应对 |
|---|---|---|
| 不支持删除 | 封禁短链后布隆仍说"存在" | 不删位，让空值缓存 + status 字段兜底 |
| 容量固定 | 超容量后误判率飙升 | 分层扩容，或提前按 3 倍容量建 |
| 异常时不能返回 false | 会把所有真实短链挡掉 | 抛异常让调用方跳过该层，降级到 Redis |

**验收标准**

- 灌入 100 万短码，查询 100 万个不存在的短码，误判率 &lt; 0.2%
- Redis 宕机时，`mightContain` 抛异常而非返回 false

---

### 2.9 shortlink-component-idempotent

**职责**：写接口防重复提交。

**包结构**

```
com.shortlink.component.idempotent
├── Idempotent           # 注解
└── IdempotentGuard      # 显式调用
```

**核心类**

| 类 | 状态 | 设计要点 |
|---|---|---|
| `Idempotent` | ✅ | 注解形态预留 AOP 接入 |
| `IdempotentGuard` | 🟡 | SETNX 占位 → 执行 → 写结果；失败释放占位 |

**实现顺序 / 待办**

1. 🟡 SETNX 占位与释放已实现
2. ⬜ **结果缓存**：首次执行完把 `Result` 序列化写回，重复请求直接返回（当前会抛异常）
3. ⬜ **等待而非报错**：并发第二个请求应短暂轮询（最多 3s）等待首个请求结果，而不是立即失败
4. ⬜ AOP 接入：解析 SpEL 或 `Idempotency-Key` 头

**当前缺陷（明确记录）**

并发两个相同 key 的请求，第二个会直接收到"重复提交"异常。体验上是报错而不是幂等返回。V4 修。

**验收标准**

- 并发 10 次相同 key 的创建请求，DB 只有 1 条记录
- 业务抛异常后，同 key 可立即重试成功

---

### 2.10 shortlink-component-ratelimit

**职责**：本地限流，保护单机容量。

**包结构**

```
com.shortlink.component.ratelimit
├── TokenBucket       # 纯算法
├── LocalRateLimiter  # Caffeine 管理多桶
└── RateLimit         # 注解
```

**核心类**

| 类 | 状态 | 设计要点 |
|---|---|---|
| `TokenBucket` | ✅ | 惰性补桶（无后台线程）；`synchronized` 临界区只有算术运算 |
| `LocalRateLimiter` | 🟡 | Caffeine 管理，避免桶无限增长；构造参数偏多，后续用 Builder |
| `RateLimit` | ✅ | 注解形态，AOP 待 V4 |

**实现顺序 / 待办**

1. ✅ 令牌桶 + 多桶管理
2. ⬜ 单测：QPS 误差 &lt; 5%、并发下不超发
3. ⬜ 动态阈值：从 Nacos 热更新，压测时可以现场调参
4. ⬜ 指标：拒绝数 `shortlink_ratelimit_reject_total{scope}`
5. ⬜ 自适应限流：按 RT 动态调整（Netflix concurrency-limits 思路），V5 可选

**为什么是本地限流而不是 Redis 限流（面试必答）**

限流如果每个请求都要一次 Redis 往返，等于给主链路加 1ms，且 Redis 抖动时限流先崩。本地限流零网络开销、天然高可用，代价是集群维度不精确（N 实例 = N 倍阈值）。所以分工是：**网关做集群精确限流（防刷），redirect 做本地容量保护（防打满）**。

**验收标准**

- 令牌桶容量 100、补充 100/s 时，1 秒内放行数在 100±5
- 100 线程并发取令牌，总数不超过容量

---

## 3. 服务模块

### 3.1 shortlink-gateway

**职责**：管理面的统一入口（admin / stats），**不承载跳转流量**。

**当前内容（V4 已实现）**：JWT 鉴权、身份透传与防伪造、IP 限流，路由改为可配置的静态 URI（接入注册中心后换成 `lb://` 即可）。

```
com.shortlink.gateway
├── config/   GatewayAuthProperties, RateLimitProperties, GatewayConfig
├── filter/   JwtAuthGlobalFilter(-90), RateLimitGlobalFilter(-100)
└── web/      GatewayResponses（401/429 的统一 JSON 出口）
```

**实现顺序**

| 步骤 | 内容 | 阶段 | 状态 |
|---|---|---|---|
| 1 | 路由补全：stats-api 单独一条 `/api/v1/stats/**` | V4 | ⬜ |
| 2 | TraceId：由 `common-webflux` 的 `TraceIdWebFilter` 提供 | V4 | ✅ |
| 3 | `JwtAuthGlobalFilter`：校验 token，写入 `X-User-Id` | V4 | ✅ |
| 4 | `RateLimitGlobalFilter`：Redis + Lua 固定窗口（600 次/分钟） | V4 | ✅ |
| 5 | `BlacklistFilter`：IP 黑名单（Redis Set），命中直接 403 | V4 | ✅ |
| 6 | 全局异常：过滤器内自行写响应（`GatewayResponses`） | V4 | ✅ |
| 7 | 访问日志：只记 method/path/status/耗时，不记 body | V4 | ✅ |

**两个必须注意的点（都已踩过）**

- **必须剥离客户端自带的 `X-User-Id`**。不过滤的话，客户端塞一个 `X-User-Id: 999` 就能冒充别人——这是最典型的越权漏洞，且肉眼看不出来。
- **白名单不能写 `/api/v1/auth/**`**。那会把 `/auth/me` 也放行，而它恰恰要靠网关注入身份头，结果永远 401。只放行 `/api/v1/auth/login`。
- 过滤器里禁止阻塞调用；限流用 `ReactiveStringRedisTemplate`。

**验收标准（已全部通过）**

- 不带 token 访问 `/api/v1/links` → 401
- 伪造 token → 401
- 登录后带 token → 200，且 `/auth/me` 能拿到 `userId=1`
- 客户端伪造 `X-User-Id` → 被剥离，仍返回真实身份
- 超过阈值 → 429


---

### 3.2 shortlink-admin

**职责**：写侧。短链 CRUD、分组、域名、批量导入、发号、幂等、风控、缓存双写、事件发布。

**当前内容（V1 已实现）**：启动类 + 数据源/Redis 配置 + MyBatis-Plus 配置；短链创建、列表、详情、修改、启停五条链路，外加 28 个风控单测。

```
com.shortlink.admin
├── controller/    LinkController
├── service/       LinkService, LinkPersistenceService
│   └── impl/      LinkServiceImpl
├── domain/
│   ├── entity/    ShortLinkDO, DomainDO
│   └── dto/       CreateLinkRequest, UpdateLinkRequest, LinkQuery, LinkView
├── mapper/        ShortLinkMapper, DomainMapper
├── config/        MybatisPlusConfig, ShortLinkCoreConfig
└── support/       UrlValidator, LinkCacheWriter, ShortUrlAssembler
```

**实现顺序（严格按此顺序，每步可独立验证）**

| 步骤 | 内容 | 产出 | 阶段 | 状态 |
|---|---|---|---|---|
| 1 | SQL 脚本落地（`sql/01-schema.sql`、`02-init-data.sql`） | 表结构 + 号段起点 | V1 | ✅ |
| 2 | Entity + Mapper + `GET /links/{gid}` 详情 | 第一个可读接口 | V1 | ✅ |
| 3 | `POST /links` 创建链路：校验 → 去重 → 发号 → 编码 → 落库 | 第一个可写接口 | V1 | ✅ |
| 4 | 创建后写 Redis（Hash）+ 更新布隆 | redirect 能读到 | V1 | ✅ |
| 5 | 列表（游标分页）、修改、启停 | 完整 CRUD（删除仍缺） | V1 | ✅ |
| 6 | 分组、域名 CRUD | 管理能力 | V2 | ⬜ |
| 7 | 风控增强（域名黑名单、DNS 解析后真实 IP 校验） | 安全 | V4 | ⬜ 基础版已含协议白名单 / SSRF 字面量拦截 |
| 8 | 发 MQ `CacheSyncEvent`（含 version） | 缓存一致性 | V2 | ⬜ |
| 9 | 批量导入（JSON 数组 → 逐条复用单条创建 → 返回失败明细） | 批量能力 | V4 | ✅ |
| 10 | 操作日志（`t_operate_log`，before/after 快照） | 审计 | V4 | ✅ |

**CRUD 实现中的四个决策**

| 决策 | 原因 |
|---|---|
| 列表用游标（`id < cursor`）而非 offset | `LIMIT 100000,20` 要扫 10 万行；短链列表持续新增，页码分页还会重复/漏数据 |
| `hasMore` 靠多取一条判断，不查 `COUNT(*)` | 少一次全表聚合；短链场景不需要精确总数 |
| 修改走 `LambdaUpdateWrapper` 显式 `set` | MP 默认更新策略跳过 null 字段，"把过期时间改成永久"就写不进去 |
| 删接口暂不做，只做启停 | 软删除后 `short_code` 仍占唯一索引，短码不会回收；启停语义更清晰且可逆 |


**V1 实现时做的三个取舍**

| 取舍 | 原因 |
|---|---|
| 去重放在业务层（group_id + long_url_hash），不建唯一索引 | 同一长链在不同分组可以共存，且产品上"允许重复"是可配置策略；加唯一索引会限制住这个自由度 |
| 缓存写入放在 DB 事务之外（先落库后写缓存） | 顺序反了会产生"能跳转但库里查不到"的脏数据，且无法通过重建修复 |
| `Idempotency-Key` 头暂不接，先用业务去重 | `IdempotentGuard` 当前对并发重复请求直接抛异常，体验是报错而非幂等返回，V4 修好后再接 |


**创建链路的伪代码（最重要的一段，先写它）**

```java
@Transactional(rollbackFor = Exception.class)
public CreateLinkResponse create(CreateLinkRequest req, String idempotencyKey) {
    return idempotentGuard.execute(idempotencyKey, Duration.ofHours(24), () -> {
        urlValidator.validate(req.longUrl());              // 协议白名单 + SSRF
        String hash = UrlHasher.md5(req.longUrl());

        // 同分组同长链默认复用，避免重复创建
        ShortLinkDO existing = linkMapper.selectByGroupAndHash(req.groupId(), hash);
        if (existing != null && !req.allowDuplicate()) {
            return toResponse(existing);
        }

        long segmentId = idGen.nextId();
        String code = codec.encode(segmentId);

        ShortLinkDO entity = buildEntity(req, segmentId, code, hash);
        linkMapper.insert(entity);                          // uk_short_code 兜底

        cacheWriter.put(entity);                            // Redis Hash
        bloomFilter.add(code);                              // 布隆
        eventPublisher.publish(Topics.CACHE_SYNC, code,   // 广播失效本地缓存
                new CacheSyncEvent(code, UPSERT, version, snapshot));

        return toResponse(entity);
    });
}
```

**关键约束**

- 事务只包 DB 操作，**不要把 Redis/MQ 放进事务**（回滚不会撤销已发的 MQ）
- 缓存写失败要重试并上报，不能静默吞掉（否则 redirect 永远读不到新链）
- `long_url_hash` 用 MD5 而非 SHA256：这里只是去重，不是安全用途

**验收标准**

- `curl` 创建后，`GET /{code}` 能 302 到目标（V1 端到端闭环）
- 相同 `Idempotency-Key` 重复提交只创建一条
- 长链为 `http://127.0.0.1/x` 时被 SSRF 拦截返回 400

---

### 3.3 shortlink-redirect

**职责**：读侧核心。短码 → 长链 → 302。**全项目性能天花板所在**。

**当前内容（V1 已实现）**：完整读链路 —— 格式校验 → L1 Caffeine（可开关）→ Redis HMGET → 状态判定 → 302 / HTML 错误页，含指标与单元测试。

```
com.shortlink.redirect
├── web/       RedirectController, ErrorPage
├── service/   LinkResolver, ResolveResult
├── cache/     LinkCache, RedisLinkCache, TwoLevelLinkCache, LinkSnapshot
├── metrics/   RedirectMetrics
└── config/    RedirectProperties, RedirectConfig
```

**实现顺序**

| 阶段 | 内容 | 预期效果 | 里程碑 | 状态 |
|---|---|---|---|---|
| 1 | 格式校验 → Redis → 302，HTML 错误页，指标 | 功能闭环，P99 ≈ 3ms | V1 | ✅ |
| 2 | L1 Caffeine（配置开关）+ 命中率指标 | 命中率 > 95%，P99 ≈ 1ms | V1 | ✅ |
| 3 | 异步埋点（本地队列 + 批量投递 Kafka） | 统计不拖慢跳转 | V3 | ✅ |
| 4 | 本地限流（实例总入口 + 单短码）/ IP 黑名单 | 风控 | V4 | ✅ |
| 5 | maxClick 次数限制（Redis INCR） | 限流型短链 | V4 | ⬜ |

**跳转主流程（已实现）**

```
GET /{code}
 ├─ 1. isValidFormat(code)              → 否：404，零 RTT
 ├─ 2. L1 Caffeine                       → 命中：跳 4
 ├─ 3. Redis HMGET [longUrl,status,expireTime,maxClick,domainId]
 │      ├─ 错误        → 503（cache_error 打点）
 │      ├─ 全 null     → 404
 │      └─ 命中        → 回填 L1 → 跳 4
 ├─ 4. 状态判定（纯函数 evaluate）
 │      ├─ status != 1        → 403
 │      ├─ now >= expireTime  → 410
 │      ├─ longUrl 非 http(s) → 503 + corrupt 打点
 │      └─ 通过               → 302
 └─ 5. 302 + Location + no-store + Content-Length: 0
```

**与原设计的两处修正（重要）**

| 原设计 | 实际实现 | 原因 |
|---|---|---|
| 读链路前置布隆过滤器 | **移除** | 本地布隆要求"新短码在被访问前已加入过滤器"，而 admin 只能通过 MQ 同步。MQ 滞后窗口内会出现「Redis 已有、布隆没有」→ 真实短链被判 404。假阴性的代价远大于省一次 RTT；Redis 版布隆每次判定同样是一次 RTT，等于没省 |
| Redis 空值缓存 `sl:link:null:` | **移除** | 空值缓存是为保护 DB；redirect 没有 DB。且空值 key 与主 key 都是同一次 RTT，省不了请求。防扫描靠格式校验 + V4 的限流 |

> 这两个组件从 redirect 移除后，`component-bloom` / `component-ratelimit` 也从 redirect 的 POM 中删掉了，避免依赖树里躺着用不到的模块。

**性能关键点（按收益排序）**

1. **L1 命中率**：95% 意味着 95% 的请求不碰网络。`maximumSize` 按堆 1/8 估算（10 万条约 80MB）
2. **零序列化**：响应不写 body，`Content-Length: 0`
3. **Redis 超时 50ms**：超时即降级 503，绝不挂住 EventLoop
4. **不打全量日志**：失败分支才打日志，且 Redis 故障用 DEBUG 级别（否则会刷爆）
5. **指标预注册**：计数器在构造时建好，请求路径上不做按名查找

**必须处理的边界（已覆盖）**

| 场景 | 行为 |
|---|---|
| 短码格式非法 | 404（不查 Redis，省一次网络） |
| 短链不存在 | 404 |
| 已过期 | 410（闭区间：正好等于过期时刻即失效） |
| 已停用 / 状态未知 | 403（fail-closed） |
| Redis 超时/连接失败 | 503（**不是 404**） |
| 缓存字段缺失/非法 | 503 + `corrupt` 打点，绝不猜默认值 |
| `maxClick > 0` | 只解析不执行（V4 补） |

**验收标准**

- 单元测试全绿（`LinkSnapshotTest` / `LinkResolverTest` / `ErrorPageTest`，共 18 个用例）
- 单机 8C16G，10 万短码数据量，wrk2 打出 **P99 &lt; 5ms，QPS &gt; 5w**
- 停掉 Redis 后，L1 命中的请求仍能正常跳转
- 停掉 MySQL（本就不连），跳转无任何影响


---

### 3.4 shortlink-stats-consumer

**职责**：Kafka 消费 → 解析 → 写 ClickHouse → 更新实时指标。

**当前内容**：启动类 + Kafka 批量消费配置（`max-poll-records=500`、手动 ack、并发 4）。

**建议包结构**

```
com.shortlink.stats.consumer
├── listener/     ClickEventListener
├── service/      ClickAggregator, UaParser, IpRegionResolver
├── repository/   ClickHouseRepository
└── config/       KafkaConfig, ClickHouseConfig
```

**实现顺序**

| 步骤 | 内容 | 阶段 |
|---|---|---|
| 1 | `@KafkaListener` 批量消费，反序列化 `ClickEvent` | V3 ✅ |
| 2 | 幂等：`eventId` Redis SETNX（1 小时），落库失败会撤销标记 | V3 ✅ |
| 3 | UA 解析（device/os/browser）✅；IP 归属地（ip2region）⬜ | V3 |
| 4 | 批量写 ClickHouse（500 条 / 1s 或满 500 即写） | V3 ✅ |
| 5 | 更新 Redis 实时指标：`INCR pv` + `PFADD uv`（HLL） | V3 ✅ |
| 6 | 失败重试 + 死信（`sl-click-dlt`） | V3 ⬜ |
| 7 | lag 监控告警 | V4 |

**三个必须做对的点**

| 点 | 做法 | 做错的后果 |
|---|---|---|
| 幂等 | `eventId` 去重 | PV 虚高，报表不可信 |
| 批量 | 攒批写 CH | 单条 insert 会把 CH 打爆（CH 最怕小批量高频写） |
| 手动 ack | 处理完再提交 offset | 自动提交会丢数据 |

**验收标准**

- 投递 10 万条消息，Redis PV 与 CH 明细行数一致（误差 &lt; 0.1%）
- 重复投递 1000 条，PV 不变
- 消费 1 万条/秒时 lag 不持续增长

---

### 3.5 shortlink-stats-api

**职责**：统计查询。

**当前内容**：启动类 + 统计库数据源配置。

**实现顺序**

| 步骤 | 内容 | 阶段 |
|---|---|---|
| 1 | `GET /stats/links/{code}` 日趋势（`t_stats_link_daily` + Redis 实时覆盖今天） | V3 ✅ |
| 2 | `GET /stats/links/{code}/top` 维度 TopN（读 ClickHouse） | V3 ⬜ 需 ClickHouse |
| 3 | `GET /stats/overview` 大盘（`t_stats_overview_daily`） | V3 ⬜ |
| 4 | `GET /stats/links/{code}/realtime` 实时 PV/UV（Redis HLL） | V3 ✅ |
| 5 | 查询缓存（Redis，30s）+ 慢查询打点 | V4 |

**注意**

- 报表查询容易慢，必须加分页/时间范围限制（单次最多查 90 天）
- `uv` 是 HLL 估算值，返回时要带 `"estimated": true`，别让用户以为精确
- 实时与历史口径不同（Redis HLL vs CH uniqExact），接口要明确区分

**验收标准**

- 1000 万行明细按天聚合 &lt; 500ms
- 实时接口 P99 &lt; 50ms

---

### 3.6 shortlink-job

**职责**：过期清理、缓存预热、统计对账、批量任务补偿。

**当前内容**：启动类 + 数据源配置。

**实现顺序**

| 步骤 | 任务 | 频率 | 阶段 |
|---|---|---|---|
| 1 | 过期短链清理（`expire_time` 早于保留期 → 软删 + 失效缓存） | 每天 | V2 ✅ |
| 2 | 布隆预热（全量 `short_code` 灌布隆） | 启动/每周 | V2 ⬜ 读链路不用布隆，暂缓 |
| 3 | 缓存预热（Top N 热门短链灌 Redis） | 每天 | V2 ⬜ |
| 4 | 统计对账（Redis PV vs CH 明细，差异 &gt; 1% 告警） | 每天 | V5 |
| 5 | 批量导入补偿（卡在"处理中"的任务重跑） | 每 10 分钟 | V4 ⬜ 批量是同步的，无任务表 |

**注意**

- 每个任务都要能"多实例只跑一个"：用 Redis 分布式锁或 XXL-JOB 的路由策略
- 清理任务必须**分批 + 限流**，不要一条 `DELETE ... WHERE` 扫全表
- 预热任务要能断点续跑

**验收标准**

- 停掉任务重跑，不产生重复数据
- 清理 100 万条过期数据时，DB CPU 不超过 30%

---

## 4. 跨模块约定

### 4.1 异常规范

| 类型 | 处理方式 |
|---|---|
| 可预期失败（参数错、不存在、限流） | `throw BizException.of(ErrorCode.XXX)` |
| 编程错误（空指针、越界） | 不捕获，交给全局处理器 → 500 + 告警 |
| 第三方调用失败 | 包装成 `BizException(SERVICE_UNAVAILABLE)` 并记录原始异常 |
| 禁止 | `catch (Exception e) { }` 空吞；`e.printStackTrace()` |

### 4.2 日志规范

| 规则 | 说明 |
|---|---|
| 占位符 | 用 `log.info("code={}", code)`，不用字符串拼接 |
| traceId | 通过 MDC 自动带入 pattern，不要手动拼 |
| redirect | 采样 1%，只记 code + status + 耗时 |
| 异常 | `log.error("...", ex)` 传异常对象，不要只传 `ex.getMessage()` |
| 禁止 | 在循环里打日志、打印完整长链（可能是敏感信息） |

### 4.3 测试策略

| 层次 | 范围 | 工具 |
|---|---|---|
| 单测 | 纯算法（codec / idgen / ratelimit / SSRF 校验） | JUnit 5，零 Spring 上下文 |
| 集成 | Mapper、Redis 操作、Kafka 消费 | Testcontainers |
| 端到端 | 创建 → 跳转 → 统计 | 脚本 + curl |
| 压测 | redirect 单机 QPS / P99 | wrk2 |

**原则**：纯算法必须有单测且不依赖 Spring（启动上下文 3 秒 vs 30 毫秒的差别）；涉及中间件的用 Testcontainers，不用 H2（SQL 方言差异会让你在生产翻车）。

### 4.4 依赖红线（放进 CI）

```bash
# redirect 不允许出现 DB / Servlet 相关依赖
mvn -q -pl shortlink-redirect dependency:tree -Dincludes=mysql:*,com.baomidou:*,org.springframework.boot:spring-boot-starter-web
# 期望：无输出
```

### 4.5 命名约定

| 对象 | 约定 | 示例 |
|---|---|---|
| 实体 | `XxxDO` | `ShortLinkDO` |
| 传输对象 | `XxxRequest` / `XxxVO` | `CreateLinkRequest` |
| 表 | `t_` 前缀 + 下划线 | `t_short_link` |
| Redis key | `sl:` 前缀，集中在 `CacheKeys` | `sl:link:aB3xK9z` |
| Topic | `sl-` 前缀 + 短横线 | `sl-click` |
| 包 | 按技术分层（controller/service/mapper），不按业务切 | — |

---

## 5. 里程碑与验收

| 阶段 | 涉及模块 | 完成标志 | 预计 |
|---|---|---|---|
| **V1 打通** | common-core/web/cache、codec、idgen、admin、redirect | 创建短链后 `curl -i /{code}` 返回 302；数据落 MySQL + Redis | 3~4 天 |
| **V2 一致性与可恢复** | common-cache(频道契约)、admin(通知+重建)、redirect(订阅+版本兜底)、compose(AOF) | 改链/封禁**秒级生效**；Redis 全丢后可一键重建；失效通道开销 &lt; 5% | 已完成 |
| **V3 有数据** | common-mq、redirect(埋点)、stats-consumer、stats-api、ClickHouse | 点击 3 秒内可查；PV/UV 与明细对得上 | 4 天 | 🟡 埋点+消费+查询已实现（117 单测 + 统计查询端到端）；**Kafka/ClickHouse 镜像不可达，消费链路未跑过真环境** |
| **V4 稳** | gateway、idempotent、ratelimit、admin(风控/批量)、可观测性 | 鉴权、限流、SSRF 拦截生效；Grafana 面板可用 | 4 天 | ✅ 鉴权/限流/黑名单/幂等/批量/配额/审计 全部端到端验证，47 项断言 |
| **V5 深** | Canal 同步、分片方案、混沌演练、压测报告 | MySQL 宕机跳转无感；kill Redis 后降级正确 | 5 天 |

**每个阶段结束必须产出的东西**

| 阶段 | 产出 |
|---|---|
| V1 | 一段可复现的 curl 脚本 |
| V2 | 压测报告：优化前后 QPS / P99 对比 + 火焰图 |
| V3 | 统计接口的响应示例 + 数据对账结论 |
| V4 | Grafana 截图 + 限流/风控的验证记录 |
| V5 | 故障演练记录 + 分片前后的性能对比 |

---

## 6. 已验证的事项

### 6.1 骨架阶段

| 验证项 | 方法 | 结果 |
|---|---|---|
| POM 结构完整性 | 脚本解析全部 POM，校验 XML、模块目录、内部依赖 | 全部通过 |
| Maven Reactor | `mvn -o validate`（用本地仓库存在的版本组合临时替换） | **19 个模块全部 SUCCESS** |
| 短码算法 | javac 编译 + 10 万连续号 | 无碰撞、长度恒 7、往返一致 |
| cycle-walking | 20 万全域随机抽样 | 逆变换全部还原成功 |
| 短码打散效果 | 连续号段输出观察 | `XAApjcu / IYtGs0y / bbraAZ3 / RnUjx8V ...` 无规律 |
| 编解码性能 | 20 万次 encode+decode | 45 ms（约 225 ns/次） |
| ULID | 20 万次生成 | 无重复、26 位、时间戳可反解 |
| 号段发号器 | 8 线程 × 2 万并发 | 16 万号无重复，56 ms，DB 取段 321 次 |
| 令牌桶 | 容量/耗尽/补充 | 容量内全放行，耗尽拒绝，120ms 补回 12 个 |

### 6.2 V1（SQL + admin 创建链路）

| 验证项 | 方法 | 结果 |
|---|---|---|
| admin 及其依赖库编译 | `mvn -o -pl shortlink-admin -am compile` | **11 个模块 BUILD SUCCESS**，admin 16 个源文件编译通过 |
| 编译覆盖的模块 | 同上 | common-core / common-web / common-cache / common-mq / component-idgen / component-codec / component-bloom / component-idempotent / admin |
| 实体与表结构对应 | 人工核对 17 个字段与 DDL | 一致（含 `short_code` 的 `utf8mb4_bin`） |

**仍未验证**：

- **未运行过**。环境无 Docker、无本地 MySQL/Redis，`POST /links` 的真实链路（取号→编码→落库→写缓存）只做了编译验证，没有端到端跑通。
- `redirect`、`gateway`、`stats-*`、`job` 仍未编译（依赖 Spring Cloud 2023.0.x，仓库缺失）。
- 编译使用 Boot 3.2.9 代替 3.3.5（本地仓库无 3.3.5）。两者 Spring Framework 同为 6.1，用到的 API 无差异。

> 联网后第一步：`mvn clean package -DskipTests`；第二步：起 MySQL + Redis，执行 `sql/01-schema.sql`、`sql/02-init-data.sql`，然后按 README 的 curl 验证创建链路。

---

## 7. 风险与待决

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| 1 | redirect / gateway / stats 未编译 | 首次构建可能报错 | admin 链路已编译通过；其余模块联网后跑 `mvn clean package` 逐个修 |
| 2 | 版本矩阵未在线核对 | Boot/Cloud/Alibaba 三者错配会启动失败 | 升级前查官方兼容表；本项目基线 Boot 3.3.5 + Cloud 2023.0.3 + SCA 2023.0.3.2 |
| 3 | redirect 错误页 vs JSON | 浏览器看到 JSON 很不专业 | redirect 单独实现 HTML 错误页（见 2.3 待办 2） |
| 4 | 布隆不可删除、不可扩容 | 容量超限后误判率飙升 | 设计为"只增不删"；扩容走分层布隆（V2） |
| 5 | 本地缓存多实例不一致 | 改短链后最长 1s 内可能读到旧值 | MQ 广播 + 全局版本号轮询双保险，明确接受秒级延迟 |
| 6 | 单机跑 6 个服务 | 16G 内存吃紧 | 本地只起 admin + redirect + 中间件，其余按需启动 |
| 7 | 幂等组件第二个请求直接报错 | 体验差 | V4 改为"短暂等待首个请求结果" |
| 8 | 统计丢数据 | 报表偏差 | 队列满丢弃 + 打点告警 + 每日对账 |

### 需要你确认的两个决策

1. **MQ 选型**：当前按 Kafka 设计（广播消费需每实例独立 groupId 绕行）。若更看重"缓存广播"的简洁性，换 RocketMQ 可省掉这个绕行，代价是多运维一个 NameServer。
2. **JDK 版本**：骨架按 17 编译（保证任何环境都能构建）。若要写"JDK 21 + 虚拟线程"到简历，改父 POM 的 `java.version` 为 21 即可，代码无需改动。
