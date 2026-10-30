package com.shortlink.admin.domain.dto;

/**
 * 缓存重建结果。
 *
 * @param scanned   扫描到的记录数
 * @param written   成功写入缓存的数量
 * @param failed    写入失败的数量（非零说明 Redis 有问题）
 * @param elapsedMs 总耗时
 */
public record RebuildResult(long scanned, long written, long failed, long elapsedMs) {
}
