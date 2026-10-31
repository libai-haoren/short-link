# 变更记录

| 项目 | 内容 |
|---|---|
| 日期 | 2026-10-31 |
| 范围 | 缓存一致性链路（redirect L1 / Redis / 版本号对账）、job 批量失效、文档 |
| 状态 | 工作区改动，**未提交**；`mvn -o -B test` 全量 20 个模块通过 |
| 背景 | 按"面试拷问"的方式逐条审查一致性设计，把站不住的表述改成能对上代码的表述，并补掉三个真实缺陷 |

---

## 一句话总结

这轮没有加功能，做的是六件事：**把"缓存挂了会怎样"从口头降级变成有代码的闸门**（A）、**修正两个会自我掩盖的指标与机制**（B 让兜底指标失真、C 让兜底每次写都触发）、**把 job 的清理从逐条往返改成按批 pipeline**（D）、**给缓存写入加版本号 CAS，关掉并发覆盖与回填竞态**（E）、**把"redirect 不许有 Servlet/DB 依赖"从文档口号变成 CI 会失败的断言**（F）、**把文档里对不上实现的表述全部对齐**（G）。

---

## A. 缓存就绪闸门：区分 404 与 503

**问题**：Redis 被 FLUSH 或主从空切后所有 key 都不在，读链路一律返回 404。这等于用确定性的口吻告诉用户"这条链接不存在"，而且**不触发任何错误告警**——是最难发现的一类事故。

**改动**

| 文件 | 内容 |
|---|---|
| `CacheKeys` | 新增 `sl:cache:ready` |
| `cache/CacheReadiness`（新） | 只有 `isReady()` 的接口，让 Resolver 不依赖 Redis 生命周期对象 |
| `cache/RedisCacheReadiness`（新） | 启动同步探测一次 + 每 2s 轮询；探测失败**保持上次状态**；关闭开关时恒为就绪 |
| `service/LinkResolver` | `defaultIfEmpty(notFound)` → `switchIfEmpty(onCacheMiss)`；未就绪时返回 503 并打点 |
| `metrics/RedirectMetrics` | 新增 `shortlink.cache.not_ready.total` |
| `admin/support/LinkCacheWriter` | `markCacheReady()` / `isCacheReady()` |
| `admin/service/CacheRebuildService` | 重建完成后先置位、再 bump 版本号、再 `EVICT_ALL` |
| `admin/support/CacheReadinessBootstrap`（新） | 空库启动自动置位；**库里有数据但标志缺失只打 WARN，绝不自动置位** |
| 配置 | `shortlink.redirect.ready-gate.{enabled,check-interval}`、`shortlink.admin.cache.ready-bootstrap` |

**影响**：重建完成前，未命中返回 503 而不是 404。这是刻意的取舍——宁可承认服务不可用，也不谎报链接不存在。

**测试**：`LinkResolverTest` +2、`RedisCacheReadinessTest` +2。

---

## B. 指标修正：`EVICT_ALL` 不再算到版本号兜底头上

**问题**：`cacheEvicted(boolean fromVersionPoll)` 用两个状态表达三个事件。重建后的全清走的是消息通道，却被记成 `source=version`，导致"版本号兜底次数"虚高——而这个指标的本意是"Pub/Sub 在丢消息"，虚高之后彻底失去信号价值。

**改动**：`RedirectMetrics` 引入 `EvictSource{PUBSUB, EVICT_ALL, VERSION}` 枚举（boolean 表达不了三态，这是根因），三个 Counter 各自注册；`CacheSyncSubscriber`、`CacheVersionWatcher` 调用点改为显式传枚举。

**影响**：`source=version` 恢复告警价值；`source=evict_all` 单独可见（重建是正常运维动作，不是故障）。

**测试**：`RedirectMetricsTest` +2（其中一条专门断言 `EVICT_ALL` 不计入 `version`）。

---

## C. 版本号对账：从"每次写都全清"改成"只在真丢消息时全清"

**问题**：admin 每次写都 `INCR`，watcher 看到版本变化就 `invalidateAll()`。后果是**只要有人在写，所有实例的 10 万条 L1 每秒被清一次**——精确失效被全清盖掉，命中率随写流量下降，批量导入 500 条直接变成灾难。根因是把"写入计数器"当成了"丢失探测器"。

**改动**

