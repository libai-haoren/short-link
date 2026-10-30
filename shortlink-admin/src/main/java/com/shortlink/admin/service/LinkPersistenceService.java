package com.shortlink.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shortlink.admin.domain.entity.ShortLinkDO;
import com.shortlink.admin.mapper.ShortLinkMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 短链持久化：所有落库操作集中在这里，业务层不直接碰 Mapper。
 *
 * <p>为什么单独一层：事务边界必须清晰。缓存与 MQ 不能放进事务（回滚不会撤销已发的消息），
 * 所以把"纯 DB 操作"收在一个 bean 里，让外层在事务提交后再做副作用。</p>
 */
@Service
public class LinkPersistenceService {

    private final ShortLinkMapper linkMapper;

    public LinkPersistenceService(ShortLinkMapper linkMapper) {
        this.linkMapper = linkMapper;
    }

    /** 同分组 + 同长链查重，走 idx_hash_group。 */
    public ShortLinkDO findByGroupAndHash(long groupId, String longUrlHash) {
        return linkMapper.selectOne(Wrappers.<ShortLinkDO>lambdaQuery()
                .eq(ShortLinkDO::getGroupId, groupId)
                .eq(ShortLinkDO::getLongUrlHash, longUrlHash)
                .last("LIMIT 1"));
    }

    public ShortLinkDO findByGid(String gid) {
        return linkMapper.selectOne(Wrappers.<ShortLinkDO>lambdaQuery()
                .eq(ShortLinkDO::getGid, gid)
                .last("LIMIT 1"));
    }

    /**
     * 游标分页查询。
     *
     * <p>按 {@code id DESC} 排序并用 {@code id < cursor} 过滤：id 是自增主键，
     * 与创建时间同序，既能走主键索引，又天然是稳定游标（不会因为并发插入导致翻页重复/漏数据）。</p>
     *
     * <p>调用方传 {@code limit = size + 1}，多取一条用来判断 {@code hasMore}，
     * 避免额外来一次 {@code COUNT(*)}。</p>
     */
    public List<ShortLinkDO> page(Long groupId, Integer status, String keyword, Long cursor, int limit) {
        LambdaQueryWrapper<ShortLinkDO> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(groupId != null, ShortLinkDO::getGroupId, groupId)
                .eq(status != null, ShortLinkDO::getStatus, status)
                .lt(cursor != null, ShortLinkDO::getId, cursor);

        if (StringUtils.hasText(keyword)) {
            // 标题模糊 + 短码/gid 精确。注意 title LIKE '%kw%' 用不上索引，
            // 数据量上来后应换成 ES（见设计文档的分片/检索演进）。
            wrapper.and(w -> w.like(ShortLinkDO::getTitle, keyword)
                    .or().eq(ShortLinkDO::getShortCode, keyword)
                    .or().eq(ShortLinkDO::getGid, keyword));
        }

        wrapper.orderByDesc(ShortLinkDO::getId)
                .last("LIMIT " + limit);
        return linkMapper.selectList(wrapper);
    }

    /**
     * 重建缓存用的顺序扫描：按 id 升序、{@code id > cursor}。
     *
     * <p>与列表分页的区别是排序方向：这里要顺序扫完全表，用升序；列表是最新在前，用降序。
     * 逻辑删除由 MP 的全局配置自动追加 {@code del_flag = 0}。</p>
     */
    public List<ShortLinkDO> pageForRebuild(Long cursor, int limit) {
        return linkMapper.selectList(Wrappers.<ShortLinkDO>lambdaQuery()
                .gt(cursor != null, ShortLinkDO::getId, cursor)
                .orderByAsc(ShortLinkDO::getId)
                .last("LIMIT " + limit));
    }

    /**
     * 更新可变字段。
     *
     * <p>用 {@link LambdaUpdateWrapper} 显式 {@code set} 而不是 {@code updateById(entity)}：
     * MP 默认的更新策略会跳过 null 字段，那样"把过期时间改成永久"（set null）就不会生效。
     * 显式 set 能正确写出 {@code SET expire_time = NULL}。</p>
     */
    public int updateFields(String gid,
                            String longUrl,
                            String longUrlHash,
                            String title,
                            LocalDateTime expireTime,
                            Long maxClick,
                            LocalDateTime updateTime) {
        LambdaUpdateWrapper<ShortLinkDO> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(ShortLinkDO::getGid, gid)
                .set(ShortLinkDO::getLongUrl, longUrl)
                .set(ShortLinkDO::getLongUrlHash, longUrlHash)
                .set(ShortLinkDO::getTitle, title)
                .set(ShortLinkDO::getExpireTime, expireTime)
                .set(ShortLinkDO::getMaxClick, maxClick)
                .set(ShortLinkDO::getUpdateTime, updateTime);
        return linkMapper.update(null, wrapper);
    }

    public int updateStatus(String gid, int status, LocalDateTime updateTime) {
        LambdaUpdateWrapper<ShortLinkDO> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(ShortLinkDO::getGid, gid)
                .set(ShortLinkDO::getStatus, status)
                .set(ShortLinkDO::getUpdateTime, updateTime);
        return linkMapper.update(null, wrapper);
    }

    /**
     * 插入，若短码已存在（理论上不该发生）则返回已有记录。
     *
     * <p>{@code uk_short_code} 是最后一道防线：发号器或编码器出 bug 时，
     * 宁可插入失败也不能产生重复短码。这里捕获后回查，把"异常"降级成"复用"，
     * 避免把内部错误暴露给用户。</p>
     *
     * <p>注意：异常在事务方法内被捕获不会触发回滚，MySQL 也允许在重复键错误后继续执行，
     * 因此随后的查询是安全的。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public PersistResult insertOrGetExisting(ShortLinkDO candidate) {
        try {
            linkMapper.insert(candidate);
            return new PersistResult(candidate, true);
        } catch (DuplicateKeyException ex) {
            ShortLinkDO existing = linkMapper.selectOne(Wrappers.<ShortLinkDO>lambdaQuery()
                    .eq(ShortLinkDO::getShortCode, candidate.getShortCode())
                    .last("LIMIT 1"));
            if (existing == null) {
                // 不是短码冲突（比如 gid 冲突），说明是真正的 bug，原样抛出
                throw ex;
            }
            return new PersistResult(existing, false);
        }
    }

    /**
     * @param entity  落库后的实体（新建或已存在）
     * @param created true 表示本次真正插入
     */
    public record PersistResult(ShortLinkDO entity, boolean created) {
    }
}
