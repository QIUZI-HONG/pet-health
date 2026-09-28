package com.pethealth.file.web;

import com.pethealth.file.service.FileService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 直传与读图的落点。契约见 contract/open.yaml。
 *
 * <p>**这一组接口不要求登录**，因为授权在签名里（ADR-0020）：前端 {@code <img src>} 带不了
 * {@code Authorization} 头，而对象存储的直传地址本来也不认识我们的 Token。校验一律在
 * {@link FileService} 里做——它拿签名里的文件 id、归属用户与过期时间与数据库里的那一行对。
 */
@RestController
@RequestMapping("/api/v1/open/files")
public class OpenFileController {

    private final FileService fileService;

    public OpenFileController(FileService fileService) {
        this.fileService = fileService;
    }

    /** 直传落定。local 驱动直传即完成；cos 驱动将来由回调走同一条服务方法。 */
    @PutMapping("/{fileId}/content")
    public ResponseEntity<Void> upload(@PathVariable long fileId,
                                       @RequestParam("token") String token,
                                       @RequestBody byte[] content) {
        fileService.store(fileId, token, content);
        return ResponseEntity.noContent().build();
    }

    /** 读图（原图或缩略图，各自有各自的签名地址）。 */
    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> read(@PathVariable long fileId, @RequestParam("token") String token) {
        FileService.StoredContent content = fileService.read(fileId, token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mime()))
                // 私有缓存：照片是个人数据，不能被中间层共享；1 分钟与「详情缓存 1 分钟」一致
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate())
                .body(content.content());
    }
}
