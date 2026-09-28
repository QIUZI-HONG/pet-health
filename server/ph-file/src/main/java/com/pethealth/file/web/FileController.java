package com.pethealth.file.web;

import com.pethealth.api.app.FilePresignRequest;
import com.pethealth.api.app.FilePresignView;
import com.pethealth.api.app.FileView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.file.service.FileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * C 端文件接口，契约见 contract/app.yaml 的 {@code /files}。
 *
 * <p>只做元数据与凭证：**字节不经这里**——上传走 {@code /api/v1/open/files/**} 的签名地址，
 * 读图走那边签发的读地址。这条分工是 ADR-0020 的核心（浏览器直传，不让大文件穿过业务接口）。
 */
@RestController
@RequestMapping("/api/v1/app/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/presign")
    public ApiResponse<List<FilePresignView>> presign(@Valid @RequestBody FilePresignRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(fileService.presign(userId, request));
    }

    @GetMapping
    public ApiResponse<List<FileView>> list(@RequestParam(required = false) Long petId,
                                           @RequestParam(required = false) String bizType) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(fileService.list(userId, petId, bizType));
    }

    @GetMapping("/{fileId}")
    public ApiResponse<FileView> get(@PathVariable long fileId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(fileService.get(userId, fileId));
    }

    @DeleteMapping("/{fileId}")
    public ApiResponse<Void> delete(@PathVariable long fileId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        fileService.delete(userId, fileId);
        return ApiResponse.ok();
    }
}
