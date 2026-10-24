-- =====================================================================
--  ShortLink · 初始化数据（V1）
--  执行：mysql -uroot -p < sql/02-init-data.sql
--  幂等：全部使用 ON DUPLICATE KEY UPDATE，可重复执行
-- =====================================================================

USE `shortlink`;

-- ---------------------------------------------------------------------
-- 号段起点 = 62^6 = 56800235584
-- 这个数字不是随便起的：它保证短码经 Base62 编码后恰好 7 位，
-- 不需要补零（补零会产生 000000a 这种泄露"系统刚上线"的短码）。
-- ---------------------------------------------------------------------
INSERT INTO `t_id_segment` (`biz_tag`, `max_id`, `step`, `version`)
VALUES ('short_code', 56800235584, 1000, 0)
ON DUPLICATE KEY UPDATE `update_time` = `update_time`;

-- ---------------------------------------------------------------------
-- 默认分组（id=1，与 t_short_link.group_id 的默认值呼应）
-- ---------------------------------------------------------------------
INSERT INTO `t_link_group` (`id`, `group_name`, `owner_id`, `remark`)
VALUES (1, '默认分组', 0, '系统内置')
ON DUPLICATE KEY UPDATE `update_time` = `update_time`;

-- ---------------------------------------------------------------------
-- 默认短链域名（id=1，与 t_short_link.domain_id 的默认值呼应）
-- 本地调试时改成自己的域名即可；protocol: 1=http 2=https
-- ---------------------------------------------------------------------
INSERT INTO `t_domain` (`id`, `domain`, `protocol`, `is_default`, `status`, `remark`)
VALUES (1, 's.example.com', 2, 1, 1, '默认短链域名，本地调试请替换')
ON DUPLICATE KEY UPDATE `update_time` = `update_time`;

-- ---------------------------------------------------------------------
-- 自检：号段起点必须是 62^6，否则短码会出现 6 位
-- ---------------------------------------------------------------------
SELECT
  (SELECT `max_id` FROM `t_id_segment` WHERE `biz_tag` = 'short_code') AS segment_start,
  POW(62, 6)                                                          AS expected_start,
  CASE
    WHEN (SELECT `max_id` FROM `t_id_segment` WHERE `biz_tag` = 'short_code') >= POW(62, 6)
    THEN 'OK' ELSE 'ERROR: 号段起点小于 62^6，短码可能不足 7 位'
  END AS check_result;