| 文件 | 内容 |
|---|---|
| `cache/VersionGapTracker`（新） | 对账逻辑：`expected = 版本增量 + 上轮缺口`，`actual = 实收消息数`；缺口先观察一轮，连续两轮补不上才判丢；版本回退（FLUSH）立即清 |
| `cache/CacheVersionWatcher` | 重写：`AtomicLong received` + tracker；启动首次延迟随机化（打散同相位实例）；读失败仍只跳过本轮 |
| `cache/CacheSyncSubscriber` | 每处理一条消息（含 `*`）上报 `onMessageReceived()` |
| `job/service/RedisCacheEvictor` | **补洞**：原本只 `PUBLISH` 不 `INCR`，这条消息丢了版本号永远发现不了 |

**为什么需要"观察一轮"**：`put()` 的顺序是 `INCR` 再 `PUBLISH`，中间有亚毫秒窗口。不做延迟判定，每次正常写都会被误判成丢消息——改造等于没做。

**影响**：正常写入零代价；丢消息发现时间从 ≤1s 变成 ≤2s（一轮观察），用 1 秒延迟换掉每次全清；批量导入不再触发全量失效。

**测试**：`VersionGapTrackerTest` +7（基线、正常写、消息晚到、真丢、版本回退、累积缺口、补齐后不误判）。

---

## D. job 批量失效：按批一次 pipeline

**问题**：清理任务逐条 `DEL + INCR + PUBLISH`，500 条就是 1500 次串行往返。

**改动**

| 文件 | 内容 |
|---|---|
| `job/service/CacheEvictor` | 新增 `evictBatch(List<String>)` 默认方法（逐条），保持向后兼容 |
| `job/service/RedisCacheEvictor` | 覆盖为一次 `executePipelined`：每条的 DEL/INCR/PUBLISH 在同一批次里发出 |
| `job/service/LinkCleanupService` | 由逐条循环改为按批调用 |

**两个刻意没做的选择**

1. **没用 `EVICT_ALL` 收口**：能再省到 2 条命令，但会清空所有实例的 L1——为省几条命令换整个缓存层，不划算。
2. **`INCR` 仍逐条**：版本号必须与广播消息 1:1，否则上一节的对账机制会把"只发消息不 bump"当成没丢。

**影响**：命令数不变，**往返从 1500 次降到 1 次**；失效精度不变；整批失败只打日志，靠 TTL 兜底，清理不中断。

**测试**：`LinkCleanupServiceTest` +1（假实现里 `evict()` 直接抛异常，改回逐条会立刻红）。

---

## E. 缓存写入带版本号（Lua CAS）

**问题**：DB 层两个并发写是"后提交的赢"，但两个 `HSET` 到达 Redis 的顺序可能与提交顺序相反——晚到的旧值会覆盖新值，**Redis 永久停在旧值**直到下一次写或 TTL。read-through 回填也有同源竞态：读取期间发生失效，旧值被写回空掉的 L1，且不会再有消息来清它。

**改动**

| 文件 | 内容 |
|---|---|
| `admin/support/LinkCacheWriter` | 写入改为 Lua 脚本：`HGET version >= 传入版本 → 拒绝`，否则 `HSET(含 version) + EXPIRE`，比较与写入原子完成；版本号取自 `INCR sl:cache:ver`（每次写 +1 的单调计数器）。写入顺序因此变为 **INCR → CAS 写 → PUBLISH** |
| `admin/service/CacheRebuildService` | 重建整批复用一个版本号（`currentVersion()`），中途不逐条 +1；重建期间的真实写入版本更高，会自然胜出——重建不覆盖更新的用户修改 |
| `cache/LinkSnapshot` | 新增 `version` 字段；**缺失按 0 处理**，否则上线那一刻所有老缓存都会变成"数据损坏 503" |
| `cache/RedisLinkCache` | `FIELDS` 增加 `version` |
| `cache/TwoLevelLinkCache` | 回填前检查两道：① 读取开始后是否发生过失效（按 key 记录水位 + 全局全清水位），发生过就放弃回填；② 本地已有更高版本则不覆盖（`asMap().merge`） |

**为什么版本号仍与消息 1:1**：版本号既是缓存条目的排序依据，也是失效通道的对账依据。写入被 CAS 拒绝时**仍然发消息**——否则版本 +1 而消息为 0，对账会把它当成丢消息并全量清空 L1。用一次多余的失效，换掉一次全量清空。

**测试**：`LinkSnapshotTest` 8（+3：六字段、缺 version 按 0、非法 version 按 0）、`TwoLevelLinkCacheTest` 6（+2：并发回填保留高版本、读取期间失效不回填）。

---

## F. 依赖红线断言落地

**问题**：设计文档 3.2 写的是"CI 加一条 `mvn dependency:tree` 断言，违反即失败"，但仓库里既没有 CI 配置也没有脚本——这是唯一"文档说做了、实际没做"的约束，评审时一打开就穿帮。

