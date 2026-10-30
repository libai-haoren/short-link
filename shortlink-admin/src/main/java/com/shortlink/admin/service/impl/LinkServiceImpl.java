package com.shortlink.admin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.admin.domain.dto.BatchCreateRequest;
import com.shortlink.admin.domain.dto.BatchCreateResult;
import com.shortlink.admin.domain.dto.CreateLinkRequest;
import com.shortlink.admin.domain.dto.LinkQuery;
import com.shortlink.admin.domain.dto.LinkView;
import com.shortlink.admin.domain.dto.UpdateLinkRequest;
import com.shortlink.admin.domain.entity.ShortLinkDO;
import com.shortlink.admin.service.LinkPersistenceService;
import com.shortlink.admin.service.LinkPersistenceService.PersistResult;
import com.shortlink.admin.service.LinkService;
import com.shortlink.admin.service.OperateLogService;
import com.shortlink.admin.support.CurrentUser;
import com.shortlink.admin.support.JsonResultCodec;
import com.shortlink.admin.support.LinkCacheWriter;
import com.shortlink.admin.support.ShortUrlAssembler;
import com.shortlink.admin.support.UrlValidator;
import com.shortlink.common.core.exception.BizException;
import com.shortlink.common.core.result.ErrorCode;
import com.shortlink.common.core.result.PageResult;
import com.shortlink.common.core.util.Ulid;
import com.shortlink.component.bloom.BloomFilter;
import com.shortlink.component.codec.ShortCodeCodec;
import com.shortlink.component.idempotent.IdempotentGuard;
import com.shortlink.component.idgen.SegmentIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 短链创建、查询、修改、启停、批量导入。
 *
 * <p>写链路的统一顺序：<b>校验 → 落库 → 刷新缓存 → 记审计</b>。
 * 缓存一定在事务提交之后写，否则 DB 回滚了缓存却已经更新，会留下"能跳转但库里没有"的脏数据。</p>
 *
 * <p>已知限制：缓存刷新只能覆盖 Redis。redirect 的 L1 本地缓存靠 Pub/Sub 通知失效，
 * 极端情况下（消息丢失且版本号轮询也失败）最长等 TTL。</p>
 */
@Service
public class LinkServiceImpl implements LinkService {

    private static final long DEFAULT_GROUP_ID = 0L;
    private static final long DEFAULT_DOMAIN_ID = 1L;
    private static final int MAX_TITLE_LENGTH = 128;
    private static final int MAX_BATCH_SIZE = 500;
    private static final int STATUS_DISABLED = 0;
    private static final int STATUS_ENABLED = 1;

    /** 幂等结果保留 24h：足够覆盖"客户端超时重试"的全部场景。 */
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final LinkPersistenceService persistenceService;
    private final SegmentIdGenerator idGenerator;
    private final ShortCodeCodec codec;
    private final BloomFilter bloomFilter;
    private final LinkCacheWriter cacheWriter;
    private final UrlValidator urlValidator;
    private final ShortUrlAssembler assembler;
    private final IdempotentGuard idempotentGuard;
    private final OperateLogService operateLogService;
    private final JsonResultCodec<LinkView> linkViewCodec;

    public LinkServiceImpl(LinkPersistenceService persistenceService,
                           SegmentIdGenerator idGenerator,
                           ShortCodeCodec codec,
                           BloomFilter bloomFilter,
                           LinkCacheWriter cacheWriter,
                           UrlValidator urlValidator,
                           ShortUrlAssembler assembler,
                           IdempotentGuard idempotentGuard,
                           OperateLogService operateLogService,
                           ObjectMapper objectMapper) {
        this.persistenceService = persistenceService;
        this.idGenerator = idGenerator;
        this.codec = codec;
        this.bloomFilter = bloomFilter;
        this.cacheWriter = cacheWriter;
        this.urlValidator = urlValidator;
        this.assembler = assembler;
        this.idempotentGuard = idempotentGuard;
        this.operateLogService = operateLogService;
        this.linkViewCodec = new JsonResultCodec<>(objectMapper, LinkView.class);
    }

