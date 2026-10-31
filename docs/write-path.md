# 写链路工作流程（shortlink-admin）

> 面向面试讲解与代码评审。设计背景见 [详细设计 §5.3](./shortlink-design.md)，
> 读链路见 §5.4。本文只讲**一次写入从进来到被 redirect 看到**的全过程。

---

## 0. 一句话概括

> 写链路是**唯一能改数据的地方**，它要保证三件事：短码不重复、缓存不落后于库、
> 失败时不留下"库里没有但能跳转"或"库里有但跳不过去"的中间态。
> 所有设计都是围绕这三件事展开的。

角色边界：**admin 写、redirect 读**。redirect 的依赖树里禁止出现 JDBC（CI 断言），
所以它无法回源 MySQL，Redis 就是它的唯一数据源——这一点决定了写链路的每一步顺序。

---

## 1. 端到端时序

```
Client
  │  POST /api/v1/links   (Idempotency-Key 可选)
  ▼
Gateway ── JWT 校验 → 注入 X-User-Id ── IP 黑名单 ── 限流 ── TraceId
  │  401 / 403 / 429 在这一层就结束，不进 admin
  ▼
LinkController.create()
  ▼
LinkService.create()
  │
  ├─[0] 幂等闸门（只有带了 Idempotency-Key 才走）
  │      SETNX sl:idem:{key} = PROCESSING (TTL 24h)
  │        ├─ 抢到 → 执行 [1]~[6] → SET 结果
  │        └─ 没抢到 → 每 50ms 轮询，最多 3s → 拿首次结果 / 报"处理中"
  │
  ├─[1] 校验        UrlValidator: 协议白名单 / 长度 / userinfo / 自跳转 / 内网 IP
  ├─[2] 归一化      trim、groupId 默认 0、title ≤128、expireTime 必须晚于 now
  ├─[3] 业务去重    (groupId, md5(longUrl)) 命中 → 刷缓存 + 直接返回已有记录
  ├─[4] 生成候选    发号 → Feistel 编码 7 位短码；gid = ULID
  ├─[5] 落库        insert（唯一事务边界，@Transactional）
  └─[6] 副作用      布隆 → 写 Redis → INCR 版本号 → PUBLISH 失效 → 审计
  ▼
LinkView { gid, shortCode, shortUrl, ... }
  ▼
redirect: L1 失效 → 下次请求回 Redis 读到新值
```

一次创建大约 4~5 次 Redis 往返（HSET + EXPIRE + INCR + PUBLISH）和 2 次 MySQL 写
（业务行 + 审计行），本地耗时量级 5~10ms。

---

## 2. 逐段拆解

### [0] 幂等闸门：解决"网络重试"，不是"业务重复"

```
SETNX sl:idem:{key} = \0PROCESSING  EX 24h
```

- **先占位再执行**：顺序反过来，两个并发请求会同时穿过；
- **业务失败必须释放占位**，否则用户永远无法重试；
- **并发重复不报错，而是等待结果**：第二个请求轮询 3s，拿不到就返回"处理中"。
  语义是"告诉我第一次的结果"，不是"你重复提交了"。

`Idempotency-Key` 由客户端生成（UUID）。它防的是"超时重试导致建了两条一模一样的短链"。

### [1] 校验：短链本质是开放重定向

必须在校验层挡住的东西：`javascript:` / `data:` 协议（XSS）、`http://trusted@evil.com`
（userinfo 伪装）、指向自身域名（跳转环）、内网 IP 字面量（SSRF）。

**V1 的已知缺口**：只挡 IP 字面量，攻击者用域名解析到内网仍可绕过；域名黑名单、
DNS 解析后真实 IP 校验、重定向链跟随都是 V4 的内容。评审时别说"已经安全了"。

### [2] 归一化：把"看起来一样"的输入变成"真的不一样"

`title` 截断到 128，`expireTime` 必须晚于当前时间，`maxClick` 负数归零。
这一步的目的是让 [3] 的去重有意义——不去掉首尾空格，`" https://a.com"` 和
`"https://a.com"` 会被当成两条不同的短链。

### [3] 业务去重：和幂等键是两层，别混

