package com.pethealth.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 骨架冒烟测试：验证 Spring 上下文能起来。
 *
 * <p>这条测试的价值在于它是「模块接线是否正确」的最廉价检查——加了新模块、改了依赖、
 * 写错了配置，它会第一时间失败。
 */
@SpringBootTest
class PetHealthApplicationTests {

    @Test
    void contextLoads() {
    }
}
