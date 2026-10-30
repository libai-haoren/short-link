package com.shortlink.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.admin.domain.entity.OperateLogDO;
import com.shortlink.admin.mapper.OperateLogMapper;
import com.shortlink.admin.support.CurrentUser;
import com.shortlink.common.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作审计。
 *
 * <p>两个刻意的设计：</p>
 * <ul>
 *   <li><b>审计失败不影响业务</b>：写日志抛异常会把一次成功的创建变成 500，
 *       这比"少一条审计"严重得多。这里 catch 住并打 ERROR。</li>
 *   <li><b>异步化留给以后</b>：当前同步插入，因为 admin 写 QPS 是个位数。
 *       真到需要异步时，换成落 MQ 即可，调用方无感知。</li>
 * </ul>
 */
@Service
public class OperateLogService {

    private static final Logger log = LoggerFactory.getLogger(OperateLogService.class);

    public static final String BIZ_LINK = "LINK";

    private final OperateLogMapper mapper;
    private final ObjectMapper objectMapper;

    public OperateLogService(OperateLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(String bizType, String bizId, String action, Object before, Object after) {
        try {
            OperateLogDO entry = new OperateLogDO();
            entry.setBizType(bizType);
            entry.setBizId(bizId);
            entry.setAction(action);
            entry.setBeforeJson(toJson(before));
            entry.setAfterJson(toJson(after));
            // 操作人来自网关注入的 X-User-Id；没经过网关时记 0（匿名）
            entry.setOperatorId(CurrentUser.idOrAnonymous());
            entry.setClientIp(CurrentUser.clientIp());
            entry.setTraceId(TraceContext.get());
            entry.setCreateTime(LocalDateTime.now());
            mapper.insert(entry);
        } catch (RuntimeException ex) {
            log.error("write operate log failed, bizType={}, bizId={}, action={}",
                    bizType, bizId, action, ex);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("serialize audit payload failed: {}", ex.toString());
            return null;
        }
    }
}
