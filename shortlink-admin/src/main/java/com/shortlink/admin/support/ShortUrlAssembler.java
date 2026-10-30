package com.shortlink.admin.support;

import com.shortlink.admin.domain.dto.LinkView;
import com.shortlink.admin.domain.entity.DomainDO;
import com.shortlink.admin.domain.entity.ShortLinkDO;
import com.shortlink.admin.mapper.DomainMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 短链展示对象装配：域名解析 + 拼接短链。
 *
 * <p>单独成类的两个理由：创建/详情/列表/修改四处都要用；V2 这里会加域名本地缓存，
 * 改动只落在一个文件。</p>
 *
 * <p>兜底：{@code t_domain} 查不到时用配置的 {@code shortlink.short-domain}，
 * 保证接口不会因为域名表漏配而整体失败。</p>
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
        DomainDO domain = link.getDomainId() == null ? null : domainMapper.selectById(link.getDomainId());
        return assemble(link, domain);
    }

    /**
     * 批量装配（列表用）。
     *
     * <p>一次查出全部域名再在内存里映射，而不是每行查一次数据库：
     * 域名表最多几十行，N+1 次查询才是真问题——20 条列表就会打 20 次 DB。</p>
     */
    public List<LinkView> toViews(List<ShortLinkDO> links) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        Map<Long, DomainDO> domains = domainMapper.selectList(null).stream()
                .collect(Collectors.toMap(DomainDO::getId, Function.identity(), (first, second) -> first));
        return links.stream()
                .map(link -> assemble(link, domains.get(link.getDomainId())))
                .toList();
    }

    private LinkView assemble(ShortLinkDO link, DomainDO domain) {
        return new LinkView(
                link.getGid(),
                link.getShortCode(),
                buildShortUrl(link.getShortCode(), domain),
                link.getLongUrl(),
                link.getTitle() == null ? "" : link.getTitle(),
                link.getStatus(),
                link.getExpireTime(),
                link.getMaxClick() == null ? 0L : link.getMaxClick(),
                link.getClickCount() == null ? 0L : link.getClickCount(),
                link.getCreateTime());
    }

    private String buildShortUrl(String shortCode, DomainDO domain) {
        if (domain == null) {
            return "https://" + fallbackDomain + "/" + shortCode;
        }
        boolean https = domain.getProtocol() != null && domain.getProtocol() == 2;
        return (https ? "https://" : "http://") + domain.getDomain() + "/" + shortCode;
    }
}
