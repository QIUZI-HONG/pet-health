package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.privilege.RightsDtos;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.util.Text;
import com.pethealth.privilege.api.RightsApi;
import com.pethealth.privilege.domain.RightsCode;
import com.pethealth.privilege.domain.RightsGrant;
import com.pethealth.privilege.mapper.RightsCodeMapper;
import com.pethealth.privilege.mapper.RightsGrantMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 权益的运营侧（切片 #112）：码表维护、授予记录查询、手动授予与回收、判定视图。
 *
 * <p>三条刻意的口径：
 *
 * <ul>
 *   <li><b>扩码 ≠ 长出新能力</b>：运营可以新增码，但那个码在判定侧默认不生效
 *       （要有模块按它写判定才有用）。「加一行数据就多一项功能」这条期待必须被否掉；
 *   <li><b>停用码不撤销已有授予</b>：停用只是不再允许新授予——停用一个码不该让用户手里的
 *       权益消失（那是用户已经得到的东西）；
 *   <li><b>手动授予的典型用途是订阅</b>：ADR-0036 之后订阅没有支付载体，
 *       只能线下签约 + 后台标记；另一个用途是客诉补偿（理由必填并进备注）。
 * </ul>
 */
@Service
public class RightsAdminService {

    private final RightsCodeMapper codeMapper;
    private final RightsGrantMapper grantMapper;
    private final RightsApi rightsApi;

    public RightsAdminService(RightsCodeMapper codeMapper, RightsGrantMapper grantMapper,
                              RightsApi rightsApi) {
        this.codeMapper = codeMapper;
        this.grantMapper = grantMapper;
        this.rightsApi = rightsApi;
    }

    /** 码表（含停用）。 */
    @Transactional(readOnly = true)
    public List<RightsDtos.RightsCodeView> listCodes() {
        CurrentUser.requireAdmin();
        return codeMapper.selectList(Wrappers.<RightsCode>lambdaQuery()
                        .orderByAsc(RightsCode::getSortOrder)
                        .orderByAsc(RightsCode::getCode))
                .stream().map(PrivilegeViews::toRightsCodeView).toList();
    }

    /** 新增权益码。 */
    @Transactional
    public RightsDtos.RightsCodeView createCode(RightsDtos.RightsCodeRequest request) {
        CurrentUser.requireAdmin();
        if (request.code() == null || request.code().isBlank()) {
            throw BusinessException.paramInvalid("权益码不能为空（小写字母、数字与点，如 ai.unlimited）");
        }
        RightsCode existing = codeMapper.selectOne(Wrappers.<RightsCode>lambdaQuery()
                .eq(RightsCode::getCode, request.code()));
        if (existing != null) {
            throw BusinessException.conflict("该权益码已存在");
        }
        RightsCode code = new RightsCode();
        code.setCode(request.code().trim());
        code.setName(request.name().trim());
        code.setDescription(Text.trimToNull(request.description()));
        code.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        code.setStatus(request.status() == null ? RightsCode.STATUS_ENABLED : request.status());
        codeMapper.insert(code);
        return PrivilegeViews.toRightsCodeView(code);
    }

    /** 修改权益码（编码不可改；停用只挡新授予）。 */
    @Transactional
    public RightsDtos.RightsCodeView updateCode(String code, RightsDtos.RightsCodeRequest request) {
        CurrentUser.requireAdmin();
        RightsCode existing = requireCode(code);
        if (request.code() != null && !request.code().isBlank() && !existing.getCode().equals(request.code())) {
            throw BusinessException.paramInvalid("权益码不可变更（授予记录与各模块的判定都引用它）");
        }
        existing.setName(request.name().trim());
        existing.setDescription(Text.trimToNull(request.description()));
        existing.setSortOrder(request.sortOrder() == null ? existing.getSortOrder() : request.sortOrder());
        existing.setStatus(request.status() == null ? existing.getStatus() : request.status());
        codeMapper.updateById(existing);
        return PrivilegeViews.toRightsCodeView(existing);
    }

