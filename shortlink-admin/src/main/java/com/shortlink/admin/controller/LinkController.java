package com.shortlink.admin.controller;

import com.shortlink.admin.domain.dto.CreateLinkRequest;
import com.shortlink.admin.domain.dto.LinkView;
import com.shortlink.admin.service.LinkService;
import com.shortlink.common.core.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短链管理接口。
 *
 * <p>路径以 {@code /api/v1} 开头，是为了让网关按前缀路由时不用改代码。
 * 注意做路径设计时把"资源"和"动作"分开：{@code POST /links} 是创建，
 * {@code GET /links/{gid}} 是查询，不要在 URL 里出现 {@code /createLink} 这种写法。</p>
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
     *   -d '{"longUrl":"https://example.com/a/b?x=1","title":"测试"}'
     * </pre>
     */
    @PostMapping
    public Result<LinkView> create(@RequestBody CreateLinkRequest request) {
        return Result.ok(linkService.create(request));
    }

    /** 按业务 ID 查详情。 */
    @GetMapping("/{gid}")
    public Result<LinkView> detail(@PathVariable String gid) {
        return Result.ok(linkService.getByGid(gid));
    }
}
