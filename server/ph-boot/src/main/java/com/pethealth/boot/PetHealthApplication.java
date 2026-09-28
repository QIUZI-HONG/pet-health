package com.pethealth.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * <p>{@code @EnableScheduling} 是**提醒批算的前提**：不加它，{@code @Scheduled} 注解只是装饰，
 * 每日提醒永远不生成（这个坑差点被「惰性补算」掩盖过去——用户读消息时确实还能看到提醒，
 * 所以少有人发现批算没跑）。
 */
@SpringBootApplication(scanBasePackages = "com.pethealth")
@EnableScheduling
public class PetHealthApplication {

    public static void main(String[] args) {
        SpringApplication.run(PetHealthApplication.class, args);
    }
}
