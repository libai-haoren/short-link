package com.shortlink.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shortlink.admin.domain.entity.ShortLinkDO;

/**
 * 短链 Mapper。
 *
 * <p>V1 只用 BaseMapper + LambdaQueryWrapper，不写 XML：
 * 单表 CRUD 没必要引入 SQL 片段维护成本。等出现多表关联或复杂聚合再补 XML。</p>
 *
 * <p>注意：{@code short_code} 走唯一索引查询，{@code group_id + long_url_hash}
 * 走 {@code idx_hash_group}，两条查询路径都在建表脚本里准备了索引。</p>
 */
public interface ShortLinkMapper extends BaseMapper<ShortLinkDO> {
}
