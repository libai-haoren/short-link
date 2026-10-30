package com.shortlink.component.idempotent;

/**
 * 幂等结果编解码。
 *
 * <p>组件本身不认识 JSON：把序列化方式交给调用方，避免为了一个泛型工具
 * 把 Jackson 拖进 common 层（另一条路是让组件依赖 Jackson，但那会让所有使用方都被迫带上它）。</p>
 *
 * @param <T> 业务返回值类型
 */
public interface ResultCodec<T> {

    String encode(T value);

    T decode(String payload);
}
