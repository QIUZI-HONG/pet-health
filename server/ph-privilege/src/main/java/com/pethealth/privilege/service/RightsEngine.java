package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.privilege.api.RightsApi;
import com.pethealth.privilege.domain.RightsCode;
import com.pethealth.privilege.domain.RightsGrant;
import com.pethealth.privilege.mapper.RightsCodeMapper;
import com.pethealth.privilege.mapper.RightsGrantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权益引擎（切片 #112），决策见 ADR-0038 第三节与 ADR-0045。
 *
 * <p><b>判定 = 实时 + 来源优先</b>：
 *
 * <pre>
 *   订阅(1) &gt; 邀请(2) &gt; 打卡(3) &gt; 运营补偿(4)
 *   每个码取「当前生效」的授予里 source 最小（优先级最高）的那一条
 * </pre>
 *
 * <p>**不比较到期时间**：「邀请得永久 + 订阅得月度」这种组合下，取最晚到期会把语义算错
 * （ADR-0038 的原话）。举例——用户从邀请拿到永久权益，又订了一个月订阅：
 * 正确的答案是「他有一条订阅（到月底），也有一条邀请（永久）」，展示与判定都取订阅那条
 * （当前最高优先级）；到月底订阅被回收后，他仍然保有邀请那条。按到期时间取则会得出
 * 「他拥有永久权益，所以订阅到期也不用管」——两个来源的信息就丢了。
 *
 * <p>**判定不缓存**：权益决定功能能不能用，缓存漂移的代价不对称（用户无法自救），
 * 而且这里查的是两张小表上的两个索引，没有缓存的必要。
 *
 * <p>到期**只回收该来源那一条**（订阅到期不触碰邀请得的永久权益），由
 * {@link #revokeExpired()} 批量做，它是一条件更新所以幂等。
 */
@Service
public class RightsEngine implements RightsApi {

    private static final Logger log = LoggerFactory.getLogger(RightsEngine.class);

    private final RightsCodeMapper codeMapper;
    private final RightsGrantMapper grantMapper;

    public RightsEngine(RightsCodeMapper codeMapper, RightsGrantMapper grantMapper) {
        this.codeMapper = codeMapper;
        this.grantMapper = grantMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, RightsState> evaluate(long userId) {
        LocalDateTime now = AppTime.now();
        List<RightsCode> codes = codeMapper.selectList(Wrappers.<RightsCode>lambdaQuery()
                .orderByAsc(RightsCode::getSortOrder)
                .orderByAsc(RightsCode::getCode));
        List<RightsGrant> grants = grantMapper.selectList(Wrappers.<RightsGrant>lambdaQuery()
                .eq(RightsGrant::getUserId, userId)
                .eq(RightsGrant::getStatus, RightsGrant.STATUS_ACTIVE));

        // 每个码挑一条生效里优先级最高的：source 越小优先级越高 → 取最小 source
        Map<String, RightsGrant> best = new HashMap<>();
        for (RightsGrant grant : grants) {
            if (!grant.isEffectiveAt(now)) {
                continue;
            }
            RightsGrant current = best.get(grant.getCode());
            if (current == null || grant.getSource() < current.getSource()) {
                best.put(grant.getCode(), grant);
            }
        }

        Map<String, RightsState> result = new LinkedHashMap<>();
        for (RightsCode code : codes) {
            RightsGrant grant = best.get(code.getCode());
            result.put(code.getCode(), new RightsState(code.getCode(), grant != null,
                    grant == null ? null : grant.getSource(),
                    grant == null ? null : grant.getExpireAt()));
        }
        // 码表里没有、但确实有授予记录的码（码被删过）也要出现在结果里：
        // 少一个 key 会让调用方以为「这个人什么都没有」，而真相是「有一条授予但码表里没定义」
        for (Map.Entry<String, RightsGrant> entry : best.entrySet()) {
            result.computeIfAbsent(entry.getKey(), key -> new RightsState(key, true,
                    entry.getValue().getSource(), entry.getValue().getExpireAt()));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEffective(long userId, String code) {
        RightsState state = evaluate(userId).get(code);
        return state != null && state.effective();
    }

    @Override
    @Transactional
    public void grant(GrantCommand command) {
        RightsCode code = codeMapper.selectOne(Wrappers.<RightsCode>lambdaQuery()
                .eq(RightsCode::getCode, command.code()));
        if (code == null) {
            throw BusinessException.notFound("权益码不存在：" + command.code());
        }
        LocalDateTime now = AppTime.now();
        if (command.expireAt() != null && command.expireAt().isBefore(now)) {
            throw BusinessException.paramInvalid("到期时间不能早于当前（那条权益授予即失效）");
        }
        String sourceRef = command.sourceRef() == null || command.sourceRef().isBlank()
                ? null : command.sourceRef().trim();

        RightsGrant existing = findExisting(command.userId(), command.code(), command.source(), sourceRef);
        if (existing != null) {
            // 幂等：同一来源的同一引用只授予一次。重复授予**不报错**——
            // 批算重跑与事件重放都走这条路，报错会让上游把「什么都不用做」当成失败。
            log.debug("权益已授予过，跳过：userId={} code={} source={} ref={}",
                    command.userId(), command.code(), command.source(), sourceRef);
            return;
        }

        RightsGrant grant = new RightsGrant();
        grant.setUserId(command.userId());
        grant.setCode(command.code());
        grant.setSource(command.source());
        grant.setSourceRef(sourceRef);
        grant.setExpireAt(command.expireAt());
        grant.setStatus(RightsGrant.STATUS_ACTIVE);
        grant.setRemark(command.remark() == null || command.remark().isBlank() ? null : command.remark().trim());
        try {
            grantMapper.insert(grant);
        } catch (DuplicateKeyException e) {
            // 并发下的同一来源引用：唯一键兜底（(user_id, code, source, source_ref)）
            log.debug("并发重复授予被唯一键拦下：userId={} code={} source={} ref={}",
                    command.userId(), command.code(), command.source(), sourceRef);
        }
    }

    @Override
    @Transactional
    public void revoke(long grantId) {
        RightsGrant grant = grantMapper.selectById(grantId);
        if (grant == null || grant.getStatus() == null || grant.getStatus() != RightsGrant.STATUS_ACTIVE) {
            // 顶层资源：重复回收 / 删别人的一律 40400（docs/conventions.md 的删除口径）
            throw BusinessException.notFound("授予记录不存在或已被回收");
        }
        revokeRow(grant, "运营回收");
    }

    @Override
    @Transactional
    public int revokeExpired() {
        int revoked = grantMapper.revokeExpired(AppTime.now());
        if (revoked > 0) {
            log.info("权益到期回收 {} 条（只回收该来源那一条，其他来源不受影响）", revoked);
        }
        return revoked;
    }

    private void revokeRow(RightsGrant grant, String remark) {
        grant.setStatus(RightsGrant.STATUS_REVOKED);
        grant.setRevokedAt(AppTime.now());
        grant.setRemark(remark);
        grantMapper.updateById(grant);
    }

    private RightsGrant findExisting(long userId, String code, int source, String sourceRef) {
        return grantMapper.selectOne(Wrappers.<RightsGrant>lambdaQuery()
                .eq(RightsGrant::getUserId, userId)
                .eq(RightsGrant::getCode, code)
                .eq(RightsGrant::getSource, source)
                .eq(sourceRef != null, RightsGrant::getSourceRef, sourceRef)
                .isNull(sourceRef == null, RightsGrant::getSourceRef)
                .last("LIMIT 1"));
    }
}
