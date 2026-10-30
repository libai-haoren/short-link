package com.shortlink.admin.controller;

import com.shortlink.admin.domain.dto.BatchCreateRequest;
import com.shortlink.admin.domain.dto.BatchCreateResult;
import com.shortlink.admin.domain.dto.CreateLinkRequest;
import com.shortlink.admin.domain.dto.LinkQuery;
import com.shortlink.admin.domain.dto.LinkView;
import com.shortlink.admin.domain.dto.UpdateLinkRequest;
import com.shortlink.admin.service.LinkService;
import com.shortlink.common.core.constant.HeaderConstants;
import com.shortlink.common.core.result.PageResult;
import com.shortlink.common.core.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短链管理接口。
 *
 * <p>路径以 {@code /api/v1} 开头，为了让网关按前缀路由时不用改代码。
 * 路径设计把"资源"和"动作"分开：{@code POST /links} 是创建，
 * {@code PUT /links/{gid}} 是修改，不要在 URL 里出现 {@code /createLink} 这种写法。</p>
 *
 * <p>所有写操作都会同步刷新 Redis 并发 Pub/Sub 通知；同时落一条操作审计。</p>
 */
@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    /**
     * 创建短链。
     *
     * <pre>
     * curl -X POST http://127.0.0.1:8082/api/v1/links \
     *   -H 'Content-Type: application/json' \
     *   -H 'Idempotency-Key: 8f14e45f-ea6b-4c1a-9c3d-2b7e5f0a1c22' \
     *   -d '{"longUrl":"https://example.com/a/b?x=1","title":"测试"}'
     * </pre>
     *
     * <p>{@code Idempotency-Key} 可选：带上之后重复提交会返回首次结果（同一个短码），
     * 不带则退化成普通的业务去重。</p>
     */
    @PostMapping
    public Result<LinkView> create(
            @RequestBody CreateLinkRequest request,
            @RequestHeader(value = HeaderConstants.IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return Result.ok(linkService.create(request, idempotencyKey));
    }

    /**
     * 批量创建（单次最多 500 条，同步返回逐条失败原因）。
     *
     * <pre>
     * curl -X POST http://127.0.0.1:8082/api/v1/links/batch \
     *   -H 'Content-Type: application/json' \
     *   -d '{"items":[{"longUrl":"https://a.com"},{"longUrl":"https://b.com","title":"B"}]}'
     * </pre>
     */
    @PostMapping("/batch")
    public Result<BatchCreateResult> batchCreate(@RequestBody BatchCreateRequest request) {
        return Result.ok(linkService.batchCreate(request));
    }

    /**
     * 列表（游标分页）。
     *
     * <pre>
     * # 第一页
     * curl 'http://127.0.0.1:8082/api/v1/links?size=20'
     * # 下一页：把上次返回的 nextCursor 传回来
     * curl 'http://127.0.0.1:8082/api/v1/links?size=20&cursor=1024'
     * # 过滤
     * curl 'http://127.0.0.1:8082/api/v1/links?groupId=1&status=0&keyword=双11'
     * </pre>
     */
    @GetMapping
    public Result<PageResult<LinkView>> list(
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(linkService.list(new LinkQuery(groupId, status, keyword, cursor, size)));
    }

    /** 按业务 ID 查详情。 */
    @GetMapping("/{gid}")
    public Result<LinkView> detail(@PathVariable String gid) {
        return Result.ok(linkService.getByGid(gid));
    }

    /**
     * 修改短链（全量替换可变字段）。
     *
     * <pre>
     * curl -X PUT http://127.0.0.1:8082/api/v1/links/{gid} \
     *   -H 'Content-Type: application/json' \
     *   -d '{"longUrl":"https://example.com/new","title":"新标题","expireTime":null,"maxClick":0}'
     * </pre>
     *
     * <p>注意 {@code expireTime} 传 null 表示改成永久，不是"不修改"。</p>
     */
    @PutMapping("/{gid}")
    public Result<LinkView> update(@PathVariable String gid, @RequestBody UpdateLinkRequest request) {
        return Result.ok(linkService.update(gid, request));
    }

    /** 封禁（幂等，重复调用返回当前状态）。 */
    @PostMapping("/{gid}/disable")
    public Result<LinkView> disable(@PathVariable String gid) {
        return Result.ok(linkService.changeStatus(gid, 0));
    }

    /** 解封。 */
    @PostMapping("/{gid}/enable")
    public Result<LinkView> enable(@PathVariable String gid) {
        return Result.ok(linkService.changeStatus(gid, 1));
    }
}
