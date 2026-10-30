package com.shortlink.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志。
 *
 * <p>只记"谁在什么时候把什么改成了什么"，不记查询。审计表的写入量应当与<strong>写操作</strong>成正比，
 * 而不是与 QPS 成正比——否则它会变成第二个业务表。</p>
 */
@Data
@TableName("t_operate_log")
public class OperateLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** LINK / GROUP / DOMAIN / BLACKLIST */
    private String bizType;

    private String bizId;

    /** CREATE / UPDATE / DELETE / ENABLE / DISABLE / BATCH_CREATE */
    private String action;

    /** 变更前快照（JSON），CREATE 时为 null */
    private String beforeJson;

    /** 变更后快照（JSON） */
    private String afterJson;

    private Long operatorId;

    private String clientIp;

    private String traceId;

    private LocalDateTime createTime;
}
