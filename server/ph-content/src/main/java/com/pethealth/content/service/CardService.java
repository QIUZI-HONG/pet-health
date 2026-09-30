package com.pethealth.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.content.CommunityCardCreateRequest;
import com.pethealth.api.content.CommunityCardView;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.common.util.Text;
import com.pethealth.content.domain.CommunityCard;
import com.pethealth.content.domain.CommunityCardLike;
import com.pethealth.content.domain.ContentStatus;
import com.pethealth.content.mapper.CommunityCardLikeMapper;
import com.pethealth.content.mapper.CommunityCardMapper;
import com.pethealth.record.api.PetQueryApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 经验卡片：一键生成、浏览、详情、点赞（切片 #84 / F020；决策见 ADR-0041 第一节 / ADR-0050 第一节）。
 *
 * <p><b>四条贯穿本类的口径</b>：
 *
 * <ol>
 *   <li><b>卡片恒匿名</b>：{@code authorId} 只用于三件事——越权判定（待审的卡片只有作者看得到）、
 *       「我的卡片」列表、审核追溯。所有对外视图都不出作者字段（见 {@code ContentViews}）；
 *   <li><b>发文要权益</b>（{@code community.post}，ADR-0041 第一节）：只有**生成卡片**过这道门，
 *       浏览与点赞不过——点赞是互动不是发文；
 *   <li><b>内容进审核</b>：新建一律待审，机审命中敏感词直接落已驳回，公开列表只读已发布；
 *   <li><b>来源记录是逻辑关联</b>：校验宠物归属（走 ph-record 的 {@code PetQueryApi}，
 *       **不 join 它的表**，ADR-0006），但**不重读档案正文**——卡片正文是用户提交的那份，
 *       档案正文不因社区再暴露一次。
 * </ol>
 *
 * <p><b>待澄清</b>（ADR-0051 第 2 条）：{@code sourceRef} 只做关联与追溯，服务端不校验
 * 「这条记录确实属于这只宠物」——档案模块没有「按 id 取一条记录」的对外接口，
 * 为一个展示用的关联去扩它的接口不在本切片范围内。
 */
@Service
public class CardService {

    private static final Logger log = LoggerFactory.getLogger(CardService.class);

    private final CommunityCardMapper cardMapper;
    private final CommunityCardLikeMapper likeMapper;
    private final PostingAccess postingAccess;
    private final SensitiveWordService sensitiveWords;
    private final PetQueryApi petQueryApi;
    private final ContentViews views;