| 层 | 依据 | 语义 | 存储 |
|---|---|---|---|
| 幂等 | `Idempotency-Key` | "这是我刚才那次请求的重试" | Redis SETNX，24h |
| 业务去重 | `(group_id, md5(longUrl))` | "这个分组里已经有这条长链了" | MySQL `idx_hash_group` |

命中业务去重时**仍然要刷一次缓存**——因为这条记录的 Redis Hash 可能已被淘汰或过期，
而 redirect 只认 Redis。

### [4] 生成候选：发号在事务外

```
segmentId = idGenerator.nextId()          // 内存操作，双 Buffer
shortCode = codec.encode(segmentId)       // Feistel 双射，恰好 7 位
gid       = Ulid.next()                   // 对外 ID，防遍历
```

发号刻意放在事务外：它不需要回滚，失败了最多浪费一个号（7 位 Base62 有 3.5 万亿）。
把它放进事务只会拉长事务、白白占着连接。

### [5] 落库：全链路唯一的事务边界

```java
@Transactional(rollbackFor = Exception.class)
public PersistResult insertOrGetExisting(ShortLinkDO candidate) {
    try {
        linkMapper.insert(candidate);
        return new PersistResult(candidate, true);
    } catch (DuplicateKeyException ex) {          // uk_short_code 兜底
        ShortLinkDO existing = selectByShortCode(candidate.getShortCode());
        if (existing == null) throw ex;            // 不是短码冲突 → 真 bug，原样抛
        return new PersistResult(existing, false); // 降级成"复用"
    }
}
```

`uk_short_code` 是最后一道防线：**发号器或编码器出 bug 时，宁可插入失败也不能产生重复短码**。
捕获异常后回查，把内部错误降级成"复用已有记录"，避免把 500 暴露给用户。

> 为什么异常捕获后不回滚：MySQL 允许在重复键错误后继续执行语句，
> 且 `DuplicateKeyException` 在方法内被捕获不会触发回滚（见代码注释）。

### [6] 副作用：顺序不能乱

```
① bloomFilter.add(shortCode)        仅 created = true 时
② cacheWriter.put(entity)
     └ writeOnly(): HSET sl:link:{code} + EXPIRE(24h + 0~10% 抖动)，失败重试 3 次
          ├─ 成功 → markCacheChanged()  INCR sl:cache:ver
          │       → publishEvict(code)  PUBLISH sl:cache-sync
          └─ 失败 → 计数器 shortlink.cache.write.failure + ERROR 日志，**不发通知**
③ operateLogService.record(...)     整体 catch，失败只打 ERROR
```

三条规则：

1. **缓存和 MQ 绝不进事务**。事务回滚不会撤销已经写进 Redis 的值、也不会撤回已经发出去的消息——把副作用放进事务，等于制造"库里没有、缓存里有"的幽灵短链。
2. **通知只在 Redis 写入成功后发**。写失败还发通知，只会让所有 redirect 实例一起去读同一份旧值，白白制造一次缓存穿透。
3. **"顺手做的事"失败不能拖垮主流程**。审计失败、幂等结果落库失败都只打日志；但缓存写失败要打点告警——它是唯一会让用户拿到"打不开的短链"的失败。

---

## 3. 与 redirect 的交接

```
admin 写完 Redis ──PUBLISH──▶ redirect 各实例 invalidate L1（毫秒级）
                 ──INCR────▶ 版本号对账：版本增量 ≠ 实收消息数（且补不齐）才 invalidateAll
```

交接物是 Redis Hash `sl:link:{code}`，字段契约见设计文档 4.5.1（**写侧、读侧、解析三处必须同时改**）。

一个容易被忽略的好处：读链路**没有负缓存**（`sl:link:null:` 已移除），
所以新建一条短链不存在"某个实例缓存了 404 导致访问不到"的问题——第一次访问必然回 Redis。

---

## 4. 失败模式矩阵（面试重点）

