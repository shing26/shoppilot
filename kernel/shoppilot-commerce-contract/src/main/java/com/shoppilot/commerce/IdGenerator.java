package com.shoppilot.commerce;

/**
 * 本地请求 id 生成器。测试注入确定性实现，生产用 UUID。
 */
@FunctionalInterface
public interface IdGenerator {

    String newId();
}
