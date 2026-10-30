package com.shortlink.admin.service;

import com.shortlink.admin.domain.dto.BatchCreateRequest;
import com.shortlink.admin.domain.dto.BatchCreateResult;
import com.shortlink.admin.domain.dto.CreateLinkRequest;
import com.shortlink.admin.domain.dto.LinkQuery;
import com.shortlink.admin.domain.dto.LinkView;
import com.shortlink.admin.domain.dto.UpdateLinkRequest;
import com.shortlink.common.core.result.PageResult;

/**
 * 短链业务接口。
 *
 * <p>所有方法以 {@code gid} 而不是 {@code shortCode} 作为标识：
 * 短码是可枚举的公开信息，用它做管理操作的入参会把"知道短码"等同于"有权限改"。</p>
 */
public interface LinkService {

    /**
     * 创建短链。
     *
     * <p>两层幂等：</p>
     * <ul>
     *   <li><b>业务去重</b>：同分组 + 同长链（MD5 相同）默认复用已有短链；</li>
     *   <li><b>请求幂等</b>：带 {@code Idempotency-Key} 时，重复请求直接返回首次结果。</li>
     * </ul>
     *
     * @param idempotencyKey 客户端生成的幂等键，可为 null
     */
    LinkView create(CreateLinkRequest request, String idempotencyKey);

    /**
     * 批量创建（同步执行，逐条返回失败原因）。
     *
     * <p>不做跨条事务：一条失败不影响其他条，失败明细里能定位到下标。</p>
     */
    BatchCreateResult batchCreate(BatchCreateRequest request);

    /** 按业务 ID 查详情，不存在抛 404。 */
    LinkView getByGid(String gid);

    /**
     * 游标分页查询。
     *
     * <p>返回 {@code hasMore} 与 {@code nextCursor}，客户端不要用页码——
     * 短链列表会持续新增，页码分页在翻页时会重复或漏数据。</p>
     */
    PageResult<LinkView> list(LinkQuery query);

    /**
     * 修改短链（全量替换可变字段）。
     *
     * <p>修改成功后会<b>同步刷新 Redis</b>，否则 redirect 会一直读到旧值直到缓存过期。</p>
     */
    LinkView update(String gid, UpdateLinkRequest request);

    /**
     * 启用 / 停用。
     *
     * @param status 1 启用，0 停用
     */
    LinkView changeStatus(String gid, int status);
}
