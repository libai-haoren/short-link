package com.shortlink.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shortlink.admin.domain.entity.ShortLinkDO;
import com.shortlink.admin.mapper.ShortLinkMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
