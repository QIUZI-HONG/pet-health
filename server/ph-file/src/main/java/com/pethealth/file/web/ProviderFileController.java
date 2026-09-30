package com.pethealth.file.web;

import com.pethealth.api.app.FilePresignRequest;
import com.pethealth.api.app.FilePresignView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.file.service.FileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务者侧的文件接口，契约见 contract/provider.yaml 的 {@code /files/presign}。
 *
 * <p><b>为什么需要这一份</b>：{@code JwtAuthenticationFilter} 按**路径前缀**判登录域（ADR-0012），
 * {@code /api/v1/app/files/**} 只认 C 端令牌——服务者令牌打过去等于没登录（40100）。
 * 而三道照片墙（ADR-0040 第四节）的照片是门店拍的，{@code file_id} 必须由门店自己的令牌换出来，
 * 否则「这张图是谁上传的」在文件域里无从落账（{@link FileService#presign} 拿的正是上传者 id）。
 * 所以这里补的是**登录域的入口**，不是第二套文件逻辑：三个方法都直接转 {@link FileService}。
 *
 * <p>只开 {@code presign}：读与删没有服务者侧的场景——照片的读取走订单（{@code OrderPhotoWallView}
 * 里带签名地址），替换走照片槽位的**整体替换**（{@code file_id} 列表里去掉即下墙）。
 * 少一个入口就少一处要守的归属判定。
 *
 * <p>与 C 端的分工一致：**字节不经这里**——上传走 {@code /api/v1/open/files/{id}/content}
 * 的签名地址，那组接口不认登录域（授权在签名里），服务者的令牌同样用得着。
 */
@RestController
@RequestMapping("/api/v1/provider/files")
public class ProviderFileController {

    /** 服务者侧唯一开放的用途（契约里 {@code biz_type} 的枚举只有它一个）。 */
    private static final String BIZ_TYPE_CARE = "care";

    private final FileService fileService;

    public ProviderFileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 申请服务留痕照片的上传凭证（一次最多 9 张）。
     *
     * <p>用途收窄到 {@code care} 是**照着契约拒**（contract/provider.yaml 的
     * {@code FilePresignRequest.biz_type} 只写了 {@code care}）：打卡 / 防疫 / 档案照片 / 咨询图
     * 都是用户自己的照片，门店的手不该有上传它们的位置。文件本身是**上传者私有**的
     * （ADR-0020 第六条：读图必过归属校验），所以多开的用途不会泄漏谁的数据——
     * 它只是会长出一批谁都挂不上的孤儿行，那不叫功能。
     */
    @PostMapping("/presign")
    public ApiResponse<List<FilePresignView>> presign(@Valid @RequestBody FilePresignRequest request) {
        long providerUserId = CurrentUser.requireDomain(LoginDomain.PROVIDER);
        if (!BIZ_TYPE_CARE.equals(request.bizType().trim())) {
            throw BusinessException.paramInvalid("服务者侧只支持 care（服务留痕）用途：" + request.bizType());
        }
        return ApiResponse.ok(fileService.presign(providerUserId, request));
    }
}
