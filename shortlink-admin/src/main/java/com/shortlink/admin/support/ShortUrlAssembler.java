package com.shortlink.admin.support;

import com.shortlink.admin.domain.dto.LinkView;
import com.shortlink.admin.domain.entity.DomainDO;
import com.shortlink.admin.domain.entity.ShortLinkDO;
import com.shortlink.admin.mapper.DomainMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 短链展示地址装配。
 *
 * <p>把"域名解析 + 拼接"从业务服务里抽出来，原因有两个：</p>
 * <ul>
 *   <li>创建和详情两个接口都要用它，重复代码会漂移；</li>
 *   <li>V2 这里会加域名本地缓存，改一个地方即可。</li>
 * </ul>
 *
 * <p>兜底策略：{@code t_domain} 查不到时用配置的 {@code shortlink.short-domain}，
 * 保证创建接口不会因为域名表漏配而失败。</p>
 */
@Component
public class ShortUrlAssembler {

    private final DomainMapper domainMapper;
    private final String fallbackDomain;

    public ShortUrlAssembler(DomainMapper domainMapper,
                             @Value("${shortlink.short-domain:s.example.com}") String fallbackDomain) {
        this.domainMapper = domainMapper;
        this.fallbackDomain = fallbackDomain;
    }

    public LinkView toView(ShortLinkDO link) {
        return new LinkView(
                link.getGid(),
                link.getShortCode(),
                buildShortUrl(link),
                link.getLongUrl(),
                link.getStatus(),
                link.getExpireTime(),
                link.getCreateTime());
    }

    private String buildShortUrl(ShortLinkDO link) {
        DomainDO domain = link.getDomainId() == null ? null : domainMapper.selectById(link.getDomainId());
        if (domain == null) {
            return "https://" + fallbackDomain + "/" + link.getShortCode();
        }
        boolean https = domain.getProtocol() != null && domain.getProtocol() == 2;
        return (https ? "https://" : "http://") + domain.getDomain() + "/" + link.getShortCode();
    }
}
