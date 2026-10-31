# 短链平台（ShortLink）详细设计文档

| 项目 | 内容 |
|---|---|
| 版本 | v1.0（设计稿） |
| 日期 | 2026-10-24 |
| 定位 | 简历级高并发微服务项目，读多写少型系统的完整落地 |
| 文档范围 | 技术栈、架构、数据模型、核心流程、接口、非功能设计、部署与演进 |
| 非目标 | 不覆盖多租户计费、不覆盖前端实现细节、不做跨机房双活 |

---

## 目录

1. [技术栈选型](#1-技术栈选型)
2. [总体架构](#2-总体架构)
3. [模块与职责边界](#3-模块与职责边界)
4. [数据模型](#4-数据模型)
5. [核心设计](#5-核心设计)
6. [接口设计](#6-接口设计)
7. [非功能设计](#7-非功能设计)
8. [部署方案](#8-部署方案)
9. [演进路线](#9-演进路线)
10. [关键取舍与风险](#10-关键取舍与风险)
11. [面试映射表](#11-面试映射表)

---

## 1. 技术栈选型

### 1.1 选型总表

| 层次 | 选型 | 建议版本 | 选它的理由 | 不选什么 |
|---|---|---|---|---|
| 语言/运行时 | JDK | 21 (LTS) | 虚拟线程、ZGC 成熟；虚拟线程对 IO 密集的 admin/stats 收益明显 | JDK 8 太老，JDK 17 也可但 21 更符合“新项目”叙事 |
| 基础框架 | Spring Boot | 3.3.x | 与 Cloud 2023.0.x 对齐，原生支持 GraalVM/虚拟线程 | Boot 2.x 已 EOL |
| 微服务框架 | Spring Cloud | 2023.0.x | Gateway / OpenFeign / LoadBalancer 版本统一 | Dubbo（本项目无强 RPC 诉求，HTTP 足够） |
| 注册与配置 | Nacos | 2.3.x | 注册+配置一体，国内生态好，面试认可度高 | Eureka + Config（组件多、已停更） |
| 网关 | Spring Cloud Gateway | 随 Cloud | WebFlux 非阻塞，路由/过滤器扩展方便 | Zuul 1（阻塞，已淘汰） |
| Web 框架（redirect） | Spring WebFlux + Netty | 随 Boot | 跳转是纯 IO 操作，少量线程扛高并发；相比 MVC 省线程栈和上下文切换 | MVC + 虚拟线程（也可，但 WebFlux + Reactive Redis 更彻底，且能量化对比，是加分项） |
| Web 框架（admin/stats） | Spring MVC | 随 Boot | 写侧 QPS 低，同步模型开发效率高 | 不必强行全栈响应式 |
| ORM | MyBatis-Plus | 3.5.x | 单表 CRUD 少写样板，复杂 SQL 仍可手写 | JPA（分页/分片场景更绕） |
| 分库分表 | ShardingSphere-JDBC | 5.5.x | 客户端分片无代理，无额外网络跳数 | MyCat（已停更）、ShardingSphere-Proxy（多一跳，个人项目没必要） |
| 缓存 | Redis | 7.2 | 数据结构全，7.x 有 Function/客户端缓存 | Memcached（无持久化/HLL） |
| Redis 客户端 | Lettuce（WebFlux 用 reactive） + Redisson（分布式锁） | 随 Boot / 3.x | Lettuce 线程安全、支持响应式；Redisson 补分布式锁/限流器 | Jedis（连接模型不适合高并发） |
| 本地缓存 | Caffeine | 3.1.x | W-TinyLFU 命中率优于 LRU，redirect 的核心武器 | Guava Cache（已弱维护） |
| 消息队列 | Kafka | 3.7（KRaft 模式） | 点击流高吞吐、可回放、分区有序；个人项目部署比 RocketMQ 轻 | RocketMQ（广播消费更顺手，但需 NameServer，运维重）；RabbitMQ（吞吐不够） |
| 实时数仓 | ClickHouse | 24.x | 亿级明细秒级聚合，UV 精确去重 `uniqExact`，报表场景碾压 MySQL | ES（聚合慢、存储贵）、Doris（也可，但 CH 单机 Docker 更轻） |
| 缓存同步 | Canal | 1.1.8 | 订阅 MySQL binlog，解耦“写库”与“刷缓存”，是最终一致的兜底 | 纯双写（丢消息就永久不一致） |
| 调度 | XXL-JOB | 2.4.x | 过期清理、缓存预热、统计补偿、对账 | Quartz（无控制台、无失败重试） |
| 链路追踪 | Micrometer Tracing + OTel / SkyWalking | — | TraceId 全链路透传（HTTP + Feign + Kafka Header） | 只打日志不串链路（面试一问就露馅） |
| 指标监控 | Micrometer + Prometheus + Grafana | — | QPS/RT/命中率/消费 lag 可视化，压测报告的数据来源 | — |
| 接口文档 | SpringDoc OpenAPI | 2.x | 自动生成，方便演示 | Swagger 2（与 Boot 3 不兼容） |
| 对象映射 | MapStruct | 1.6.x | 编译期生成，无反射 | BeanUtils（反射慢） |
| 测试 | JUnit 5 + Testcontainers | — | 中间件用容器起，CI 可跑 | 只写单测不测集成 |
| 压测 | wrk2 / JMeter | — | wrk2 能打恒定速率，出准确 P99 | ab（单线程，测不出高并发） |
| 部署 | Docker Compose | — | 一键起全套，简历演示友好 | K8s（个人项目过重，面试可讲“可平滑迁 K8s”） |

> ⚠️ 版本落地前请核对官方兼容矩阵：Spring Cloud Alibaba 的 2023.0.3.x 需与 Boot 3.2/3.3 严格对齐，版本错配是新手最容易卡住的地方。

### 1.2 关键选型的三个“为什么”

**为什么 redirect 用 WebFlux？**
跳转链路 99% 时间在等 Redis/网络，线程几乎不占 CPU。MVC 默认 200 线程，在 5w 并发连接下线程栈和上下文切换成本显著；WebFlux 用事件循环，少量线程即可。代价是阻塞 API 不能用（Redis 必须走 `ReactiveStringRedisTemplate`），代码可读性下降。**这个取舍本身就是面试素材**：可以用同一份压测脚本对比 MVC 与 WebFlux 的 P99 和内存占用。

**为什么用 Kafka 而不是 RocketMQ？**
点击流是“高吞吐、允许少量丢失、需要回放补数”的场景，Kafka 分区顺序 + 位点回放天然契合，且 KRaft 模式单容器即可跑。代价是 Kafka 没有原生广播消费，redirect 集群的本地缓存刷新需要“每实例独立 groupId”来模拟广播——这点在 5.3 节展开。

**为什么统计明细放 ClickHouse 而不是 MySQL？**
日点击 1000 万，一年 36 亿行。MySQL 存明细要么分表到天、要么直接崩；CH 列存 + 稀疏索引，`uniqExact(ip_hash)` 算 UV 在亿级也就秒级，且 TTL 自动清理冷数据。

---

## 2. 总体架构

### 2.1 部署拓扑

```
                        ┌──────────────────────────┐
                        │   SLB / Nginx (四层)      │
                        └───────┬──────────┬───────┘
                                │          │
              admin.example.com │          │ s.example.com  （短链独立域名）
                                ▼          ▼
                     ┌──────────────┐  ┌───────────────────────────┐
                     │  gateway     │  │  redirect  (N 副本)        │
                     │  :8080       │  │  :8081  无 MySQL 依赖       │
                     │  JWT/限流/审计│  │  本地缓存 + Redis + Kafka   │
                     └──┬───────┬───┘  └──────┬──────────┬─────────┘
                        │       │             │          │
                        ▼       ▼             ▼          ▼
                 ┌──────────┐ ┌──────────┐  ┌─────┐  ┌───────┐
                 │  admin   │ │  stats   │  │Redis│  │ Kafka │
                 │  :8082   │ │  :8083   │  │     │  │       │
                 └─┬──┬──┬──┘ └──┬───────┘  └─────┘  └───┬───┘
                   │  │  │       │                       │
                   │  │  │       ▼                       ▼
                   │  │  │  ┌───────────┐          ┌────────────┐
                   │  │  └──│ ClickHouse│◄─────────│   stats-   │
                   │  │     └───────────┘          │  consumer  │
                   │  │                            └────────────┘
                   │  ▼
                   │ ┌────────┐      ┌────────┐
                   └─│ MySQL  │─────►│ Canal  │──binlog──► Kafka
                     └────────┘      └────────┘
                                     ┌────────┐
                                     │ Nacos  │  注册/配置
                                     └────────┘
```

### 2.2 一个重要的架构决策：redirect 不走网关

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A. 全部流量过网关 | 统一入口、统一限流/黑名单 | 网关扛全量 QPS，扩容成本翻倍；多一跳 RT；WebFlux 过滤器里一个阻塞调用就雪崩 | ❌ |
| B. redirect 独立域名直连 LB | 少一跳、网关只服务 admin/stats、故障隔离 | 网关的黑名单/限流覆盖不到 redirect | ✅ **采用** |
| C. 折中：网关路由到 redirect，但仅做透传 | 保留“统一入口”叙事 | 仍然是全量过网关，成本问题没解决 | 仅演示用 |

**B 方案下黑名单怎么办？** 黑名单数据在 admin 维护 → 写入 Redis + 发 Kafka `cache-sync` 广播 → 每个 redirect 实例刷新本地布隆/Set。这样 redirect 既不依赖网关，也不依赖 DB。**这个“数据面下沉 + 控制面集中”的思路，是整份文档最值得讲的设计。**

### 2.3 服务清单

| 服务 | 端口 | 依赖 | 副本数 | 职责 |
|---|---|---|---|---|
| shortlink-gateway | 8080 | Nacos、Redis | 2 | 路由、JWT 鉴权、IP 粗粒度限流、TraceId 注入、访问审计 |
| shortlink-admin | 8082 | MySQL、Redis、Kafka、Nacos | 2 | 短链 CRUD、分组/域名、批量导入、发号、幂等、风控校验、黑名单维护 |
| shortlink-redirect | 8081 | Redis、Kafka（**无 MySQL**） | N（3~10） | 短码解析、302 跳转、本地多级缓存、本地限流、点击事件投递 |
| shortlink-stats | 8083 | Kafka、ClickHouse、MySQL(stats 库)、Redis | 2 | 消费聚合、明细落 CH、报表查询、HLL 实时 UV |
| shortlink-job | — | MySQL、Redis | 1 | 过期清理、预热、对账、批量任务调度（可并入 admin，独立更清晰） |

> 副本数最小化原则：个人项目一台 16G 机器跑 4 个 JVM 已是上限，别开 2 副本 × 4 服务。文档里写清“生产 2 副本”，本地跑 1 副本。

---

## 3. 模块与职责边界

### 3.1 Maven 结构

落地后的实际结构（19 个 POM，已通过 `mvn validate` 验证 Reactor 完整）：

```
shortlink                                  # 聚合 + 版本治理（父 POM 即 BOM）
├── pom.xml
├── shortlink-lib                          # 公共库聚合（不含可部署服务）
│   ├── shortlink-common-core              # 零依赖：Result/ErrorCode/BizException/TraceContext/ULID
│   ├── shortlink-common-web               # Servlet 栈横切：全局异常 + TraceId Filter
│   ├── shortlink-common-webflux           # Reactive 栈横切：全局异常 + TraceId WebFilter
│   ├── shortlink-common-cache             # Redis key 契约 + Caffeine 工厂
│   ├── shortlink-common-mq                # Topic 常量 + 事件模型 + 发布抽象
│   ├── shortlink-component-idgen          # 号段发号器（双 Buffer + 乐观锁）
│   ├── shortlink-component-codec          # Base62 + Feistel FPE + 短码编解码
│   ├── shortlink-component-bloom          # Redis Bitmap 布隆过滤器
│   ├── shortlink-component-idempotent     # 幂等守卫
│   └── shortlink-component-ratelimit      # 本地令牌桶
├── shortlink-gateway                      # :8080  接入层
├── shortlink-admin                        # :8082  写侧
├── shortlink-redirect                     # :8081  读侧（无 MySQL 依赖）
├── shortlink-stats                        # 聚合
│   ├── shortlink-stats-consumer           # :8084  消费端
│   └── shortlink-stats-api                # :8083  查询端
└── shortlink-job                          # :8085  定时任务
```

**与初版设计的三处修正（都有具体原因，不是随意改动）**

| 修正 | 原因 |
|---|---|
| 取消独立的 `shortlink-dependencies` BOM 模块 | 父 POM 去 `import` 自己的子模块 BOM 会构成 import 循环，Maven 直接报 `dependencies of type=pom and with scope=import form a cycle`（已实测）。版本治理收敛到父 POM 的 `dependencyManagement`，效果等价且没有循环风险 |
| `common-web` 拆分为 `common-web`(MVC) + `common-webflux`(Reactive) | redirect 用 WebFlux。只要依赖了带 `spring-boot-starter-web` 的模块，就会同时引入 Tomcat 与 Netty，两个 Web 栈并存会直接导致启动失败。Servlet 与 Reactive 的横切代码（异常处理、过滤器）在类型层面就无法共用 |
| redirect 的依赖用 CI 断言守住 | “无 DB 依赖”不能靠自觉。`mvn dependency:tree` 里出现 `mysql-connector-j`、`spring-boot-starter-web`、`mybatis-*` 任意一个就说明架构已经破了 |

### 3.2 边界规则（写进 README，评审时讲）

| 规则 | 说明 |
|---|---|
| redirect 的依赖树里不允许出现 Servlet 栈 / JDBC | `scripts/check-redirect-deps.ps1`（或 `.sh`）解析 `dependency:tree`，命中 `spring-boot-starter-web`、`tomcat-*`、`mybatis*`、`mysql-connector`、`shortlink-common-web` 即失败并以退出码 1 结束；`.github/workflows/ci.yml` 每次构建都跑。这是"无 DB 依赖"的硬保障，不是口号 |
| common-core 不允许依赖 Spring Web / MyBatis | 保证任何服务引入它都不会被污染 |
| 跨服务只走 HTTP/Feign 或 MQ，不共享数据库表 | admin 与 stats 各自库；stats 不回查 admin 的库 |
| 发号器只在 admin 进程内使用 | redirect 绝不发号，避免号段竞争 |
| 所有对外 ID 用 `gid`（ULID），`id` 自增主键不外露 | 防遍历、防爬 |

---

## 4. 数据模型

### 4.1 存储划分

| 存储 | 库/表 | 用途 | 是否分片 |
|---|---|---|---|
| MySQL | `shortlink` | 短链主数据、分组、域名、号段、批量任务、操作日志 | 阶段二按 `short_code` 分片 |
| MySQL | `shortlink_stats` | 日/时聚合结果、看板汇总 | 否 |
| Redis | — | 短链缓存、布隆、限流、实时 PV/UV、黑名单 | Cluster |
| ClickHouse | `shortlink` | 点击明细、物化视图日聚合 | 按天分区 |
| Kafka | — | 点击流、缓存同步、binlog 变更 | 分区 |

### 4.2 ER 关系

```
t_link_group 1 ──── N t_short_link N ──── 1 t_domain
                          │
                          ├── 1:N  t_operate_log（逻辑关联）
                          └── 1:N  t_stats_link_daily（跨库，靠 short_code 关联）

t_id_segment      独立（发号）
t_batch_task      独立（批量导入，产出 N 条 t_short_link）
```

### 4.3 表结构总览

| 表名 | 所在库 | 预估量级 | 说明 |
|---|---|---|---|
| `t_short_link` | shortlink | 千万级 | 核心主表 |
| `t_link_group` | shortlink | 万级 | 分组 |
| `t_domain` | shortlink | 百级 | 短链域名池 |
| `t_id_segment` | shortlink | 十级 | 号段发号 |
| `t_batch_task` | shortlink | 万级 | 批量导入任务 |
| `t_operate_log` | shortlink | 千万级 | 操作审计，按月归档 |
| `t_stats_link_daily` | shortlink_stats | 亿级 | 短链日聚合 |
| `t_stats_overview_daily` | shortlink_stats | 万级 | 大盘日汇总 |

### 4.4 MySQL DDL

#### 4.4.1 短链主表

```sql
CREATE DATABASE IF NOT EXISTS `shortlink`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `shortlink`;

CREATE TABLE `t_short_link` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键，分片后仅表内唯一',
  `gid`           CHAR(26)        NOT NULL                COMMENT '业务ID(ULID)，对外暴露，防遍历',
  `short_code`    VARCHAR(16)     COLLATE utf8mb4_bin NOT NULL COMMENT '短码，7位Base62，大小写敏感',
  `long_url`      VARCHAR(2048)   NOT NULL                COMMENT '原始长链，最长2048',
  `long_url_hash` CHAR(32)        NOT NULL                COMMENT '长链MD5(小写)，用于同分组去重',
  `domain_id`     BIGINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '短链域名ID -> t_domain.id',
  `group_id`      BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '分组ID -> t_link_group.id，0=默认分组',
  `title`         VARCHAR(128)    NOT NULL DEFAULT ''     COMMENT '备注标题',
  `creator_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '创建人ID',
  `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '1启用 0禁用(封禁)',
  `expire_time`   DATETIME(3)     DEFAULT NULL            COMMENT '过期时间，NULL=永久',
  `max_click`     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '最大点击次数，0=不限(限流型短链)',
  `click_count`   BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '累计点击，异步回写，非强一致',
  `version`       INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
  `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `del_flag`      TINYINT         NOT NULL DEFAULT 0      COMMENT '0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_short_code` (`short_code`),
  UNIQUE KEY `uk_gid` (`gid`),
  KEY `idx_group_ctime` (`group_id`, `del_flag`, `create_time`),
  KEY `idx_creator_ctime` (`creator_id`, `create_time`),
  KEY `idx_hash_group` (`long_url_hash`, `group_id`),
  KEY `idx_expire` (`expire_time`),
  KEY `idx_ctime` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='短链主表';
```

**设计说明**

- **`short_code` 必须显式 `COLLATE utf8mb4_bin`**：Base62 字母表区分大小写（`a`≠`A`），而 MySQL 默认的 `utf8mb4_0900_ai_ci` 是大小写不敏感的。若用默认排序规则，`uk_short_code` 会把 `aB3xK9z` 与 `Ab3xK9z` 判为重复，导致大量合法短码插不进去——这是本项目最容易踩的坑之一，且只在特定短码组合下才复现。
- `long_url` 用 `VARCHAR(2048)` 而非 `TEXT`：可走行内存储，避免溢出页；不建索引（`utf8mb4` 下 2048×4 字节远超索引上限），去重靠 `long_url_hash`。
- `uk_short_code` 是最后一道防线：即使发号器/FPE 出 bug 产生重复，也只会插入失败而不是脏数据。
- `click_count` 放主表是为了列表页展示，但它是**异步回写**的，允许滞后；精确值从 stats 查。**这个字段是“展示与准确分离”的典型设计，面试可讲。**
- `del_flag` 软删除；`idx_group_ctime` 带上 `del_flag` 让列表查询走覆盖更优。
- 关于唯一索引与软删除：如果业务要求“删除后同分组同名可重建”，把 `del_flag` 改成 `BIGINT`，删除时写入 `id` 值，唯一索引改为 `(group_id, long_url_hash, del_flag)`。

#### 4.4.2 分组表

```sql
CREATE TABLE `t_link_group` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `group_name`  VARCHAR(64)     NOT NULL                COMMENT '分组名',
  `owner_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '归属人',
  `link_count`  INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '冗余短链数(异步更新)',
  `remark`      VARCHAR(255)    NOT NULL DEFAULT '',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `del_flag`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_owner_name` (`owner_id`, `group_name`),
  KEY `idx_owner` (`owner_id`, `del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='短链分组';
```

#### 4.4.3 域名池

```sql
CREATE TABLE `t_domain` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `domain`      VARCHAR(128)    NOT NULL                COMMENT '如 s.example.com',
  `protocol`    TINYINT         NOT NULL DEFAULT 2      COMMENT '1=http 2=https',
  `is_default`  TINYINT         NOT NULL DEFAULT 0      COMMENT '1=默认域名，全局唯一由应用保证',
  `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '1启用 0停用',
  `remark`      VARCHAR(255)    NOT NULL DEFAULT '',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_domain` (`domain`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='短链域名池';
```

#### 4.4.4 号段发号表

```sql
CREATE TABLE `t_id_segment` (
  `biz_tag`     VARCHAR(64)     NOT NULL                COMMENT '业务标识，如 short_code',
  `max_id`      BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '当前已分配到的最大值',
  `step`        INT UNSIGNED    NOT NULL DEFAULT 1000   COMMENT '号段步长',
  `version`     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`biz_tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='号段发号器';

-- 关键：初始值设为 62^6，保证每个短码编码后恰好 7 位，无需补位
INSERT INTO `t_id_segment` (`biz_tag`, `max_id`, `step`)
VALUES ('short_code', 56800235584, 1000)
ON DUPLICATE KEY UPDATE `biz_tag` = `biz_tag`;
```

取号 SQL（乐观锁，必须带 version，否则并发下会发出重复号）：

```sql
UPDATE t_id_segment
   SET max_id = max_id + step, version = version + 1
 WHERE biz_tag = 'short_code' AND version = #{version};
```

#### 4.4.5 批量导入任务

```sql
CREATE TABLE `t_batch_task` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_no`        CHAR(26)        NOT NULL                COMMENT '任务号(ULID)',
  `group_id`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `file_name`      VARCHAR(255)    NOT NULL DEFAULT '',
  `file_url`       VARCHAR(512)    NOT NULL DEFAULT ''     COMMENT '原始文件(OSS/MinIO)',
  `total_count`    INT UNSIGNED    NOT NULL DEFAULT 0,
  `success_count`  INT UNSIGNED    NOT NULL DEFAULT 0,
  `fail_count`     INT UNSIGNED    NOT NULL DEFAULT 0,
  `status`         TINYINT         NOT NULL DEFAULT 0      COMMENT '0待处理 1处理中 2完成 3失败 4部分成功',
  `error_file_url` VARCHAR(512)    NOT NULL DEFAULT ''     COMMENT '失败明细下载地址',
  `creator_id`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `create_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_no` (`task_no`),
  KEY `idx_status_ctime` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='批量导入任务';
```

#### 4.4.6 操作日志

```sql
CREATE TABLE `t_operate_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `biz_type`    VARCHAR(32)     NOT NULL                COMMENT 'LINK/GROUP/DOMAIN/BLACKLIST',
  `biz_id`      VARCHAR(64)     NOT NULL                COMMENT '业务ID',
  `action`      VARCHAR(32)     NOT NULL                COMMENT 'CREATE/UPDATE/DELETE/ENABLE/DISABLE',
  `before_json` JSON            DEFAULT NULL,
  `after_json`  JSON            DEFAULT NULL,
  `operator_id` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `client_ip`   VARCHAR(64)     NOT NULL DEFAULT '',
  `trace_id`    CHAR(32)        NOT NULL DEFAULT '',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_biz` (`biz_type`, `biz_id`),
  KEY `idx_ctime` (`create_time`),
  KEY `idx_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='操作审计日志，按月归档';
```

#### 4.4.7 统计库

```sql
CREATE DATABASE IF NOT EXISTS `shortlink_stats`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `shortlink_stats`;

CREATE TABLE `t_stats_link_daily` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `short_code`   VARCHAR(16)     COLLATE utf8mb4_bin NOT NULL,
  `gid`          CHAR(26)        NOT NULL DEFAULT '',
  `stat_date`    DATE            NOT NULL,
  `pv`           BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '访问次数',
  `uv`           BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '去重访客(HLL估算)',
  `uv_exact`     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '精确UV(CH uniqExact)，T+1 回填',
  `ip_cnt`       BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '去重IP数',
  `new_uv`       BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '新访客数',
  `region_top`   JSON            DEFAULT NULL            COMMENT 'Top省份分布',
  `referer_top`  JSON            DEFAULT NULL            COMMENT 'Top来源域',
  `device_json`  JSON            DEFAULT NULL            COMMENT '设备/OS/浏览器分布',
  `create_time`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_date` (`short_code`, `stat_date`),
  KEY `idx_date_pv` (`stat_date`, `pv`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='短链日聚合';

CREATE TABLE `t_stats_overview_daily` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `stat_date`   DATE            NOT NULL,
  `group_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '0=全局',
  `link_cnt`    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '当日有访问的短链数',
  `pv`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `uv`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_group` (`stat_date`, `group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='大盘日汇总';
```

### 4.5 Redis Key 设计

统一前缀 `sl:`，所有 key 必须带 TTL（除黑名单 / 布隆位图 / 全局版本号）。

| Key 模式 | 类型 | TTL | 用途 | 写入方 |
|---|---|---|---|---|
| `sl:link:{code}` | Hash | 24h + 0~10% 抖动 | 短链核心元数据，6 个字段，契约见 4.5.1 | admin / 预热 |
| `sl:click:max:{code}` | String | 7d | 限流型短链的已访问计数（`INCR`），只有配了 `maxClick` 的短链才产生 | redirect |
| ~~`sl:link:null:{code}`~~ | — | — | **已移除**：读链路没有 DB 要保护，空值 key 与主 key 是同一次 RTT，省不了请求（见 5.4.4） | — |
| `sl:bloom:code` | String(Bitmap) | 永久（扩容时双写） | 短码布隆过滤器 | admin |
| `sl:bloom:ip` | String(Bitmap) | 永久 | 黑名单 IP 布隆 | admin |
| `sl:black:ip` | Set | 永久 | 精确黑名单（布隆误判时二次确认） | admin |
| `sl:black:domain` | Set | 永久 | 恶意长链域名黑名单 | admin |
| `sl:rate:ip:{ip}:{yyyyMMddHHmm}` | String | 120s | IP 分钟级计数（网关限流） | gateway |
| `sl:rate:code:{code}:{yyyyMMddHHmmss}` | String | 5s | 单码秒级计数（热点保护） | redirect |
| `sl:pv:{code}:{yyyyMMdd}` | String | 8d | 实时 PV 计数（看板秒级） | stats-consumer |
| `sl:uv:{code}:{yyyyMMdd}` | HyperLogLog | 8d | 实时 UV 估算 | stats-consumer |
| `sl:pv:global:{yyyyMMdd}` | String | 8d | 全局 PV | stats-consumer |
| `sl:seg:{bizTag}` | String | 5min | 当前号段（发号器兜底，防 DB 抖动） | admin |
| `sl:lock:{biz}:{id}` | String | 30s | 分布式锁（Redisson） | admin |
| `sl:idem:{key}` | String | 24h | 接口幂等令牌 | admin |
| `sl:cache:ver` | String | 永久 | 全局缓存版本号，丢弃 MQ 时的兜底 | admin |
| `sl:cache:ready` | String | 永久 | 缓存就绪标志：存在才允许把"未命中"判成 404（见 5.4.5） | admin / rebuild |

**编码约定**：key 用 `:` 分隔、全小写，一律由 `CacheKeys` 生成（禁止业务代码拼字符串，否则"写得进去查不出来"）。
value 按类型分工：**短链元数据用 Hash**（可单字段更新、只取需要的字段）、**计数与版本用 String**（`INCR`）、**黑名单用 Set**。
数字一律存字符串，时间一律存 epoch 毫秒。禁止大 key（单 value < 10KB，集合 < 5000）。

> 早期版本此处写的是"value 统一 JSON（带 `_v` 版本字段便于灰度）"，实现时改成了 Hash + 字段级读写：
> JSON 需要整体替换，改一个字段就要"读-改-写"，并发下容易互相覆盖；Hash 用 `HSET` 只动目标字段。
> 代价是字段名与顺序成了隐式契约，必须靠 4.5.1 的三处同步约定守住。

#### 4.5.1 短链 Hash 的字段契约

| 字段 | 编码 | 语义 |
|---|---|---|
| `longUrl` | 原样，含 query | 目标地址。必须以 `http(s)://` 开头，否则读侧判为数据损坏 → 503 |
| `status` | `"1"` 启用 | **只有 1 是启用**，其它值一律按停用处理（fail-closed）→ 403 |
| `expireTime` | epoch 毫秒；`"0"` = 永久 | 用毫秒而非日期字符串：避开时区问题与解析开销 |
| `maxClick` | 整数；`0` = 不限 | 只有 `> 0` 时读侧才多付一次 `INCR` |
| `domainId` | 整数 | redirect 不使用，保留用于排障 |
| `version` | 整数，从 1 开始 | **写入版本号**，只有更大的版本才能覆盖（Lua CAS）。缺失按 0 处理——加该字段之前的历史数据没有它 |
| `shortCode` | 短码 | admin 写入，**读侧刻意不读**——它已经知道短码，多取一个字段只是浪费带宽 |

> **三处必须同时改**：admin 的 `LinkCacheWriter`（写）、`RedisLinkCache.FIELDS`（读的字段与顺序）、`LinkSnapshot.parse`（解析顺序）。
> 漏改任何一处，表现都是"部分短链 404"或"503 数据损坏"，且只影响改动之后新写入的那批，极难排查。
> 读侧对前五个字段**全部严格校验、不给默认值**：缺字段说明契约已经不一致，必须暴露而不是猜。
> `version` 是唯一例外：缺省按 0 处理，否则上线那一刻所有老缓存都会变成"数据损坏 503"。

### 4.6 Kafka Topic 设计

| Topic | 分区 | 副本 | Key | 保留 | 生产方 | 消费方 |
|---|---|---|---|---|---|---|
| `sl-click` | 32 | 2 | `short_code` | 3d | redirect | stats-consumer |
| `sl-link-change` | 16 | 2 | `short_code` | 7d | Canal | admin（索引更新） |
| `sl-click-dlt` | 8 | 2 | `short_code` | 7d | stats-consumer | 人工/补偿 job |

> **缓存同步不走 Kafka**：V2 实现时改用 Redis Pub/Sub（频道 `sl:cache-sync`），
> 理由见 5.3.3。Kafka 只承载需要分区有序与回放能力的点击流。

**广播消费怎么实现**：Kafka 没有 RocketMQ 的广播模式，让每个 redirect 实例使用**唯一 groupId**（`redirect-{ip}-{pid}`，启动时生成），并通过 `assign()` 消费全部分区——这样每个实例都能收到全量消息。缺点是 offset 提交无意义，改成 `enable.auto.commit=false` + 定期 seek 到最新，或直接不提交（重启从头消费最后 1 小时数据，正好当预热）。**这个细节面试官很爱问，答得出来就是加分项。**

### 4.7 ClickHouse DDL

```sql
CREATE DATABASE IF NOT EXISTS shortlink;

CREATE TABLE shortlink.link_click_detail
(
    event_time   DateTime64(3, 'Asia/Shanghai')       COMMENT '事件时间(服务端)',
    short_code   LowCardinality(String)               COMMENT '短码',
    gid          String                               COMMENT '业务ID',
    ip_hash      UInt64                               COMMENT 'xxhash64(ip+salt)，不存明文',
    country      LowCardinality(String) DEFAULT '',
    province     LowCardinality(String) DEFAULT '',
    city         LowCardinality(String) DEFAULT '',
    device       LowCardinality(String) DEFAULT ''    COMMENT 'PC/MOBILE/TABLET',
    os           LowCardinality(String) DEFAULT '',
    browser      LowCardinality(String) DEFAULT '',
    referer      String DEFAULT '',
    user_agent   String DEFAULT '',
    trace_id     String DEFAULT '',
    is_bot       UInt8 DEFAULT 0                      COMMENT '爬虫标记，统计时剔除'
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(event_time)
ORDER BY (short_code, event_time)
TTL event_time + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- 日聚合物化视图：AggregatingMergeTree + State/Merge 组合，才支持增量合并
CREATE MATERIALIZED VIEW shortlink.mv_link_daily
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(stat_date)
ORDER BY (stat_date, short_code)
AS
SELECT
    toDate(event_time)        AS stat_date,
    short_code,
    countState()              AS pv_state,
    uniqState(ip_hash)        AS uv_state
FROM shortlink.link_click_detail
WHERE is_bot = 0
GROUP BY stat_date, short_code;

-- 查询时用 Merge 后缀
-- SELECT stat_date, short_code, countMerge(pv_state) pv, uniqMerge(uv_state) uv
-- FROM shortlink.mv_link_daily
-- WHERE stat_date = today() - 1 GROUP BY stat_date, short_code;
```

> `SummingMergeTree` 不能存 `uniqExact` 这种非可加状态，必须用 `AggregatingMergeTree` + `uniqState/uniqMerge`。这是 CH 最常见的踩坑点，写进文档。

---

## 5. 核心设计

### 5.1 短码生成

#### 5.1.1 方案对比

| 方案 | 实现 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 自增 ID → Base62 | DB 自增直接转 62 进制 | 最简单 | **可被顺序遍历**，暴露业务量 | ❌ |
| B. 随机 7 位 + 唯一索引重试 | 随机生成，撞库重试 | 不可枚举 | 高水位时冲突率上升、重试放大 | 仅作兜底 |
| C. 号段 + FPE 混淆 + Base62 | 号段发号 → 格式保留加密 → 编码 | 不可枚举、无冲突、无重试、可反解 | 实现稍复杂 | ✅ **采用** |

#### 5.1.2 短码空间计算

- Base62 字母表：`0-9 A-Z a-z`（62 个字符，**注意大小写敏感，做短码比较时必须区分**）
- 7 位容量：`62^7 = 3,521,614,606,208`（约 3.52 万亿）
- 6 位容量：`62^6 = 56,800,235,584`（约 568 亿）
- 选择 **7 位**：容量充裕，且便于后续缩短到 6 位（反向兼容）

**关键技巧**：号段初始值设为 `62^6 = 56,800,235,584`，使所有号的编码结果**天然是 7 位**，无需补零。补零会产生 `000000a` 这种丑陋且泄露“系统刚上线”的短码。

#### 5.1.3 FPE 混淆（核心算法）

目标：把连续的发号 `x ∈ [0, D)` 映射为看似随机的 `y ∈ [0, D)`，且**一一对应、可逆**。

- `D = 62^7 - 62^6 = 3,464,814,370,624`（约 3.46 万亿个可用码）
- 算法：**Feistel 网络 + 循环行走（cycle-walking）**

```
encode(segmentId):                 // segmentId ∈ [62^6, 62^7)，即号段发出来的原始号
  x = segmentId - 62^6             // 平移到 [0, D)
  y = Feistel42(x)                 // 42-bit 域上的 4 轮 Feistel，双射
  while y >= D:                    // 循环行走：落在非法区间就再加密一次
      y = Feistel42(y)
  return base62(y + 62^6)          // 输出恰好 7 位

Feistel42(x):                      // 21/21 位分组，4 轮
  L = x >>> 21; R = x & 0x1FFFFF
  for r in 0..3:
      L, R = R, L ^ F(R, r)
  return (L << 21) | R

F(R, r) = SipHash24(key, r ‖ R) & 0x1FFFFF     // 轮函数，无需可逆
```

**性质与验证**

| 性质 | 说明 |
|---|---|
| 双射 | Feistel 结构保证；循环行走保持双射（只重试不丢弃） |
| 不可枚举 | 相邻 x 的输出无相关性，遍历者拿不到规律 |
| 可逆 | 解密时逆序执行 `L, R = R ^ F(L, r), L`，用于“短码反解为号段”（排查/对账用） |
| 期望迭代 | `1 / (D / 2^42) ≈ 1.27` 次，性能可忽略 |
| 密钥 | 配置中心下发，泄露即等于短码可预测，属于敏感配置 |

> 工业级替代：NIST SP 800-38G 的 **FF1/FF3-1** 格式保留加密，支持任意进制字母表。简历项目用 Feistel 足够，但**知道 FF1 的存在**是区分度。

#### 5.1.4 发号器（双 Buffer 号段）

```java
// 伪代码
class SegmentIdGenerator {
    volatile Segment current;
    volatile Segment next;        // 预取，避免取号时同步等 DB

    long nextId() {
        if (current.remaining() < current.step * 0.2) {   // 剩余 20% 触发异步预取
            asyncLoadNext();
        }
        if (current.isExhausted()) {
            rotate();              // current = next，next = null
            if (current == null) loadSync();  // 极端情况同步兜底
        }
        return current.next();
    }
}
```

| 关注点 | 设计 |
|---|---|
| 并发 | `AtomicLong` 自增，无锁 |
| DB 抖动 | 剩余 20% 时异步预取，一次 DB 故障不影响发号 |
| 重启浪费 | 每次重启丢弃当前号段剩余号（浪费 < 1000 个），可接受 |
| 多实例 | 各实例独立取段，互不干扰，号段不连续但**短码经 FPE 后本就无规律** |
| step 选择 | 1000；日新增 10 万时约 100 次 DB 交互/天 |

### 5.2 分库分表策略

#### 5.2.1 阶段化决策（重要）

| 阶段 | 数据量 | 方案 | 理由 |
|---|---|---|---|
| V1~V3 | < 1000 万 | **单库单表** | 千万级 + 覆盖索引，MySQL 毫无压力；分片会引入跨片查询、分布式事务、运维复杂度，**过早分片是负分** |
| V4 | 1000 万 ~ 1 亿 | 2 库 × 8 表，按 `crc32(short_code) % 16` | 单表 600 万，写入/查询仍线性 |
| V5 | > 1 亿 | 4 库 × 16 表 + 冷热分离 | 归档 1 年前无访问短链到冷库 |

**简历上怎么写**：不要写“我做了分库分表”，写“**基于数据量评估做了分片方案设计与压测验证，V1 单表支撑 800 万数据 P99 < 8ms，预留 ShardingSphere 配置可按 short_code 水平扩展**”。后者体现判断力，前者容易被追问“为什么一开始就分”。

#### 5.2.2 分片键选择

| 候选键 | 读路径 | 写路径 | 管理端列表 | 结论 |
|---|---|---|---|---|
| `short_code` | ✅ O(1) 定位 | ✅ 均匀 | ❌ 按分组查需扫全部分片 | ✅ **主选** |
| `group_id` | ❌ 需二次查询 | ⚠️ 大客户倾斜 | ✅ 单分片 | 备选（分组内短链多时） |
| `creator_id` | ❌ | ⚠️ | ✅ | ❌ |

选 `short_code` 的决定性理由：**redirect 不查 MySQL，但 admin 的编辑/删除/详情全部按短码查**，这是最高频的 DB 访问模式。

#### 5.2.3 跨片查询怎么解

按 `short_code` 分片后，“某分组下的短链列表”会广播到 16 个分片再归并，深分页性能极差。三种解法：

1. **管理端列表走 ES**（Canal 同步 binlog）——最正统，但引入新组件
2. **冗余一张 `t_group_link_idx(group_id, short_code, gid, title, create_time)` 按 group_id 分片**——双写，读走索引表，详情回主表。个人项目推荐这个，成本低、可讲“CQRS 读写分离”
3. **限制分组规模 + 游标分页**（`WHERE group_id=? AND id < ?`），避免 `LIMIT 100000,20`

文档默认选 2，简历上表述为“**基于 CQRS 思想为管理端建立分组维度索引表，规避分片后的跨片深分页**”。

#### 5.2.4 分片后必须重估的唯一约束

`uk_short_code` 在分片后只在单表内生效。必须保证：
- 发号器 + FPE 是**单射**（理论上保证），
- 加监控：定期抽样校验短码唯一性，
- 兜底：插入冲突时重新取号（不会发生，但代码要有这条分支）。

### 5.3 多级缓存与一致性

#### 5.3.1 缓存层次

```
请求 → [L1 Caffeine 本地缓存] 命中率 96%+
            ↓ miss
       [Redis 集群] 命中率 3.5%
            ↓ miss
       [返回 404 / 触发异步回源] 0.5%
```

| 层 | 容量 | TTL | 淘汰 | 说明 |
|---|---|---|---|---|
| L1 Caffeine | 10 万条 / 堆内约 80MB | 10min + 随机 0~120s | W-TinyLFU | 只存热点，`maximumSize` 按堆 1/8 估算 |
| L2 Redis | 全量（或近 90 天活跃） | 24h + 随机抖动 | allkeys-lru | `maxmemory-policy allkeys-lru` |

#### 5.3.2 缓存三件套

| 问题 | 现象 | 方案 |
|---|---|---|
| **穿透** | 大量不存在的短码打到 Redis（读侧后面没有 DB） | ① 短码格式校验前置，非法直接 404、零 RTT ② 本地限流（V4）③ 布隆过滤器只用于 admin 侧批量导入预检，不进读链路（见 5.4.4）；误判率 0.1%，n=1000 万 → 约 18MB，n=1 亿 → 约 180MB |
| **击穿** | 单个热点 key 过期瞬间并发回源 | ① 逻辑过期：value 内嵌 `expireAt`，读到过期值先返回旧值 + 异步刷新 ② singleflight 合并并发回源（Guava `Striped` 或手写 `ConcurrentHashMap<code, Future>`） |
| **雪崩** | 大批 key 同时过期 / Redis 整体挂 | ① TTL 加随机抖动 ② 本地缓存兜底（Redis 挂了仍有 ~95% 命中，但只维持一个 TTL 周期）③ 50ms 超时快速失败，绝不挂住事件循环（未做熔断，见 5.4.5 与第 10 节） |

**布隆过滤器的两个坑**（面试高频）：
1. **不支持删除**。短链有删除/封禁操作，布隆无法移除 → 只增不删：删除/封禁时不清布隆位，宁可误判为"可能存在"。读侧不用布隆（见 5.4.4），所以这个误判不会变成线上 404，只影响 admin 侧预检的准确率。
2. **扩容**。固定位图无法扩容 → 用**可扩展布隆**（分层：每层容量翻倍，查询查所有层）或定期重建（双写新旧两份，切换后删旧）。

#### 5.3.3 一致性方案（写路径，V2 已实现）

```
admin 写请求
   │
   ├─ 1. 校验 + 幂等检查
   ├─ 2. 写 MySQL（事务提交）
   ├─ 3. INCR sl:cache:ver                    ← 拿到本次写入的版本号
   ├─ 4. Lua CAS 写 Redis Hash（只有版本更大才覆盖；失败重试 3 次）
   ├─ 5. PUBLISH sl:cache-sync {shortCode}
   └─ 6. （job 侧同理：DEL + INCR + PUBLISH，按批 pipeline）
                │
                ▼
      redirect 各实例收到（Redis Pub/Sub 天然广播）
                │
                └─ 7. 失效本地 Caffeine 中该短码
```

**为什么版本号要参与缓存写入**：DB 层两个并发写是"后提交的赢"，但两个 `HSET` 到达 Redis
的顺序可能与提交顺序相反——不做比较的话 Redis 会**永久**停在旧值（直到下一次写或 TTL）。
版本号来自同一个单调计数器，Lua 里"比较 + 写入 + 续期"原子完成，只有更大的版本能覆盖。

> **实现修正**：原设计用 Kafka `sl-cache-sync` 做广播，落地时改成了 **Redis Pub/Sub**。
> 理由：缓存失效是"通知"而不是"数据流"，不需要回放/累积/分区有序；
> 而 Kafka 没有原生广播，要给每个实例分配独立 groupId 再 `assign()` 全分区才能模拟出来。
> Pub/Sub 的 at-most-once 缺口由版本号轮询兜底。
> 详见 `common-cache` 的 `CacheChannels`。

**为什么是"更新 Redis"而不是"删除 Redis"？**
删除会让下一个请求穿透到 DB，而 redirect 根本连不上 DB——它只能拿到空值然后 404。所以 redirect 场景必须**写时更新**，不是 Cache Aside 的删除。这是本项目和普通缓存场景最大的不同，**一定要讲出来**。

**消息语义：失效（evict）而不是推值（push payload）**

推值能省一次 Redis GET，但要求消息有序 + 版本比较（多实例并发写时顺序无保证），
且 payload 结构要与 `LinkSnapshot` 双份维护。失效只需一个短码，**无需版本、无顺序问题**；
代价是写操作后第一次访问多一次 Redis GET——写频率极低，可以忽略。

**一致性等级**

| 环节 | 延迟 | 兜底 |
|---|---|---|
| MySQL → Redis | 同步（同事务后） | 失败重试 3 次 + 指标告警；最终靠重建接口恢复 |
| Redis → 本地缓存 | Pub/Sub 广播，毫秒级 | 全局版本号 `sl:cache:ver` 每秒对账，**发现版本增量与实收消息数不符**才清空本地缓存 |
| Pub/Sub 丢消息 | — | 版本号对账 ≤2s 自愈（含一轮观察期） |
| 极端不一致 | ≤2s | 可接受：短链改长链不是资金业务，**明确写出"接受秒级最终一致"比假装强一致更专业** |

**版本号兜底机制**（防止 Pub/Sub 丢消息导致本地缓存永久脏）：

每次写操作都让版本号 +1，并与一条失效消息**1:1 配对**：

```
写：   INCR sl:cache:ver  →  Lua CAS 写 Hash  →  PUBLISH sl:cache-sync {code}
轮询：每 1s（启动时加随机延迟，避免多实例同相位）
        expected = 版本增量 + 上一轮未补齐的缺口
        actual   = 这一轮实际收到的失效消息数
        actual < expected → 先观察一轮（INCR 早于 PUBLISH，消息可能还在路上）
                            连续两轮都补不上 → 判定真丢 → invalidateAll()
```

**为什么不是"版本一变就全清"**：早期实现就是那样，后果是每次正常写入都会让所有实例清空 10 万条 L1——精确失效被全清盖掉，命中率随写流量下降，批量导入更是灾难。

现在正常写入**零代价**，只有真丢消息（实例重启、订阅断连）才全清；代价是发现时间从 1s 变成约 2s。判断逻辑抽在 `VersionGapTracker` 里，可脱离 Redis 单测。

> 运维含义：`shortlink_cache_evict_total{source="version"}` 现在只在真丢消息时增长，可以放心拿它告警；改造前它会被正常写入刷高，完全失去信号价值。

**写入口不止 admin**：job 清理过期短链时直接删 Redis，同样要 `DEL + INCR + PUBLISH` 三件套，
否则 L1 会继续命中已下线的短链。清理按批走一次 pipeline，一批只付一次往返。

> 完整的写链路步骤、失败模式矩阵与"为什么顺序不能乱"见 [写链路工作流程](./write-path.md)。

**缓存可恢复性**：Redis 是 redirect 的唯一数据源，重启/FLUSH 就意味着全站链接不可达——若未命中一律判 404，表现出来就是"全站短链都不存在"。
因此 admin 提供 `POST /api/v1/admin/cache/rebuild`：分页扫 MySQL → 重灌 Redis + 布隆 → 置位 `sl:cache:ready` → 发一次"全部失效"。
重建完成前，未命中会被就绪标志降级成 503（见 5.4.5），而不是谎报 404。compose 里 Redis 同时开了 AOF（`appendonly yes`）作为第一道保险。

### 5.4 跳转链路（redirect 核心）

#### 5.4.1 处理流程

```
GET /{code}
  │
  ├─ 1. 短码格式校验（^[0-9A-Za-z]{7}$）—— 非法直接 404，零 RTT
  ├─ 2. L1 Caffeine 查询（可配置开关）
  │      ├─ 命中 → 跳 4
  │      └─ 未命中 ↓
  ├─ 3. Redis HMGET sl:link:{code} [longUrl,status,expireTime,maxClick,domainId]
  │      ├─ 连接/超时错误 → 503（打点 cache_error）
  │      ├─ 全为 null      → 看 sl:cache:ready（本机判定，最多 2s 滞后）
  │      │                    ├─ 在   → 404（这条短码真的不存在）
  │      │                    └─ 缺失 → 503 + 打点 cache_not_ready
  │      └─ 命中 → 回填 L1 → 跳 4
  ├─ 4. 状态判定（纯函数，无副作用）
  │      ├─ status != 1        → 403
  │      ├─ now >= expireTime  → 410
  │      ├─ longUrl 非 http(s) → 503 + corrupt 打点
  │      └─ 通过               → 302
  └─ 5. 异步投递点击事件（V3）
```

**耗时预算**：L1 命中路径 < 1ms（纯内存），Redis 命中路径 < 3ms。**95% 以上的请求不应该碰到第 3 步之后的网络。**

> 注意：布隆过滤器与 Redis 空值缓存已从读链路移除，原因见 [5.4.4](#544-两个被移除的组件实现后的修正)；
> Redis 不可用时的降级与恢复见 5.4.5。

#### 5.4.2 响应细节

```http
HTTP/1.1 302 Found
Location: https://example.com/very/long/path?a=1
Cache-Control: no-store, no-cache, must-revalidate
Pragma: no-cache
Content-Length: 0
X-Trace-Id: 0af7651916cd43dd
```

| 决策 | 原因 |
|---|---|
| 302 而非 301 | 301 被浏览器永久缓存：改不了目标、统计不到点击、封禁无效。**这是短链服务的第一原则** |
| `no-store` | 同上；若追求极致性能可给 `max-age=60`，但要接受 60s 内点击丢失，**这个取舍要显式记录** |
| 空 body | 省一次序列化和几 KB 流量，高 QPS 下很可观 |
| 不重定向到自身 | 防环：跳转前校验长链 host 不等于短链域名 |

#### 5.4.3 点击事件投递（不能拖慢跳转）

```
业务线程 → 有界队列（ArrayBlockingQueue 8192 / Disruptor）
              │ 满
              ├─ 丢弃 + 计数（统计可丢，跳转不可丢）
              ▼
        单线程批量消费 → KafkaProducer.send(async, batch 16KB / linger 20ms)
              │ 失败
              └─ 本地磁盘 spool（可选）或直接丢弃 + 告警
```

| 参数 | 建议值 | 说明 |
|---|---|---|
| 队列容量 | 8192 | 按峰值 QPS × 100ms 估算 |
| batch.size | 16384 | 太小则网络调用多，太大则延迟高 |
| linger.ms | 20 | 牺牲 20ms 换吞吐 |
| acks | 1 | 统计允许丢，不需要 all |
| 压缩 | lz4 | CPU 换带宽 |
| 满队列策略 | 丢弃 + `Counter` 打点 | **绝不允许阻塞业务线程** |

> 可直接用 Disruptor（`LMAX Disruptor 3.4`）替换 ArrayBlockingQueue，无锁 RingBuffer 在 10w QPS 下比 BlockingQueue 少一次上下文切换，是简历上的性能亮点。

#### 5.4.4 两个被移除的组件（实现后的修正）

原设计在读链路上放了布隆过滤器和 Redis 空值缓存。实现时发现两者在"无 DB 的读链路"里都不成立，**已移除**。

| 组件 | 原设想 | 移除原因 |
|---|---|---|
| 布隆过滤器 | 拦截不存在的短码，减少 Redis 访问 | 本地布隆要求"新短码在被访问前已加入过滤器"，而 admin 只能通过 MQ 广播同步。滞后窗口内会出现「Redis 已有、布隆没有」→ **真实短链被判 404**。Redis 版布隆每次判定同样是一次 RTT，本来就没省。假阴性的代价远大于省一次网络 |
| 空值缓存 `sl:link:null:` | 防缓存穿透 | 空值缓存是为保护 DB，redirect 根本没有 DB。且空值 key 与主 key 都是同一次 RTT，省不了请求。防扫描应靠格式校验（零成本）与 V4 的限流 |

**结论**：读链路的防御手段是"格式校验 + 连接超时 + 状态判定"，而不是往缓存层堆组件。布隆过滤器仍保留在 `component-bloom`，但用途改为 admin 侧的批量导入预检（V4）。

**由此产生的取舍**：V1 时 L1 没有主动失效机制，改链/封禁最长要等 TTL（默认 10 分钟）。
**V2 已补齐**：Pub/Sub 实时失效 + 版本号轮询兜底，降到秒级以内（见 5.3.3）。

#### 5.4.5 Redis 不可用时的降级与恢复

redirect 没有 DB，Redis 就是唯一数据源。所以"Redis 挂了怎么办"必须按**四种形态**分别回答，笼统说"降级"是不及格的。

| 形态 | 现象 | 处置 |
|---|---|---|
| **超时 / 抖动**（最常见） | 命令超过 `timeout: 50ms` | 抛 `CacheUnavailableException` → `UNAVAILABLE` → **503**（不是 404，不能骗用户"链接不存在"）；连接池 `max-wait: 50ms`，高并发下快速失败而非堆积 |
| **进程挂 / 主从切换** | 连接断开 | Lettuce 自动重连；订阅端每 5s 重订阅，**断连期间的消息不补发**，由版本号轮询兜底；轮询单次失败只跳过这一轮，绝不终止 Flux（否则抖一次兜底就永久失效） |
| **数据全丢**（FLUSH / 主从空切） | 全部 miss | AOF `everysec` + `POST /admin/cache/rebuild` 从 MySQL 预热；期间未命中由就绪标志降级 503，不再谎报 404 |
| **内存满 / LRU 淘汰** | 热点被逐出 | `maxmemory 512mb` + `allkeys-lru`：短链缓存可从 MySQL 重建，宁可淘汰也不要写失败 |

**降级边界要算准**（不能笼统说"扛得住"）：

- Redis 挂掉后**头 10~12 分钟**，L1 命中的热点短链仍可正常跳转；TTL（10m + 0~2m 抖动）到期后没有新写入，命中率单调衰减到 0，随后全站 503。
- 埋点队列满即丢，只丢统计不影响跳转；黑名单用最后一份 2s 快照，失效方向是"多放行"，不会误封。
- 恢复 SOP：Redis 起（AOF 回放）→ rebuild 全量重灌 + 布隆 + `EVICT_ALL` → 观察 `shortlink.cache.write.failure` 与命中率回升。

**404 与 503 必须能区分：就绪标志 `sl:cache:ready`（已实现）**

问题：Redis 被 FLUSH 或主从空切后所有 key 都不在，未命中若一律判 404，等于给用户和搜索引擎"这条链接不存在"的确定性结论，而且不触发任何错误告警——这是最难被发现的一类事故。

做法：admin 在**全量重建完成后**写入 `sl:cache:ready`；redirect 每 2s 探测一次，热路径只读本地 `volatile` 字段、不查 Redis：

| 标志 | 未命中含义 | 响应 |
|---|---|---|
| 存在 | 缓存数据完整，这条短码真的不存在 | 404 |
| 缺失 | 缓存可能被清空，无法区分 | **503** + `shortlink.cache.not_ready.total` |

标志存在 Redis 里，所以"**进程重启不丢、Redis 被清空就丢**"，语义天然对齐。初始状态是**未就绪**（fail-closed），启动时先同步探一次再放流量，避免启动瞬间的空窗把所有未命中放成 404。

置位时机与运维约定：

1. `POST /api/v1/admin/cache/rebuild` 完成后置位；
2. admin 启动时发现 `t_short_link` 为空（空库没有数据要预热），由启动引导直接置位，否则新部署的环境会把"查不存在的码"错判成服务不可用；
3. **库里有数据但标志缺失 → 绝不置位**，只打一条 WARN。这说明 Redis 被清空过，必须人工重建——自动置位等于把"数据丢了"重新贴上"数据在"的标签。

配置：`shortlink.redirect.ready-gate.enabled`（默认 true；关掉即退回 miss→404 的旧行为，留作灰度回退）、`check-interval`（默认 2s）；admin 侧 `shortlink.admin.cache.ready-bootstrap`。

代价：重建完成前，所有"不存在的短码"都返回 503 而不是 404。这是刻意的取舍——**宁可承认服务不可用，也不要谎报链接不存在**。

### 5.5 统计链路

```
Kafka sl-click (32 分区, key=short_code)
        │
        ▼
stats-consumer（批量 500 条 / 1s / 手动提交 offset）
        │
        ├─ 1. 幂等：eventId 去重（Redis SETNX，TTL 1h）—— Kafka 至少一次语义
        ├─ 2. 解析 UA（UserAgentUtils / 自写正则）、IP 归属地（ip2region 离线库）
        ├─ 3. 写 ClickHouse（JDBC batch insert，异步攒批）
        └─ 4. 更新 Redis 实时指标（INCR pv / PFADD uv），TTL 8 天
                │
                ▼
        stats-api 查询
        ├─ 实时（今天）→ Redis
        └─ 历史（昨天以前）→ ClickHouse 明细 / MySQL 日聚合
```

**UV 三档精度**

| 档位 | 实现 | 误差 | 成本 |
|---|---|---|---|
| 实时估算 | Redis HLL，`PFADD` + `PFCOUNT` | 0.81%（m=16384） | 极低，12KB/key |
| 精确值 | CH `uniqExact(ip_hash)` | 0 | 查询时算，亿级秒出 |
| 历史固化 | 日聚合表 `uv_exact` T+1 回填 | 0 | 一次离线任务 |

**为什么 PV/UV 要分“实时 Redis + 离线 CH”两套？** 实时性要求秒级、成本要低，HLL 合适；准确性和多维分析（按省份/设备/来源）必须明细，CH 合适。**一套存储满足不了两种诉求，这是典型的读写分离按场景选型。**

**幂等细节**：Kafka 至少一次投递，重复消息会导致 PV 虚高。用 `eventId = shortCode + ipHash + timestamp(ms)` 做去重，Redis `SETNX` TTL 1h 覆盖重放窗口。注意**去重本身有成本**（每条一次 Redis 写），10w QPS 下这是瓶颈——优化方案：布隆过滤器预判 + 本地 Caffeine 去重（1 分钟窗口），Redis 只做跨实例兜底。

### 5.6 限流与黑名单

#### 5.6.1 分层限流

| 层 | 维度 | 算法 | 阈值（示例） | 目的 | 状态 |
|---|---|---|---|---|---|
| 网关 | IP | Redis + Lua 固定窗口 | 600 次/分钟 | 防刷、防爬 | ✅ V4 已实现 |
| 网关 | 用户 | 令牌桶 | 100 QPS | 防单个应用打爆 | ⬜ |
| redirect | 实例总入口 | 本地令牌桶 | 10w QPS | 保护本机容量，不依赖网络 | ✅ V4 已实现 |
| redirect | 单短码 | 本地令牌桶 | 500 次/秒 | 热点爆款保护 | ✅ V4 已实现 |
| ~~redirect~~ | ~~单 IP+短码~~ | — | — | — | ❌ 主动放弃，见下 |

**为什么 redirect 用本地限流而不是 Redis？**
限流本身要是一次 Redis 调用，等于给每个请求加 1ms 网络开销，且 Redis 挂了限流失效。本地令牌桶零网络开销、天然高可用，代价是**集群维度不精确**（N 个实例 = N 倍阈值）。对“保护本机容量”这个目标，本地限流是正确工具；集群精确限流交给网关。**说清这个分工，比堆 Sentinel 更有说服力。**

**为什么 redirect 不做 IP 维度**：
redirect 直接面向公网，前面没有可信代理时只能拿到 remote address；按 IP 限流会让 NAT 后面的用户互相影响（公司/学校几十人共用一个出口 IP）。
而信任 `X-Forwarded-For` 在没有可信代理时等于把限流 key 交给攻击者控制——改个头就绕过。
**IP 维度的防刷放在网关做**（那里是管理面入口，且可以配置可信代理），redirect 只做容量与热点保护。

#### 5.6.2 限流 Lua（网关用，V4 已实现）

```lua
-- KEYS[1]=限流 key  ARGV[1]=阈值  ARGV[2]=窗口秒数
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
end
if current > tonumber(ARGV[1]) then
    return 0
end
return 1
```

**三个实现要点**

| 点 | 做法 | 原因 |
|---|---|---|
| key 必须对齐窗口 | `sl:rate:ip:{ip}:{epochSecond/windowSeconds}` | 用时间戳当 key 的话每次请求都是新 key，限流永远不触发 |
| 单次 Lua 完成读改写 | `INCR + EXPIRE + 判断` 一次往返 | 分成 GET 再 SET 会有竞态：两个并发请求都读到 599 然后都放行 |
| Redis 故障时**放行**（fail-open） | catch 异常后直接 `chain.filter` + 打点 | 网关是唯一入口，限流组件挂掉不该让全站不可用；反过来一次 Redis 抖动会演变成全站 429 |

> 固定窗口的临界问题依然存在：窗口交界处可比阈值多打一倍流量。
> 在"挡住刷接口"这个目标下可以接受；要更平滑得换滑动窗口（ZSet，内存成本高）或令牌桶。
> 这个取舍写在这里，面试被问到能直接答。

**执行顺序**：限流（-100）→ 鉴权（-90）→ 路由。
限流放在验签之前，一是便宜（一次 Redis 调用 vs HMAC 计算），二是未认证的洪水也应该被限流挡住。


#### 5.6.3 黑名单

| 类型 | 存储 | 同步方式 | 生效延迟 |
|---|---|---|---|
| 恶意 IP | Redis Set `sl:black:ip` | 各实例每 2s 拉一次全量快照 | ≤ 2s ✅ 已实现 |
| 恶意域名 | Redis Set | 同上 | ≤ 2s ⬜ |
| 恶意长链（钓鱼） | MySQL + Redis | 写入即同步 | 实时 ⬜ |
| 短码封禁 | `t_short_link.status=0` | 走正常缓存更新链路 | < 1s ✅ |

**为什么是"定时拉全量"而不是"每请求查 Redis"**：
黑名单判定发生在**每个请求**上。若每请求一次 `SISMEMBER`，等于给主链路固定加一次网络往返；而黑名单通常只有几十到几百条，整份拉进内存后判定就是 `Set#contains`，零开销。
代价是生效延迟 = 刷新间隔（2s），对"封禁一个 IP"完全够用。

**拉取失败时保留旧快照，绝不清空**：清空的后果是"Redis 抖一下，所有黑名单瞬间失效"，
比数据旧一点危险得多。

**客户端 IP 怎么取**：网关默认只认 remote address，不信任 `X-Forwarded-For`
（网关直接暴露时 XFF 由客户端控制，信任它等于把黑名单交给攻击者）。
接入可信 LB 后才打开 `shortlink.gateway.trust-forwarded-for`。
网关解析出的 IP 通过 `X-Client-Ip` 透传给下游（覆盖写入，客户端伪造的会被剥离）。

**风控校验（创建时，这是短链服务的真实痛点）**

| 校验项 | 规则 |
|---|---|
| 协议白名单 | 仅 `http://` / `https://`；拦截 `javascript:`、`data:`、`file:` |
| SSRF | 解析域名 IP，拒绝内网段（10/8、172.16/12、192.168/16、127/8、169.254/16、::1） |
| 域名黑名单 | 已知钓鱼/赌博域名库（可接第三方 API，简历项目用静态表即可） |
| 长度 | 长链 ≤ 2048，短码域名 ≤ 128 |
| 自跳转 | 长链 host 不能等于短链域名（防环） |
| 频率 | 单用户创建 ≤ 1000/天 |

> 这一节是**区别于教程项目的关键**。绝大多数短链项目只做“生成 + 跳转”，完全不考虑被滥用的风险。面试时主动提“短链本质是开放重定向，天然有被用于钓鱼的风险”，直接拉开差距。

### 5.7 幂等设计

| 场景 | 方案 | 说明 | 状态 |
|---|---|---|---|
| 创建短链 | `Idempotency-Key` 请求头 + Redis `SETNX` 24h | 重复请求返回首次结果（缓存 response） | ✅ V4 |
| 业务去重 | `(group_id, long_url_hash)` 查询 | 同分组同长链默认返回已有短码 | ✅ V1 |
| 批量导入 | 逐条复用单条创建 | 单条失败不影响其他条，返回下标与原因 | ✅ V4 |
| 点击消费 | `eventId` 去重 | 见 5.5 | ⬜ V3 |
| 缓存同步 | 消息带 `version`，小于本地版本则丢弃 | 防乱序导致旧值覆盖新值 | ⬜ V3 |

**幂等的三个必须做对的点（V4 实现时踩全了）**

1. **先占位再执行**。若先跑业务再写标记，两个并发请求都会穿过去，幂等等于没做。
2. **执行失败必须释放占位**。否则用户重试永远拿到"处理中"，一次失败就锁死了这个 key。
3. **并发重复要等待，而不是立刻失败**。第一个请求还在跑时，第二个请求轮询等结果（默认 3s），
   拿到就返回，等不到才 409。直接返回 409 对调用方毫无帮助——它真正想要的是"第一次的结果"。

> 轮询用的是 `Thread.sleep`（阻塞 Tomcat 线程）而不是异步：admin 的写 QPS 是个位数，
> 为了省这几个线程把整个接口改成响应式并不划算。**在正确的场景做正确的取舍**。

**版本号防乱序**这个点很重要：Kafka 同 key 有序，但广播消费 + 多实例重试仍可能乱序，`version` 比较是最低成本的保护。

---

## 6. 接口设计

### 6.1 通用约定

| 项 | 约定 |
|---|---|
| 风格 | RESTful，`/api/v1/**` |
| 认证 | `Authorization: Bearer <JWT>`（网关校验，透传 `X-User-Id`） |
| 追踪 | `X-Trace-Id`，网关生成，全链路透传（含 Kafka header） |
| 响应 | `{"code":0,"msg":"ok","data":{},"traceId":"..."}` |
| 错误码 | 见 6.4 |
| 分页 | 游标分页 `cursor` + `size`（避免深分页），管理端兼容 `page` |
| 时间 | ISO-8601 带时区，如 `2026-10-24T11:00:00+08:00` |

### 6.2 管理端 API（gateway → admin）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/links` | 创建短链（幂等） |
| POST | `/api/v1/links/batch` | 批量导入（JSON 数组，≤500 条，逐条返回失败原因） |
| GET | `/api/v1/links/{gid}` | 详情 |
| GET | `/api/v1/links` | 列表（按 group/时间/状态，游标分页） |
| PUT | `/api/v1/links/{gid}` | 修改（长链/标题/过期时间/状态） |
| DELETE | `/api/v1/links/{gid}` | 删除（软删） |
| POST | `/api/v1/links/{gid}/disable` | 封禁 |
| GET | `/api/v1/links/{gid}/qrcode` | 二维码（可选，演示加分） |
| POST | `/api/v1/groups` | 创建分组 |
| GET | `/api/v1/groups` | 分组列表 |
| POST | `/api/v1/domains` | 新增域名 |
| POST | `/api/v1/blacklist/ip` | 拉黑 IP |
| GET | `/api/v1/tasks/{taskNo}` | 批量任务进度 |

**创建短链**

```http
POST /api/v1/links
Authorization: Bearer eyJ...
Idempotency-Key: 8f14e45f-ea6b-4c1a-9c3d-2b7e5f0a1c22
Content-Type: application/json

{
  "longUrl": "https://example.com/path/to/campaign?utm_source=wechat",
  "groupId": 12,
  "domainId": 1,
  "title": "双11主会场",
  "expireTime": "2026-11-12T00:00:00+08:00",
  "maxClick": 0
}
```

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "gid": "01JCTX3K9M2Q7V8N5R4T6B0W1A",
    "shortCode": "aB3xK9z",
    "shortUrl": "https://s.example.com/aB3xK9z",
    "longUrl": "https://example.com/path/to/campaign?utm_source=wechat",
    "expireTime": "2026-11-12T00:00:00+08:00",
    "createTime": "2026-10-24T11:00:00+08:00"
  },
  "traceId": "0af7651916cd43dd"
}
```

### 6.3 跳转 API（redirect 独立域名）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/{code}` | 302 跳转 |
| GET | `/actuator/health` | 健康检查（K8s/LB 用） |

**错误响应**（跳转失败时返回 HTML 错误页，不做 JSON，因为面对的是浏览器）

| 状态码 | 场景 | 响应 |
|---|---|---|
| 302 | 正常 | `Location: <longUrl>` |
| 404 | 短码不存在 / 格式非法 | 静态错误页 |
| 403 | 已封禁 / 超最大点击 / IP 黑名单 | 静态错误页 |
| 410 | 已过期 | 静态错误页 |
| 429 | 触发限流 | 静态错误页 |
| 503 | 服务降级 | 静态错误页 |

### 6.4 错误码

| code | HTTP | 含义 |
|---|---|---|
| 0 | 200 | 成功 |
| 40001 | 400 | 参数校验失败 |
| 40002 | 400 | 长链非法（协议/长度/SSRF） |
| 40101 | 401 | 未认证 / Token 过期 |
| 40301 | 403 | 无权限 |
| 40401 | 404 | 短链不存在 |
| 40901 | 409 | 短链已存在（幂等冲突，返回已有记录） |
| 41001 | 410 | 短链已过期 |
| 41002 | 410 | 短链已禁用 |
| 42901 | 429 | 触发限流 |
| 50001 | 500 | 系统异常 |
| 50301 | 503 | 依赖服务不可用（降级中） |

### 6.5 统计 API（stats-api）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/stats/links/{code}?from=&to=&granularity=day\|hour` | 单链趋势 |
| GET | `/api/v1/stats/links/{code}/top?dim=province\|referer\|device&date=` | 维度 TopN |
| GET | `/api/v1/stats/overview?from=&to=` | 大盘 |
| GET | `/api/v1/stats/realtime/{code}` | 实时 PV/UV（读 Redis） |

```json
{
  "code": 0,
  "data": {
    "shortCode": "aB3xK9z",
    "total": { "pv": 125830, "uv": 42310 },
    "series": [
      { "date": "2026-10-22", "pv": 48210, "uv": 18320 },
      { "date": "2026-10-23", "pv": 50320, "uv": 19110 },
      { "date": "2026-10-24", "pv": 27300, "uv": 10480 }
    ],
    "granularity": "day"
  }
}
```

---

## 7. 非功能设计

### 7.1 可观测性

| 维度 | 实现 | 关键指标 |
|---|---|---|
| 链路追踪 | Micrometer Tracing + OTel，TraceId 透传 HTTP/Feign/Kafka Header | 慢请求 TopN、跨服务耗时 |
| 指标 | Micrometer → Prometheus → Grafana | QPS、P50/P99、缓存命中率、Kafka lag、CH 写入延迟、限流拒绝数、队列丢弃数 |
| 日志 | Logback 异步 Appender，JSON 格式 | redirect **采样 1%**（全量打日志会拖垮性能），admin 全量 |
| 告警 | Prometheus AlertManager（或 Grafana Alert） | 缓存命中率 < 90%、Kafka lag > 10w、CH 写入失败、5xx 率 > 0.1% |

**自定义指标清单**（按服务分组，与代码一一对应；Prometheus 侧 `.` 会被转成 `_`）

```
gateway
  shortlink_gateway_ratelimit_rejected
  shortlink_gateway_ratelimit_error
  shortlink_gateway_blacklist_blocked
redirect
  shortlink_redirect_total{result="found|invalid|not_found|disabled|expired|quota_exceeded|unavailable"}
  shortlink_cache_l1_total{result="hit|miss"}              # 命中率 = hit / (hit + miss)
  shortlink_cache_evict_total{source="pubsub|evict_all|version"}
  shortlink_cache_error_total
  shortlink_cache_not_ready_total
  shortlink_redirect_corrupt_total
  shortlink_redirect_ratelimit_rejected
  shortlink_click_tracked{result="published|dropped|failed"}
  shortlink_redirect_latency{quantile="0.5|0.95|0.99"}
admin
  shortlink_cache_write_failure_total
stats-consumer
  shortlink_click_processed{result="ok|duplicate"}
  shortlink_click_written
  shortlink_click_write_failed
  shortlink_click_buffer_rejected
job
  shortlink_job_cleaned
  shortlink_job_audit_deleted
```

**三条告警建议**（比"QPS 低"有用得多）：

| 告警 | 含义 |
|---|---|
| `shortlink_cache_not_ready_total` 持续增长 | Redis 被清空 / 主从空切，需要跑 rebuild |
| `shortlink_cache_evict_total{source="version"}` 持续增长 | Pub/Sub 在丢消息，订阅链路有问题（**注意与 `source="evict_all"` 区分：后者是重建的正常动作，前者才是故障**） |
| L1 命中率 < 90% | 容量不足或误码扫描，先看 `maxSize` 与 `evict_all` 频率 |

### 7.2 容量估算

| 指标 | 假设 | 结论 |
|---|---|---|
| 短链总量 | 日增 100 万，年 3.65 亿 | 单表需分片；简历项目按日增 1 万估算（年 365 万，单表足够） |
| 点击量 | 短链 : 点击 = 1 : 10 | 日 1000 万点击 ≈ 平均 116 QPS，峰值 10 倍 ≈ 1160 QPS |
| **压测目标** | 造数 10 万短链，固定 URL 池 | **单机 8C16G 目标 5w QPS，P99 < 5ms** |
| Redis 内存 | 100 万短链 × 500B ≈ 500MB | 4GB 实例足够 |
| CH 存储 | 1000 万点击/天 × 300B ≈ 3GB/天，压缩后 ~600MB | 90 天约 54GB，TTL 自动清理 |
| Kafka | 1000 万消息/天 × 500B ≈ 5GB/天 | 3 天保留 ≈ 15GB，单机够 |

> **诚实原则**：真实业务 QPS 和压测 QPS 要分开说。简历写“单机压测 5w QPS”没问题，但被问“线上多少”时要能答“这是设计目标，按日点击 1000 万估算线上约 1000 QPS 峰值”，**没人会信一个个人项目有真实 5w QPS 流量**。

### 7.3 压测方案

| 场景 | 工具 | 方法 | 关注指标 |
|---|---|---|---|
| 基准 | wrk2 | 固定 1w/3w/5w 速率，各跑 5min | QPS、P99、错误率 |
| 缓存对比 | wrk2 | 关闭 L1 vs 开启 L1 | 命中率、RT 分布 |
| 冷启动 | JMeter | 重启后立即打流量 | 缓存击穿情况、DB/Redis 压力 |
| 热点 | wrk2 | 单 URL 打满 | 单 key 限流是否生效、CPU |
| 故障 | Chaos | `docker stop redis` / `stop mysql` | 跳转可用性、恢复时间 |
| 长稳 | wrk2 | 3w QPS × 2h | 内存泄漏、GC 频率、连接数 |

**压测报告要出的图**：QPS-RT 曲线、火焰图（async-profiler）、GC 日志（GCViewer）、缓存命中率曲线。**这四张图是简历项目“真做过”的最强证据。**

### 7.4 安全设计

| 风险 | 措施 |
|---|---|
| 开放重定向滥用 | 创建时风控校验（5.6.3）、域名黑名单、举报下架 |
| 短码遍历 | FPE 混淆 + 单 IP 限流 + 异常访问告警 |
| SSRF | 长链 IP 解析 + 内网段拦截 |
| XSS | 错误页不反射用户输入；短码严格正则 |
| 越权 | 网关校验 JWT，admin 二次校验资源归属（`creator_id`） |
| 敏感数据 | IP 只存 hash（加盐），日志脱敏 |
| 爬虫 | UA 识别 + 行为频控 + `is_bot` 标记（统计剔除） |

---

## 8. 部署方案

### 8.1 本地一键启动（docker-compose）

| 服务 | 镜像 | 端口 | 内存 |
|---|---|---|---|
| mysql | mysql:8.0 | 3306 | 1G |
| redis | redis:7.2-alpine | 6379 | 512M |
| kafka | bitnami/kafka:3.7（KRaft） | 9092 | 1G |
| clickhouse | clickhouse/clickhouse-server:24 | 8123/9000 | 2G |
| nacos | nacos/nacos-server:v2.3.2（standalone） | 8848 | 1G |
| canal | canal/canal-server:v1.1.8 | 11111 | 512M |
| prometheus + grafana | — | 9090/3000 | 512M |
| **合计** | | | **~7.5G** |

**JVM 参数建议**（单机多实例，必须限制堆）

```
-Xms512m -Xmx512m -XX:+UseZGC -XX:MaxMetaspaceSize=192m
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp
```

redirect 单实例：`-Xms1g -Xmx1g -XX:+UseZGC -XX:+AlwaysPreTouch`（预触碰减少运行时缺页）。

### 8.2 配置要点

```yaml
# redirect 关键配置
server:
  port: 8081
  netty:
    connection-timeout: 2s
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 64      # 按 QPS × RT 估算
          max-idle: 32
          min-idle: 16
      timeout: 50ms           # 必须短，超时即降级走本地缓存
```

| 参数 | 值 | 理由 |
|---|---|---|
| Redis 超时 | 50ms | 超过就走本地缓存/404，绝不拖住事件循环 |
| Netty worker 线程 | 默认 = CPU 核数 | 事件循环线程数不宜多 |
| 连接池 max-active | 64 | 并发 × RT / 1000 |
| Caffeine maximumSize | 100000 | 按堆 1/8 和单条 800B 估算 |
| Tomcat 线程（admin） | 200 | 默认即可 |

### 8.3 生产化差异（面试可讲）

| 项 | 本地 | 生产 |
|---|---|---|
| 部署 | docker-compose | K8s + HPA（按 QPS 扩容 redirect） |
| Redis | 单机 | Cluster 3 主 3 从，读写分离 |
| MySQL | 单机 | 1 主 2 从，读写分离 + 半同步 |
| Kafka | 单 broker | 3 broker，副本 2，`min.insync.replicas=1` |
| 配置 | Nacos 单机 | Nacos 集群 |
| 灰度 | 无 | 按短码 hash 分流 1% → 10% → 100% |

---

## 9. 演进路线

> 建议严格按阶段走，每阶段都能独立演示、独立写简历。**不要一次上全套**。

| 阶段 | 内容 | 产出 | 预计工时 |
|---|---|---|---|
| **V1 能跑** | admin + redirect + MySQL + Redis，单机跑通创建/跳转 | 可演示的闭环 | 3~4 天 |
| **V2 快** | Caffeine 本地缓存 + Pub/Sub 失效 + 号段发号 + FPE（布隆与空值缓存实现后移除，见 5.4.4） | 压测报告：P99 从 8ms → 1ms | 3 天 |
| **V3 有数据** | Kafka + stats-consumer + ClickHouse + 实时 PV/UV | 统计看板 | 4 天 |
| **V4 稳** | 网关 + JWT + 限流 + 风控 + 可观测性 + 幂等 | 完整的生产形态 | 4 天 |
| **V5 深** | Canal 缓存同步 + 全局版本号兜底 + 分片方案 + 混沌演练 | 简历差异化 | 5 天 |

**每个阶段的简历素材**
- V2：`引入两级缓存，命中率 96.3%，P99 8.2ms → 1.1ms，单机 QPS 1.2w → 5.8w`
- V3：`点击流异步化，Kafka 削峰，CH 明细查询 1000 万行聚合 300ms 内`
- V4：`网关统一鉴权限流，拦截恶意请求 12%，风控拦截非法长链 300+`
- V5：`Canal 订阅 binlog + MQ 广播，缓存一致性延迟 < 100ms，MySQL 宕机跳转服务零影响`

---

## 10. 关键取舍与风险

| 决策 | 取舍 | 缓解 |
|---|---|---|
| redirect 不连 MySQL | 数据只能来自 Redis/本地缓存，无法回源 | rebuild 预热（已实现）+ 全局版本号 + Canal 兜底（V5，未实现） |
| 302 而非 301 | 每次请求都回源，服务器压力大 | 本地缓存 + 短 TTL 可选（接受统计损失） |
| 本地缓存 | 多实例数据可能短暂不一致 | Pub/Sub 精确失效 + 版本号对账，接受 ≤2s 延迟 |
| 本地限流 | 集群维度阈值不精确 | 网关做全局精确限流，本地只保容量 |
| 统计异步 | 可能丢数据 | 队列满丢弃 + 对账（CH 与 Redis 每日比对） |
| 布隆过滤器 | 不可删除、误判 | 只标记不删除；读链路不用布隆，误判不会变成 404；仅在 admin 侧做预检 |
| 单表起步 | 数据量到亿级需迁移 | 预留 ShardingSphere 配置，压测验证分片方案 |
| 5 个服务 | 运维/调试成本高 | docker-compose 一键起，本地单副本 |

**已知未解决问题**（写进文档，面试可主动提，显得真实）
1. 短链总量达到百亿级时布隆过滤器重建成本高，需设计分层可扩展布隆。
2. 跨机房多活未设计，短码发号器是全局单点。
3. 精确 UV 需要明细全量扫描，超大流量下应改为 HLL + 抽样校验。
4. 恶意短链的自动化识别（模型/第三方 API）未实现，仅静态黑名单。
5. redirect 没有熔断器：Redis 持续不可用时每个 miss 仍付 50ms 超时，而非立即失败；加熔断要处理"已恢复但仍在熔断窗口"的问题。

---

## 11. 面试映射表

| 设计点 | 可能被问 | 回答要点 |
|---|---|---|
| 短码生成 | 为什么不用自增 ID？ | 可枚举、泄露业务量；用 FPE 混淆且保持双射 |
| 7 位 Base62 | 容量多大？ | 3.52 万亿；初始号段设为 62^6 保证恰好 7 位 |
| 布隆过滤器 | 误判/删除/扩容怎么办？ | 读链路已移除布隆（滞后窗口会误判真链为 404）；不支持删除所以只增不删；扩容用分层布隆 |
| 缓存三件套 | 穿透/击穿/雪崩分别怎么解？ | 格式校验+限流 / 逻辑过期+singleflight / 随机 TTL+本地兜底；**布隆与空值缓存在读链路已移除**，理由是读侧没有 DB |
| 缓存就绪 | Redis 被清空后怎么区分"链接不存在"和"服务不可用"？ | `sl:cache:ready` 标志位：重建完成才置位，缺失时未命中一律降级 503 并打点，把静默的数据事故变成可告警的可用性事故 |
| 一致性 | MySQL 改了 Redis 怎么同步？ | 写时更新（不是删除）+ Pub/Sub 精确失效 + 版本号对账兜底 + Canal（V5） |
| redirect 无 DB | 缓存没命中怎么办？ | 格式校验先挡；miss 就是 404，**没有异步回源**（这是刻意的）；数据丢失场景的 404 是已知缺陷，见 5.4.5 |
| 302 vs 301 | 为什么不用 301？ | 301 被浏览器缓存，改不了链、统计不到、封禁无效 |
| 限流 | 为什么本地限流？ | 零网络开销、Redis 挂也不失效；集群精确限流放网关 |
| 统计 | 怎么保证不拖慢跳转？ | 有界队列 + 异步批量 + 满则丢弃，跳转优先 |
| UV | HLL 误差多少？ | 1.04/√m，m=16384 时 0.81%；精确值用 CH uniqExact |
| 分片 | 为什么按 short_code 分？ | 读路径不查 DB，管理端按码查最多；分组列表用 CQRS 索引表 |
| 幂等 | 重复创建怎么处理？ | Idempotency-Key + SETNX 存响应；业务层 (group, hash) 去重 |
| 消息重复 | Kafka 至少一次怎么幂等？ | eventId SETNX + 本地 Caffeine 预去重 |
| 压测 | QPS 怎么测的？瓶颈在哪？ | wrk2 固定速率，瓶颈依次是：Redis RTT → Netty 事件循环 → GC |
| 故障 | Redis 挂了会怎样？ | L1 命中 96%，剩余 4% 降级为 404/503；MySQL 挂了 redirect 无感 |

---

## 附录 A：依赖版本清单（BOM 参考）

```xml
<properties>
  <java.version>21</java.version>
  <spring-boot.version>3.3.5</spring-boot.version>
  <spring-cloud.version>2023.0.3</spring-cloud.version>
  <spring-cloud-alibaba.version>2023.0.3.2</spring-cloud-alibaba.version>
  <mybatis-plus.version>3.5.7</mybatis-plus.version>
  <shardingsphere.version>5.5.0</shardingsphere.version>
  <caffeine.version>3.1.8</caffeine.version>
  <redisson.version>3.34.1</redisson.version>
  <kafka.version>3.7.1</kafka.version>
  <clickhouse-jdbc.version>0.6.5</clickhouse-jdbc.version>
  <canal.version>1.1.8</canal.version>
  <xxl-job.version>2.4.1</xxl-job.version>
  <mapstruct.version>1.6.3</mapstruct.version>
  <springdoc.version>2.6.0</springdoc.version>
</properties>
```

> 版本号仅作起点，落地时以 `mvn dependency:tree` 无冲突为准。Spring Cloud Alibaba 与 Boot 的兼容性是唯一的硬约束。

## 附录 B：待办

- [ ] 补充 `sql/01-schema.sql`（本文字段可直接导出）
- [ ] 补充 `sql/02-clickhouse.sql`
- [ ] 补充 `docker-compose.yml` 与 Nacos 配置
- [ ] 压测脚本（wrk2 Lua）与基线数据
- [ ] 监控大盘 JSON（Grafana）
