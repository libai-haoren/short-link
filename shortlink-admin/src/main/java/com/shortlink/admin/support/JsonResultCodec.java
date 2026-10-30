package com.shortlink.admin.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.component.idempotent.ResultCodec;

/**
 * 基于 Jackson 的幂等结果编解码。
 *
 * <p>放在 admin 而不是组件里：幂等组件不认识 JSON，序列化方式应由使用方决定。</p>
 */
public class JsonResultCodec<T> implements ResultCodec<T> {

    private final ObjectMapper objectMapper;
    private final Class<T> type;

    public JsonResultCodec(ObjectMapper objectMapper, Class<T> type) {
        this.objectMapper = objectMapper;
        this.type = type;
    }

    @Override
    public String encode(T value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("serialize idempotent result failed", ex);
        }
    }

    @Override
    public T decode(String payload) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (Exception ex) {
            throw new IllegalStateException("deserialize idempotent result failed", ex);
        }
    }
}
