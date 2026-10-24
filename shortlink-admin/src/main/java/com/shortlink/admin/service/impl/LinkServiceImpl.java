package com.shortlink.admin.service.impl;

import com.shortlink.admin.domain.dto.CreateLinkRequest;
import com.shortlink.admin.domain.dto.LinkView;
import com.shortlink.admin.domain.entity.ShortLinkDO;
import com.shortlink.admin.service.LinkPersistenceService;
import com.shortlink.admin.service.LinkPersistenceService.PersistResult;
import com.shortlink.admin.service.LinkService;
import com.shortlink.admin.support.LinkCacheWriter;
import com.shortlink.admin.support.ShortUrlAssembler;
import com.shortlink.admin.support.UrlValidator;
import com.shortlink.common.core.exception.BizException;
import com.shortlink.common.core.result.ErrorCode;
import com.shortlink.common.core.util.Ulid;
import com.shortlink.component.bloom.BloomFilter;
import com.shortlink.component.codec.ShortCodeCodec;
import com.shortlink.component.idgen.SegmentIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 短链创建与查询。
 *
 * <p>创建链路（V1 完整版）：</p>
 * <pre>
 * 校验长链 → 算 MD5 → 同分组去重 → 取号 → 编码 → 落库 → 加布隆 → 写 Redis
 * </pre>
 *
 * <p>顺序上有一个容易做错的地方：<b>先落库再写缓存</b>。反过来的话，
 * 缓存写成功但 DB 失败（比如短码冲突），就会出现"能跳转但库里查不到"的脏数据，
 * 而且这种数据永远无法通过重新创建修复。</p>
 *
 * <p>V1 暂不发 MQ（redirect 单实例直接读 Redis）。V2 接入广播刷新后，
 * 在 {@code cacheWriter.put} 之后补一次 {@code CacheSyncEvent}。</p>
 */
@Service
public class LinkServiceImpl implements LinkService {

    private static final long DEFAULT_GROUP_ID = 0L;
    private static final long DEFAULT_DOMAIN_ID = 1L;
    private static final int MAX_TITLE_LENGTH = 128;

    private final LinkPersistenceService persistenceService;
    private final SegmentIdGenerator idGenerator;
    private final ShortCodeCodec codec;
    private final BloomFilter bloomFilter;
    private final LinkCacheWriter cacheWriter;
    private final UrlValidator urlValidator;
    private final ShortUrlAssembler assembler;

    public LinkServiceImpl(LinkPersistenceService persistenceService,
                           SegmentIdGenerator idGenerator,
                           ShortCodeCodec codec,
                           BloomFilter bloomFilter,
                           LinkCacheWriter cacheWriter,
                           UrlValidator urlValidator,
                           ShortUrlAssembler assembler) {
        this.persistenceService = persistenceService;
        this.idGenerator = idGenerator;
        this.codec = codec;
        this.bloomFilter = bloomFilter;
        this.cacheWriter = cacheWriter;
        this.urlValidator = urlValidator;
        this.assembler = assembler;
    }

    @Override
    public LinkView create(CreateLinkRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        urlValidator.validate(request.longUrl());

        String longUrl = request.longUrl().trim();
        long groupId = request.groupId() == null ? DEFAULT_GROUP_ID : request.groupId();
        String title = normalizeTitle(request.title());
        checkExpireTime(request.expireTime());

        String longUrlHash = DigestUtils.md5DigestAsHex(longUrl.getBytes(StandardCharsets.UTF_8));

        // 业务级去重：同分组同长链直接复用，避免用户手抖生成一堆一样的短链
        ShortLinkDO existing = persistenceService.findByGroupAndHash(groupId, longUrlHash);
        if (existing != null) {
            // 幂等返回也要刷缓存：这条记录的缓存可能已被淘汰或过期
            cacheWriter.put(existing);
            return assembler.toView(existing);
        }

        ShortLinkDO candidate = buildCandidate(request, longUrl, longUrlHash, groupId, title);
        PersistResult result = persistenceService.insertOrGetExisting(candidate);
        ShortLinkDO saved = result.entity();

        if (result.created()) {
            bloomFilter.add(saved.getShortCode());
        }
        cacheWriter.put(saved);

        return assembler.toView(saved);
    }

    @Override
    public LinkView getByGid(String gid) {
        if (gid == null || gid.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "gid 不能为空");
        }
        ShortLinkDO link = persistenceService.findByGid(gid);
        if (link == null) {
            throw new BizException(ErrorCode.LINK_NOT_FOUND);
        }
        return assembler.toView(link);
    }

    private ShortLinkDO buildCandidate(CreateLinkRequest request,
                                       String longUrl,
                                       String longUrlHash,
                                       long groupId,
                                       String title) {
        long segmentId = idGenerator.nextId();

        ShortLinkDO entity = new ShortLinkDO();
        entity.setGid(Ulid.next());
        entity.setShortCode(codec.encode(segmentId));
        entity.setLongUrl(longUrl);
        entity.setLongUrlHash(longUrlHash);
        entity.setDomainId(request.domainId() == null ? DEFAULT_DOMAIN_ID : request.domainId());
        entity.setGroupId(groupId);
        entity.setTitle(title);
        // TODO(V4): 从 JWT 解析真实创建人
        entity.setCreatorId(0L);
        entity.setStatus(1);
        entity.setExpireTime(request.expireTime());
        entity.setMaxClick(request.maxClick() == null ? 0L : Math.max(0L, request.maxClick()));
        entity.setClickCount(0L);
        entity.setVersion(0);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        entity.setDelFlag(0);
        return entity;
    }

    private static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        String trimmed = title.trim();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标题长度不能超过 " + MAX_TITLE_LENGTH);
        }
        return trimmed;
    }

    private static void checkExpireTime(LocalDateTime expireTime) {
        if (expireTime != null && !expireTime.isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "过期时间必须晚于当前时间");
        }
    }
}
