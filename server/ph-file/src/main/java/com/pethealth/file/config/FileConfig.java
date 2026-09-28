package com.pethealth.file.config;

import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.file.storage.FileStorage;
import com.pethealth.file.storage.FileStorageProperties;
import com.pethealth.file.storage.LocalFileStorage;
import com.pethealth.file.storage.UploadTokens;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件模块的装配。
 *
 * <p>驱动按配置挑：目前只有 {@code local}。配成别的值**直接启动失败**，而不是悄悄退回本地盘——
 * 生产环境误配成 {@code cos} 却写进了本地容器，重启一次照片就没了，那种失败比启动不起来危险得多。
 */
@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileConfig {

    @Bean
    public UploadTokens uploadTokens(FieldCipher fieldCipher) {
        return new UploadTokens(fieldCipher);
    }

    @Bean
    public FileStorage fileStorage(FileStorageProperties properties) {
        if (!LocalFileStorage.DRIVER.equals(properties.driver())) {
            throw new IllegalStateException("未知的文件存储驱动：" + properties.driver()
                    + "。本期只实现了 " + LocalFileStorage.DRIVER
                    + "，生产用的 cos 驱动见 ADR-0020（等 #66 部署方案与云账号确认）");
        }
        return new LocalFileStorage(properties);
    }
}
