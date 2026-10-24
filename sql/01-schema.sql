-- =====================================================================
--  ShortLink · MySQL Schema (V1)
--  适用：MySQL 8.0+
--  执行：mysql -uroot -p < sql/01-schema.sql
--
--  注意：short_code 必须使用 utf8mb4_bin。
--  Base62 字母表区分大小写（a ≠ A），而 MySQL 默认的 utf8mb4_0900_ai_ci
--  是大小写不敏感的——若用默认排序规则，uk_short_code 会把 aB3xK9z 与
--  Ab3xK9z 判为重复，导致合法短码插不进去，且只在特定组合下复现。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `shortlink`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS `shortlink_stats`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `shortlink`;

-- ---------------------------------------------------------------------
-- 短链主表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_short_link`;
CREATE TABLE `t_short_link` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键，分片后仅表内唯一',
  `gid`           CHAR(26)        NOT NULL                COMMENT '业务ID(ULID)，对外暴露，防遍历',
  `short_code`    VARCHAR(16)     COLLATE utf8mb4_bin NOT NULL COMMENT '短码，7位Base62，大小写敏感',
  `long_url`      VARCHAR(2048)   NOT NULL                COMMENT '原始长链',
  `long_url_hash` CHAR(32)        NOT NULL                COMMENT '长链MD5(小写)，用于同分组去重',
  `domain_id`     BIGINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '短链域名ID -> t_domain.id',
  `group_id`      BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '分组ID -> t_link_group.id，0=默认',
  `title`         VARCHAR(128)    NOT NULL DEFAULT ''     COMMENT '备注标题',
  `creator_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '创建人ID',
  `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '1启用 0禁用',
  `expire_time`   DATETIME(3)     DEFAULT NULL            COMMENT '过期时间，NULL=永久',
  `max_click`     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '最大点击次数，0=不限',
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
  KEY `idx_expire` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='短链主表';

-- ---------------------------------------------------------------------
-- 分组
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_link_group`;
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

-- ---------------------------------------------------------------------
-- 域名池
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_domain`;
CREATE TABLE `t_domain` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `domain`      VARCHAR(128)    NOT NULL                COMMENT '如 s.example.com',
  `protocol`    TINYINT         NOT NULL DEFAULT 2      COMMENT '1=http 2=https',
  `is_default`  TINYINT         NOT NULL DEFAULT 0      COMMENT '1=默认域名',
  `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '1启用 0停用',
  `remark`      VARCHAR(255)    NOT NULL DEFAULT '',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_domain` (`domain`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='短链域名池';

-- ---------------------------------------------------------------------
-- 号段发号
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_id_segment`;
CREATE TABLE `t_id_segment` (
  `biz_tag`     VARCHAR(64)     NOT NULL                COMMENT '业务标识，如 short_code',
  `max_id`      BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '当前已分配到的最大值',
  `step`        INT UNSIGNED    NOT NULL DEFAULT 1000   COMMENT '号段步长',
  `version`     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`biz_tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='号段发号器';

-- ---------------------------------------------------------------------
-- 批量导入任务（V4 使用）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_batch_task`;
CREATE TABLE `t_batch_task` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_no`        CHAR(26)        NOT NULL                COMMENT '任务号(ULID)',
  `group_id`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `file_name`      VARCHAR(255)    NOT NULL DEFAULT '',
  `file_url`       VARCHAR(512)    NOT NULL DEFAULT '',
  `total_count`    INT UNSIGNED    NOT NULL DEFAULT 0,
  `success_count`  INT UNSIGNED    NOT NULL DEFAULT 0,
  `fail_count`     INT UNSIGNED    NOT NULL DEFAULT 0,
  `status`         TINYINT         NOT NULL DEFAULT 0      COMMENT '0待处理 1处理中 2完成 3失败 4部分成功',
  `error_file_url` VARCHAR(512)    NOT NULL DEFAULT '',
  `creator_id`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `create_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_no` (`task_no`),
  KEY `idx_status_ctime` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='批量导入任务';

-- ---------------------------------------------------------------------
-- 操作审计（V4 使用）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_operate_log`;
CREATE TABLE `t_operate_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `biz_type`    VARCHAR(32)     NOT NULL                COMMENT 'LINK/GROUP/DOMAIN/BLACKLIST',
  `biz_id`      VARCHAR(64)     NOT NULL,
  `action`      VARCHAR(32)     NOT NULL                COMMENT 'CREATE/UPDATE/DELETE/ENABLE/DISABLE',
  `before_json` JSON            DEFAULT NULL,
  `after_json`  JSON            DEFAULT NULL,
  `operator_id` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `client_ip`   VARCHAR(64)     NOT NULL DEFAULT '',
  `trace_id`    CHAR(32)        NOT NULL DEFAULT '',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_biz` (`biz_type`, `biz_id`),
  KEY `idx_ctime` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='操作审计日志，按月归档';

-- =====================================================================
--  统计库（与业务库物理隔离，避免报表查询影响写入）
-- =====================================================================
USE `shortlink_stats`;

DROP TABLE IF EXISTS `t_stats_link_daily`;
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
  `region_top`   JSON            DEFAULT NULL,
  `referer_top`  JSON            DEFAULT NULL,
  `device_json`  JSON            DEFAULT NULL,
  `create_time`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_date` (`short_code`, `stat_date`),
  KEY `idx_date_pv` (`stat_date`, `pv`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='短链日聚合';

DROP TABLE IF EXISTS `t_stats_overview_daily`;
CREATE TABLE `t_stats_overview_daily` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `stat_date`   DATE            NOT NULL,
  `group_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '0=全局',
  `link_cnt`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `pv`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `uv`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_group` (`stat_date`, `group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='大盘日汇总';
