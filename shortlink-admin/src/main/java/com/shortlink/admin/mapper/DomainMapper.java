package com.shortlink.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shortlink.admin.domain.entity.DomainDO;

/**
 * 域名 Mapper。
 *
 * <p>域名是典型的"读多写极少"配置数据，V2 会加本地缓存；
 * V1 直接查库（创建短链才查一次，QPS 个位数）。</p>
 */
public interface DomainMapper extends BaseMapper<DomainDO> {
}
