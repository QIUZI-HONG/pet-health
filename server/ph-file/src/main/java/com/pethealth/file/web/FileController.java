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

    /**
     * 取上传凭证（一次可申请多张）。
     *
     * <p>校验的是用途、用途标记、类型与体积。**注意声明的类型与体积只是「提前拦」**——
     * 真正生效的判定在上传落盘那一步（魔数 + 实际字节数）。所以这里放行不等于那张图一定合格，
     * 客户端不能拿这里的 200 当「没问题」。
     */
    @PostMapping("/presign")
    public ApiResponse<List<FilePresignView>> presign(@Valid @RequestBody FilePresignRequest request) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(fileService.presign(userId, request));
    }

    /** 我的文件列表，可按宠物与用途过滤（两个都可以不传）。 */
    @GetMapping
    public ApiResponse<List<FileView>> list(@RequestParam(required = false) Long petId,
                                           @RequestParam(required = false) String bizType) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(fileService.list(userId, petId, bizType));
    }

    /** 文件元数据。不是我的按**不存在**处理（40400），免得用 id 探测别人的图。 */
    @GetMapping("/{fileId}")
    public ApiResponse<FileView> get(@PathVariable long fileId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        return ApiResponse.ok(fileService.get(userId, fileId));
    }

    /**
     * 删除文件：连同它的派生物（缩略图等子行）一起删。
     *
     * <p><b>两边的「删」不是一回事</b>，这里值得看清楚：
     *
     * <ul>
     *   <li><b>对象存储里的字节是真的删掉的</b>（{@code storage.remove}）——原图与每个子行各自的存储对象；
     *   <li><b>库里的行只是逻辑删除</b>（{@code isDeleted=1}，全局逻辑删除配置在
     *       {@code application.yml}）。所以「删了」之后档案里那条引用会指向一个读不出来的图。
     * </ul>
     *
     * <p>缩略图行不当独立文件处理：它的 id 传给这条接口会被按不存在拒绝，见 {@code FileService#requireOwned}。
     */
    @DeleteMapping("/{fileId}")
    public ApiResponse<Void> delete(@PathVariable long fileId) {
        long userId = CurrentUser.requireDomain(LoginDomain.APP);
        fileService.delete(userId, fileId);
        return ApiResponse.ok();
    }
}