    public CardService(CommunityCardMapper cardMapper, CommunityCardLikeMapper likeMapper,
                       PostingAccess postingAccess, SensitiveWordService sensitiveWords,
                       PetQueryApi petQueryApi, ContentViews views) {
        this.cardMapper = cardMapper;
        this.likeMapper = likeMapper;
        this.postingAccess = postingAccess;
        this.sensitiveWords = sensitiveWords;
        this.petQueryApi = petQueryApi;
        this.views = views;
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 一键生成经验卡片（从打卡 / 就医记录）。
     *
     * <p>顺序刻意如此：**权益 → 归属 → 内容**。权益在最前：没有权限时不该再去问「这只宠物是不是你的」
     * ——那等于用一个 404 告诉无权限的人「这个 id 与你的关系」，而这两件事本该互不相干。
     */
    @Transactional
    public CommunityCardView create(long userId, CommunityCardCreateRequest request) {
        postingAccess.requirePostingRight(userId);
        if (request.sourceType() != CommunityCard.SOURCE_CHECK_IN
                && request.sourceType() != CommunityCard.SOURCE_MEDICAL) {
            throw BusinessException.paramInvalid("来源记录类型只能是 1 打卡 / 2 就医记录");
        }
        if (!petQueryApi.existsOwnedBy(userId, request.petId())) {
            // 「没这只」与「不是他的」同码（docs/conventions.md 的越权口径）
            throw BusinessException.notFound("宠物不存在或不属于当前账号");
        }
        applyTagRules(request);

        CommunityCard card = new CommunityCard();
        card.setAuthorId(userId);
        card.setPetId(request.petId());
        card.setSourceType(request.sourceType());
        card.setSourceRef(request.sourceRef().trim());
        card.setSpecies(request.species());
        card.setBreed(Text.trimToNull(request.breed()));
        card.setDiseaseTag(Text.trimToNull(request.diseaseTag()));
        card.setTitle(request.title().trim());
        card.setContent(request.content().trim());
        card.setLikeCount(0);
        applyMachineReview(card, request.title(), request.content());
        cardMapper.insert(card);
        log.debug("经验卡片已生成：cardId={} userId={} source={}/{}", card.getId(), userId,
                request.sourceType(), request.sourceRef());
        return views.card(card, userId, false);
    }

    // ---------------------------------------------------------------- 读

    /** 卡片列表：默认只看已发布；{@code mine=true} 看自己的（含待审与被拒）。 */
    @Transactional(readOnly = true)
    public PageResult<CommunityCardView> list(long viewerId, Integer species, String breed, String diseaseTag,
                                              boolean mine, long page, long pageSize) {
        String breedFilter = Text.trimToNull(breed);
        String diseaseFilter = Text.trimToNull(diseaseTag);
        Page<CommunityCard> result = cardMapper.selectPage(Page.of(page, pageSize),
                Wrappers.<CommunityCard>lambdaQuery()
                        // 公开列表只读已发布；我的列表按作者过滤（此时待审与被拒也一起给）
                        .eq(!mine, CommunityCard::getStatus, ContentStatus.PUBLISHED)
                        .eq(mine, CommunityCard::getAuthorId, viewerId)
                        .eq(species != null, CommunityCard::getSpecies, species)
                        .eq(breedFilter != null, CommunityCard::getBreed, breedFilter)
                        .eq(diseaseFilter != null, CommunityCard::getDiseaseTag, diseaseFilter)
                        .orderByDesc(CommunityCard::getId));
        Set<Long> liked = likedCardIds(viewerId, result.getRecords());
        return PageResult.of(views.cards(result.getRecords(), viewerId, liked),
                result.getCurrent(), result.getSize(), result.getTotal());
    }

    /**
     * 卡片详情。
     *
     * <p>待审与被拒的卡片**只有作者看得到**，别人一律 40400（越权与不存在同码）：
     * 用 id 探测别人内容的存在性，不该得到「存在但无权」这种有信息量的答复。
     */
    @Transactional(readOnly = true)
    public CommunityCardView get(long viewerId, long cardId) {
        CommunityCard card = requireVisibleCard(viewerId, cardId);
        return views.card(card, viewerId, likedExists(viewerId, cardId));
    }

    // ---------------------------------------------------------------- 点赞

    /**
     * 点赞（**幂等**，不报 40900）。
     *
     * <p>为什么幂等：点赞按钮的重试与双击是常态，而「重复点赞」在语义上什么也没发生。
     * 三段实现各自挡住一件事——
     *
     * <ol>
     *   <li>先把**曾经取消过**的那一行翻回来（{@code is_deleted 1 → 0}）：唯一键只允许一行，
     *       所以重新点赞是翻转而不是再插；
     *   <li>翻不动（本来就没点过）才插一行；并发双击时第二个撞在唯一键上，
     *       被当作「已经点过了」——**这不是错误**；
     *   <li>只有上面两步中**真的改变了状态**的那一次才给计数 +1，否则计数会被重试推高。
     * </ol>
     */
    @Transactional
    public CommunityCardView like(long userId, long cardId) {
        requirePublicCard(cardId);
        LocalDateTime now = AppTime.now();
        String traceId = TraceIds.currentTraceId();
        boolean changed = likeMapper.restoreLike(cardId, userId, now, traceId) > 0;
        if (!changed) {
            try {
                likeMapper.insertLike(cardId, userId, now, traceId);
                changed = true;
            } catch (DuplicateKeyException e) {
                log.debug("重复点赞，跳过：cardId={} userId={}", cardId, userId);
            }
        }
        if (changed) {
            cardMapper.increaseLikeCount(cardId, now, userId, traceId);
        }
        return views.card(cardMapper.selectById(cardId), userId, true);
    }

    /**
     * 取消点赞（**宠物名下子资源口径**：归属先校验，子资源自身删除幂等 —— docs/conventions.md）。
     *
     * <p>本来就没点过 → 返回成功（不是错误）：调用方要的结果是「现在没点着」，而它已经成立。
     */
    @Transactional
    public CommunityCardView unlike(long userId, long cardId) {
        requirePublicCard(cardId);
        LocalDateTime now = AppTime.now();
        String traceId = TraceIds.currentTraceId();
        if (likeMapper.removeLike(cardId, userId, now, traceId) > 0) {
            cardMapper.decreaseLikeCount(cardId, now, userId, traceId);
        }
        return views.card(cardMapper.selectById(cardId), userId, false);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 机审 + 初始状态：命中敏感词直接落**已驳回**，未命中留**待审**。
     *
     * <p>为什么命中不留在待审里「等人工看一眼」：机审的意义就是把明显不该出现的内容挡在队列之外，
     * 否则队列里全是广告。而**改判的口子留着**——词表误伤是常态（运营可以在审核后台把它通过）。
     */
    private void applyMachineReview(CommunityCard card, String title, String content) {
        String hits = sensitiveWords.hits(title, content);
        card.setMachineHits(hits);
        card.setStatus(hits == null ? ContentStatus.PENDING : ContentStatus.REJECTED);
        if (hits != null) {
            // 作者看到的是「已驳回」+ 运营写的理由（机审命中细节只进运营端：说了词表就被绕开了）
            card.setRejectReason("内容命中敏感词，未通过机审");
        }
    }

    /**
     * 标签的一致性（40001）：带了品种却没带物种，聚合时它永远不会被任何物种筛选命中——
     * 与其静默存进去，不如让调用方当场改。单独的物种（没有品种）是合法的（「所有猫」的聚合）。
     */
    private static void applyTagRules(CommunityCardCreateRequest request) {
        if (Text.trimToNull(request.breed()) != null && request.species() == null) {
            throw BusinessException.paramInvalid("带了品种就必须带物种（1 犬 / 2 猫），否则这条卡片不会被任何物种聚合命中");
        }
        if (request.species() != null && request.species() != 1 && request.species() != 2) {
            throw BusinessException.paramInvalid("物种只能是 1 犬 / 2 猫");
        }
    }

    /** 可见范围：已发布的对所有人可见；待审 / 被拒的只有作者可见；其余一律 40400。 */
    private CommunityCard requireVisibleCard(long viewerId, long cardId) {
        CommunityCard card = cardMapper.selectById(cardId);
        if (card == null) {
            throw BusinessException.notFound("卡片不存在或已删除");
        }
        if (!card.isPubliclyVisible() && (card.getAuthorId() == null || card.getAuthorId() != viewerId)) {
            throw BusinessException.notFound("卡片不存在或已删除");
        }
        return card;
    }

    /** 点赞的准入：**只有已发布的卡片能被点赞**（待审 / 被拒的别人看不见，也就点不到）。 */
    private void requirePublicCard(long cardId) {
        CommunityCard card = cardMapper.selectById(cardId);
        if (card == null || !card.isPubliclyVisible()) {
            throw BusinessException.notFound("卡片不存在或未发布");
        }
    }

    private boolean likedExists(long viewerId, long cardId) {
        return likeMapper.selectCount(Wrappers.<CommunityCardLike>lambdaQuery()
                .eq(CommunityCardLike::getCardId, cardId)
                .eq(CommunityCardLike::getUserId, viewerId)) > 0;
    }

    /** 一页卡片的点赞状态批量查（一页 20 张不该有 20 次查询）。 */
    private Set<Long> likedCardIds(long viewerId, List<CommunityCard> cards) {
        if (cards.isEmpty()) {
            return Set.of();
        }
        Set<Long> cardIds = new LinkedHashSet<>();
        cards.forEach(card -> cardIds.add(card.getId()));
        List<CommunityCardLike> likes = likeMapper.selectList(Wrappers.<CommunityCardLike>lambdaQuery()
                .eq(CommunityCardLike::getUserId, viewerId)
                .in(CommunityCardLike::getCardId, cardIds));
        Set<Long> liked = new LinkedHashSet<>();
        likes.forEach(like -> liked.add(like.getCardId()));
        return liked;
    }
}
