package com.shortlink.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短链主表实体。
 *
 * <p>注意 {@code shortCode} 列在 MySQL 里是 {@code utf8mb4_bin}，大小写敏感；
 * 实体层面无法体现这一点，只能靠建表脚本保证。</p>
 */
@Data
@TableName("t_short_link")
public class ShortLinkDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务 ID（ULID），对外暴露，避免泄露自增 ID */
    private String gid;

    /** 7 位 Base62 短码 */
    private String shortCode;

    private String longUrl;

    /** 长链 MD5，用于同分组去重 */
    private String longUrlHash;

    private Long domainId;

    private Long groupId;

    private String title;

    private Long creatorId;

    /** 1 启用 0 禁用 */
    private Integer status;

    /** null 表示永久 */
    private LocalDateTime expireTime;

    /** 0 表示不限 */
    private Long maxClick;

    /** 累计点击，异步回写，允许滞后 */
    private Long clickCount;

    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer delFlag;
}
