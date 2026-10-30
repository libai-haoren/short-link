package com.shortlink.admin.controller;

import com.shortlink.admin.domain.dto.RebuildResult;
import com.shortlink.admin.service.CacheRebuildService;
import com.shortlink.common.core.result.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 缓存运维接口。
 *
 * <p><b>没有鉴权</b>：网关鉴权在 V4，这里先记着。上线前必须挂在内网或网关之后，
 * 否则任何人都能触发全量重建（虽然是幂等操作，但会打满 Redis 与 DB）。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/cache")
public class CacheAdminController {

    private final CacheRebuildService rebuildService;

    public CacheAdminController(CacheRebuildService rebuildService) {
        this.rebuildService = rebuildService;
    }

    /**
     * 用 MySQL 重建 Redis 缓存。
     *
     * <pre>
     * curl -X POST 'http://127.0.0.1:8082/api/v1/admin/cache/rebuild?batchSize=2000'
     * </pre>
     */
    @PostMapping("/rebuild")
    public Result<RebuildResult> rebuild(@RequestParam(defaultValue = "2000") int batchSize) {
        return Result.ok(rebuildService.rebuild(batchSize));
    }
}