**改动**

| 文件 | 内容 |
|---|---|
| `scripts/check-redirect-deps.ps1`（新） | 解析 redirect 依赖树，命中红线退出码 1，解析失败退出码 2 |
| `scripts/check-redirect-deps.sh`（新） | 同逻辑的 bash 版本 |
| `.github/workflows/ci.yml`（新） | push / PR 触发：JDK 17 + `mvn test` + 依赖红线检查（用 pwsh 跑，Actions 镜像自带） |
| `shortlink-redirect/pom.xml` | 注释从"CI 会用断言"改为指向实际脚本 |

**两个实现细节**

1. 匹配用 `:artifact:` 而不是裸字符串——`spring-boot-starter-web` 是 `spring-boot-starter-webflux` 的前缀，裸匹配会把 WebFlux 自己判成违规；
2. 解析失败必须退出 2 而不是 0——"跑不了"不能静默当成"通过"。

**验证**：本地实跑两次。现状通过（103 行依赖、0 命中）；临时往 redirect 的 pom 注入 `spring-boot-starter-web` 后，脚本以退出码 1 报出 5 处违规（starter-web / spring-webmvc / tomcat-embed-core / tomcat-embed-el / tomcat-embed-websocket），随后已还原。
⚠️ bash 版本在本机**未实跑**（系统只有被沙箱拒绝的 WSL bash）；CI 里执行的是 PowerShell 版本。

---

## G. 文档

| 文件 | 改动 |
|---|---|
| `docs/shortlink-design.md` | 4.5 Key 表（补 `click:max`、`cache:ready`，划掉已移除的空值缓存，TTL 改为实现值）；新增 4.5.1 Hash 字段契约（含 `version`）；5.3.2 三件套与布隆表述按读链路实况重写；5.3.3 写路径流程改为 INCR → CAS → PUBLISH 并说明理由；版本号对账重写；4.5.1/5.3.3 补"写入口不止 admin"；5.4.1 流程图加就绪分支；5.4.5 新增 Redis 四种宕机形态 + 就绪标志；7.1 指标清单按服务重写并补三条告警；§10 已知问题（去掉已修的 404/503，保留熔断）；面试映射表修正（"MQ 广播"→"Pub/Sub 精确失效"） |
| `docs/write-path.md`（新） | 写链路端到端时序、逐段拆解、失败模式矩阵、三条不变式、面试追问映射 |
| `docs/module-plan.md` | redirect"三处修正"；admin 补步骤 11（就绪标志）；job 当前内容与 pipeline 注意事项；风险表 5/7 更新 |

---

## 验证

```
mvn -o -B test
Reactor 20 个模块  BUILD SUCCESS

powershell -ExecutionPolicy Bypass -File scripts/check-redirect-deps.ps1 -Offline
PASS: shortlink-redirect 依赖树干净（103 行，无 Servlet / DB 依赖）
```

新增/变更用例：`VersionGapTrackerTest` 7、`RedirectMetricsTest` 2、`RedisCacheReadinessTest` 2、`LinkResolverTest` +2、`LinkSnapshotTest` +3、`TwoLevelLinkCacheTest` +2、`LinkCleanupServiceTest` +1。

---

## 遗留问题（按优先级）

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| 1 | redirect 没有熔断器 | Redis 持续不可用时每个 miss 仍付 50ms 超时 | 加熔断需处理"已恢复但仍在窗口"的问题，收益不大 |
| 2 | Caffeine 单条 800B 是估算，未实测 | `maxSize` 可能过于保守（实际或 300~400B，可上调到 20 万） | 用 JOL 或 heap dump 校准，别在面试里当实测报 |
| 3 | `LocalCaches.hitRate()`、`TwoLevelLinkCache.estimatedSize()` 无调用方 | 死代码或待接 gauge | 接成 gauge 或删除 |
| 4 | 失效水位依赖单机 `nanoTime` 与 30s 记忆窗口 | 时钟回拨/进程重启后水位失效，只影响极低概率的回填竞态 | 可接受；要更强改为"消息带版本号" |

---

## 如果只记三件事

1. **未命中不等于不存在**：`sl:cache:ready` 缺失时降级 503，把静默的数据事故变成可告警的可用性事故。
2. **版本号是发布序号，不是脏标记**：只有"版本增量 ≠ 实收消息数"才全清；否则每次写都在清空缓存。
3. **凡是能改缓存的地方都得接同一条失效通道**：admin 写、job 清理，一个都不能漏。
