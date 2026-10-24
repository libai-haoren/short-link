package com.shortlink.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短链域名池。
 *
 * <p>做域名池而不是写死一个域名，是真实业务需求：不同渠道（短信 / 微信 / 海外）
 * 常用不同短域名，且某个域名被微信封了要能快速切换。</p>
 */
@Data
@TableName("t_domain")
public class DomainDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 如 s.example.com，不含协议 */
    private String domain;

    /** 1=http 2=https */
    private Integer protocol;

    private Integer isDefault;

    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
