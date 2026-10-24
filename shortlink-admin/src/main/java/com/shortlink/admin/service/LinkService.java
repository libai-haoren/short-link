package com.shortlink.admin.service;

import com.shortlink.admin.domain.dto.CreateLinkRequest;
import com.shortlink.admin.domain.dto.LinkView;

/**
 * 短链业务接口。
 *
 * <p>V1 只暴露创建与详情两个能力，够跑通"创建 → 跳转"的端到端验证。
 * 列表/修改/封禁在 V2 补。</p>
 */
public interface LinkService {

    /**
     * 创建短链。
     *
     * <p>幂等语义：同分组 + 同长链（MD5 相同）默认复用已有短链，不重复创建。
     * 这是业务级去重，不是 header 幂等（{@code Idempotency-Key} 在 V4 接）。</p>
     */
    LinkView create(CreateLinkRequest request);

    /** 按业务 ID 查详情，不存在抛 404。 */
    LinkView getByGid(String gid);
}
