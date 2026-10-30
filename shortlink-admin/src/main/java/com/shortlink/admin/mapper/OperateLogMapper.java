package com.shortlink.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shortlink.admin.domain.entity.OperateLogDO;

/**
 * 操作日志 Mapper。
 *
 * <p>只写不改不删（归档走定时任务，V5）。审计表最忌讳业务代码能随意改——那它就没有证据价值了。</p>
 */
public interface OperateLogMapper extends BaseMapper<OperateLogDO> {
}