    /** 授予记录分页。 */
    @Transactional(readOnly = true)
    public PageResult<RightsDtos.RightsGrantView> listGrants(Long userId, String code, Integer status,
                                                             long page, long pageSize) {
        CurrentUser.requireAdmin();
        Page<RightsGrant> result = grantMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<RightsGrant>lambdaQuery()
                        .eq(userId != null, RightsGrant::getUserId, userId)
                        .eq(code != null && !code.isBlank(), RightsGrant::getCode, code)
                        .eq(status != null, RightsGrant::getStatus, status)
                        .orderByDesc(RightsGrant::getCreatedAt)
                        .orderByDesc(RightsGrant::getId));
        Map<String, RightsCode> codes = codesOf(result.getRecords());
        return PageResult.from(result, grant -> PrivilegeViews.toGrantView(grant, codes.get(grant.getCode())));
    }

    /** 手动授予一条权益（订阅标记 / 客诉补偿）。 */
    @Transactional
    public RightsDtos.RightsGrantView grant(RightsDtos.RightsGrantRequest request) {
        CurrentUser.requireAdmin();
        rightsApi.grant(new RightsApi.GrantCommand(request.userId(), request.code(), request.source(),
                request.sourceRef(), request.expireAt(), request.remark()));
        // 回读：手动授予与幂等跳过走的是同一条路，回读才能拿到那条记录的 id
        RightsGrant grant = grantMapper.selectOne(Wrappers.<RightsGrant>lambdaQuery()
                .eq(RightsGrant::getUserId, request.userId())
                .eq(RightsGrant::getCode, request.code())
                .eq(RightsGrant::getSource, request.source())
                .orderByDesc(RightsGrant::getId)
                .last("LIMIT 1"));
        RightsCode code = codeMapper.selectOne(Wrappers.<RightsCode>lambdaQuery()
                .eq(RightsCode::getCode, request.code()));
        return PrivilegeViews.toGrantView(grant, code);
    }

    /** 回收一条授予（只回收这一条）。 */
    @Transactional
    public RightsDtos.RightsGrantView revoke(long grantId) {
        CurrentUser.requireAdmin();
        rightsApi.revoke(grantId);
        RightsGrant grant = grantMapper.selectById(grantId);
        RightsCode code = codeMapper.selectOne(Wrappers.<RightsCode>lambdaQuery()
                .eq(RightsCode::getCode, grant.getCode()));
        return PrivilegeViews.toGrantView(grant, code);
    }

    /**
     * 某用户的权益判定视图（运营/客服排查用）——判定走引擎，不在这里另写一套。
     *
     * <p>不生效的码也会列出来（带中文名）：运营看到「这个用户什么都没有」与
     * 「他有这个码但当前不生效」是两件完全不同的事。
     */
    @Transactional(readOnly = true)
    public RightsDtos.RightsEvaluationView evaluationOf(long userId) {
        CurrentUser.requireAdmin();
        List<RightsCode> codes = codeMapper.selectList(Wrappers.<RightsCode>lambdaQuery()
                .orderByAsc(RightsCode::getSortOrder)
                .orderByAsc(RightsCode::getCode));
        Map<String, RightsApi.RightsState> states = rightsApi.evaluate(userId);

        List<RightsDtos.RightsItemView> items = new ArrayList<>();
        for (RightsCode code : codes) {
            RightsApi.RightsState state = states.get(code.getCode());
            items.add(PrivilegeViews.toRightsItemView(code,
                    state != null && state.effective(),
                    state == null ? null : state.source(),
                    state == null ? null : state.expireAt()));
        }
        return new RightsDtos.RightsEvaluationView(userId, items);
    }

    private Map<String, RightsCode> codesOf(List<RightsGrant> grants) {
        List<String> codes = grants.stream().map(RightsGrant::getCode).distinct().toList();
        Map<String, RightsCode> map = new java.util.HashMap<>();
        if (codes.isEmpty()) {
            return map;
        }
        for (RightsCode code : codeMapper.selectList(Wrappers.<RightsCode>lambdaQuery()
                .in(RightsCode::getCode, codes))) {
            map.put(code.getCode(), code);
        }
        return map;
    }

    private RightsCode requireCode(String code) {
        RightsCode existing = codeMapper.selectOne(Wrappers.<RightsCode>lambdaQuery()
                .eq(RightsCode::getCode, code));
        if (existing == null) {
            throw BusinessException.notFound("权益码不存在：" + code);
        }
        return existing;
    }
}