| 失败点 | 数据状态 | 用户看到 | 兜底 |
|---|---|---|---|
| 网关 401/429 | 无 | 401/429 | 未进 admin |
| 校验失败 | 无 | 400 + 具体原因 | — |
| 发号失败 | 无 | 500 | 号段预取 + 重试 3 次 |
| 唯一键冲突 | 已有记录 | **200 + 已有短码** | 回查复用 |
| **DB 成功 + Redis 失败** | **库有、缓存无** | **200 拿到链接，但访问 404** | 重试 3 次 + 告警 + rebuild |
| 审计落库失败 | 业务正常 | 200 | catch + ERROR（少一条审计） |
| 幂等结果落 Redis 失败 | 业务正常 | 200 | 退化为"再执行一次"，业务去重兜住 |
| 失效通知发送失败 | Redis 是新值、L1 是旧值 | 最长约 2s 旧跳转 | 版本号对账（含一轮观察期）/ TTL |

**最需要主动说明的是第 5 行**：DB 写成功、Redis 写失败时，代码仍然返回 200。
用户拿到的短链这一刻是打不开的（redirect 只能读 Redis），要等缓存重试或全量重建。
这是"读侧无 DB"架构的固有代价，**不要假装不存在**。可选改进：
缓存写最终失败时返回 503 让调用方重试，或落一条补偿记录由 job 定时重放。

---

## 5. 两条特殊路径

### 5.1 批量导入（≤500 条）

逐条复用 `doCreate`，单条失败不影响其他，最后返回 `{total, success, failed, failures[]}`
（带下标和原因）。审计只记一条汇总，不记 500 条。

✅ 已修正：版本号从"版本一变就全清"改成"对账不符才全清"之后，批量导入不再触发 L1 全清——
每条写入的 `INCR` 与失效消息 1:1 配对，正常情况永远对得上。500 条只是 500 次精确失效，
代价是每条一次毫秒级消息，可忽略。（改造前这里会让所有实例清空 10 万条缓存，是当时的已知问题。）

### 5.2 全量重建（运维路径）

```
分页扫 t_short_link（游标升序，每批 100~5000）
  → writeOnly() 逐条灌 Redis（不逐条广播，否则打爆频道）
  → bloomFilter.addAll() 批量补位
  → markCacheReady()   置位 sl:cache:ready（开闸：miss 才能判 404）
  → markCacheChanged() + publishEvictAll()  统一通知一次
```

顺序有讲究：**先灌数据、再开闸、最后通知**。开闸早了会把"还没灌完"误判成"不存在"。

---

## 6. 三条不变式（评审时会被问）

1. **副作用永远在事务提交之后**，且顺序固定：DB → Redis → 通知。
2. **正确性靠数据库约束兜底**，不靠应用层保证：短码唯一 → `uk_short_code`；
   同组同长链唯一 → `idx_hash_group` + 查询。
3. **降级方向要明确**：审计失败可以丢，通知失败可以等 1s，缓存失败必须告警——
   三类副作用的"可丢失程度"不同，处理方式也必须不同。

---

## 7. 面试追问映射

| 追问 | 答 |
|---|---|
| 为什么先落库再写缓存？ | 反过来会留下"能跳转但库里没有"的幽灵短链；且事务回滚不会撤销 Redis 写入 |
| 为什么是更新缓存而不是删除？ | redirect 没有 DB，删了就是全站 404。这是与通用 Cache Aside 最大的区别 |
| 缓存写失败怎么办？ | 重试 3 次 + `shortlink.cache.write.failure` 告警 + rebuild 兜底；已知代价是这一小段时间内短链打不开 |
| 幂等键和业务去重有什么区别？ | 前者防网络重试（24h Redis），后者防业务重复（MySQL 索引）；两层都要有 |
| 唯一键冲突为什么吞掉不抛？ | `uk_short_code` 是最后的防线，冲突说明发号/编码有 bug；回查复用比把 500 抛给用户好，同时不产生重复短码 |
| 审计为什么要 catch？ | 少一条审计 << 把成功的创建变成 500 |
| 批量导入用不用事务？ | 不用。500 条一个大事务会长时间持锁，且单条失败不该回滚其他条；用逐条失败明细代替 |
| 建完之后 redirect 多久能看到？ | Redis 同步写（毫秒）+ Pub/Sub 失效（毫秒）；消息丢了靠版本号对账约 2s；Redis 整体挂掉就只剩 TTL（≤12min） |
| 发号器在事务里吗？ | 不在。发号不需要回滚，失败最多浪费一个号，放事务里只会拉长事务 |