    @Override
    public LinkView create(CreateLinkRequest request, String idempotencyKey) {
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreate(request);
        }
        // 带幂等键：重复提交直接返回首次结果，而不是报"重复提交"
        return idempotentGuard.execute(idempotencyKey, IDEMPOTENCY_TTL,
                () -> doCreate(request), linkViewCodec);
    }

    @Override
    public BatchCreateResult batchCreate(BatchCreateRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "items 不能为空");
        }
        int total = request.items().size();
        if (total > MAX_BATCH_SIZE) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单次最多导入 " + MAX_BATCH_SIZE + " 条");
        }

        List<BatchCreateResult.Failure> failures = new ArrayList<>();
        int success = 0;
        for (int i = 0; i < total; i++) {
            BatchCreateRequest.Item item = request.items().get(i);
            try {
                // 复用单条创建：去重、风控、缓存刷新、审计全都一致
                doCreate(new CreateLinkRequest(item.longUrl(), null, null, item.title(), null, null));
                success++;
            } catch (RuntimeException ex) {
                // 单条失败不影响其他条，把原因带回去让调用方定位
                failures.add(new BatchCreateResult.Failure(i, item.longUrl(), ex.getMessage()));
            }
        }
        operateLogService.record(OperateLogService.BIZ_LINK, "batch", "BATCH_CREATE", null,
                Map.of("total", total, "success", success, "failed", failures.size()));
        return new BatchCreateResult(total, success, failures.size(), failures);
    }

    /** 真正创建一条（单条与批量共用）。 */
    private LinkView doCreate(CreateLinkRequest request) {
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
        if (result.created()) {
            operateLogService.record(OperateLogService.BIZ_LINK, saved.getGid(), "CREATE", null, saved);
        }
        return assembler.toView(saved);
    }

    @Override
    public LinkView getByGid(String gid) {
        return assembler.toView(requireByGid(gid));
    }

    @Override
    public PageResult<LinkView> list(LinkQuery query) {
        LinkQuery condition = query == null
                ? new LinkQuery(null, null, null, null, LinkQuery.DEFAULT_SIZE)
                : query;
        Long cursor = parseCursor(condition.cursor());
        String keyword = normalizeKeyword(condition.keyword());

        // 多取一条判断 hasMore，省掉一次 COUNT(*)
        List<ShortLinkDO> rows = persistenceService.page(
                condition.groupId(), condition.status(), keyword, cursor, condition.size() + 1);

        boolean hasMore = rows.size() > condition.size();
        List<ShortLinkDO> pageRows = hasMore ? rows.subList(0, condition.size()) : rows;
        if (pageRows.isEmpty()) {
            return PageResult.empty();
        }
        String nextCursor = hasMore
                ? String.valueOf(pageRows.get(pageRows.size() - 1).getId())
                : null;
        return PageResult.of(assembler.toViews(pageRows), nextCursor, hasMore);
    }

    @Override
    public LinkView update(String gid, UpdateLinkRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        ShortLinkDO existing = requireByGid(gid);
        // 改链同样要过风控：否则可以先建一条合法短链，再改成 javascript: 或内网地址
        urlValidator.validate(request.longUrl());

        String longUrl = request.longUrl().trim();
        String longUrlHash = DigestUtils.md5DigestAsHex(longUrl.getBytes(StandardCharsets.UTF_8));
        String title = normalizeTitle(request.title());
        long maxClick = request.maxClick() == null ? 0L : Math.max(0L, request.maxClick());

        int updated = persistenceService.updateFields(
                existing.getGid(), longUrl, longUrlHash, title,
                request.expireTime(), maxClick, LocalDateTime.now());
        if (updated == 0) {
            // 查到了但更新影响 0 行，说明期间被并发删除
            throw new BizException(ErrorCode.LINK_NOT_FOUND);
        }

        ShortLinkDO fresh = persistenceService.findByGid(existing.getGid());
        if (fresh == null) {
            throw new BizException(ErrorCode.LINK_NOT_FOUND);
        }
        cacheWriter.put(fresh);
        operateLogService.record(OperateLogService.BIZ_LINK, fresh.getGid(), "UPDATE", existing, fresh);
        return assembler.toView(fresh);
    }

    @Override
    public LinkView changeStatus(String gid, int status) {
        if (status != STATUS_ENABLED && status != STATUS_DISABLED) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 只能是 0 或 1");
        }
        ShortLinkDO existing = requireByGid(gid);
        if (existing.getStatus() != null && existing.getStatus() == status) {
            // 幂等：重复封禁不报错，直接返回当前状态
            return assembler.toView(existing);
        }

        int updated = persistenceService.updateStatus(existing.getGid(), status, LocalDateTime.now());
        if (updated == 0) {
            throw new BizException(ErrorCode.LINK_NOT_FOUND);
        }

        ShortLinkDO fresh = persistenceService.findByGid(existing.getGid());
        if (fresh == null) {
            throw new BizException(ErrorCode.LINK_NOT_FOUND);
        }
        cacheWriter.put(fresh);
        operateLogService.record(OperateLogService.BIZ_LINK, fresh.getGid(),
                status == STATUS_DISABLED ? "DISABLE" : "ENABLE", existing, fresh);
        return assembler.toView(fresh);
    }

    private ShortLinkDO requireByGid(String gid) {
        if (gid == null || gid.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "gid 不能为空");
        }
        ShortLinkDO link = persistenceService.findByGid(gid);
        if (link == null) {
            throw new BizException(ErrorCode.LINK_NOT_FOUND);
        }
        return link;
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(cursor.trim());
            if (value <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "cursor 非法");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new BizException(ErrorCode.PARAM_INVALID, "cursor 非法");
        }
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
        // 创建人来自网关注入的 X-User-Id；没经过网关（直连 admin）时记 0
        entity.setCreatorId(CurrentUser.idOrAnonymous());
        entity.setStatus(STATUS_ENABLED);
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
