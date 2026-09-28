package com.pethealth.record.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.app.PetCreateRequest;
import com.pethealth.api.app.PetUpdateRequest;
import com.pethealth.api.app.PetView;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.record.api.AiPetApi;
import com.pethealth.record.api.PetQueryApi;
import com.pethealth.record.domain.Pet;
import com.pethealth.record.domain.Weight;
import com.pethealth.record.mapper.PetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 宠物档案：建档、编辑、多宠、软删除与 30 天恢复（切片 #94）。
 *
 * <p>两条贯穿全类的规则：
 *
 * <ul>
 *   <li><b>归属校验</b>：所有单只宠物的读写都带 {@code user_id}，查不到就是 40400。
 *       「不存在」与「不是他的」对外是同一个结果（契约里写明了，别改成 403）。
 *   <li><b>恢复期</b>：软删除后 30 天内可恢复，边界由 {@link #RESTORE_WINDOW_DAYS} 决定。
 * </ul>
 */
@Service
public class PetService implements PetQueryApi, AiPetApi {

    /** 交付文档与用户故事 5 都写明「30 天内可恢复」。 */
    public static final int RESTORE_WINDOW_DAYS = 30;

    private final PetMapper petMapper;

    public PetService(PetMapper petMapper) {
        this.petMapper = petMapper;
    }

    @Transactional(readOnly = true)
    public List<PetView> list(long userId, boolean recycleBin) {
        if (recycleBin) {
            return petMapper.selectRecycleBin(userId, restoreDeadline()).stream()
                    .map(PetService::toView)
                    .toList();
        }
        // 排序：时间倒序是项目默认（docs/conventions.md）；再按 id 兜底，保证顺序稳定
        List<Pet> pets = petMapper.selectList(
                Wrappers.<Pet>lambdaQuery()
                        .eq(Pet::getUserId, userId)
                        .orderByDesc(Pet::getCreatedAt)
                        .orderByDesc(Pet::getId));
        return pets.stream().map(PetService::toView).toList();
    }

    @Transactional(readOnly = true)
    public PetView get(long userId, long petId) {
        return toView(requireOwned(userId, petId));
    }

    @Transactional
    public PetView create(long userId, PetCreateRequest request) {
        // 三个布尔字段都可空：没有默认值时按「否」处理，别直接拆箱（null 会 NPE）
        boolean chronic = Boolean.TRUE.equals(request.isChronic());
        boolean sterilized = Boolean.TRUE.equals(request.isSterilized());
        validateChronic(chronic, request.chronicDesc());
        validateBirthday(request.birthday());

        Pet pet = new Pet();
        pet.setUserId(userId);
        pet.setName(request.name().trim());
        pet.setSpecies(request.species());
        pet.setBreed(trimToNull(request.breed()));
        pet.setGender(request.gender() == null ? 0 : request.gender());
        pet.setBirthday(request.birthday());
        pet.setWeight(toWeight(request.weight()));
        pet.setAvatar(trimToNull(request.avatar()));
        pet.setIsSterilized(sterilized ? 1 : 0);
        pet.setIsChronic(chronic ? 1 : 0);
        pet.setChronicDesc(chronic ? trimToNull(request.chronicDesc()) : null);

        petMapper.insert(pet);
        return toView(pet);
    }

    @Transactional
    public PetView update(long userId, long petId, PetUpdateRequest request) {
        Pet pet = requireOwned(userId, petId);

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw BusinessException.paramInvalid("宠物昵称不能为空");
            }
            pet.setName(request.name().trim());
        }
        if (request.species() != null) {
            pet.setSpecies(request.species());
        }
        if (request.breed() != null) {
            pet.setBreed(trimToNull(request.breed()));
        }
        if (request.gender() != null) {
            pet.setGender(request.gender());
        }
        if (request.birthday() != null) {
            validateBirthday(request.birthday());
            pet.setBirthday(request.birthday());
        }
        if (request.weight() != null) {
            pet.setWeight(toWeight(request.weight()));
        }
        if (request.avatar() != null) {
            pet.setAvatar(trimToNull(request.avatar()));
        }
        if (request.isSterilized() != null) {
            pet.setIsSterilized(request.isSterilized() ? 1 : 0);
        }
        if (request.isChronic() != null || request.chronicDesc() != null) {
            boolean chronic = request.isChronic() != null ? request.isChronic() : pet.isChronicFlag();
            String desc = request.chronicDesc() != null ? trimToNull(request.chronicDesc()) : pet.getChronicDesc();
            validateChronic(chronic, desc);
            pet.setIsChronic(chronic ? 1 : 0);
            // 取消慢病标记时把描述一起清掉，避免留下一条「没病但有病史」的脏数据
            pet.setChronicDesc(chronic ? desc : null);
        }

        petMapper.updateById(pet);
        return toView(pet);
    }

    @Transactional
    public void delete(long userId, long petId) {
        int affected = petMapper.softDelete(petId, userId, AppTime.now(),
                TraceIds.currentOperatorId(), TraceIds.currentTraceId());
        if (affected == 0) {
            throw BusinessException.notFound();
        }
    }

    @Transactional
    public PetView restore(long userId, long petId) {
        int affected = petMapper.restore(petId, userId, restoreDeadline(), AppTime.now(),
                TraceIds.currentOperatorId(), TraceIds.currentTraceId());
        if (affected == 0) {
            throw BusinessException.notFound("宠物不存在，或已超过 " + RESTORE_WINDOW_DAYS + " 天恢复期");
        }
        return toView(requireOwned(userId, petId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsOwnedBy(long userId, long petId) {
        return petMapper.exists(Wrappers.<Pet>lambdaQuery()
                .eq(Pet::getId, petId)
                .eq(Pet::getUserId, userId));
    }

    /**
     * 归属校验：不存在与越权都抛 40400（契约里写明了，别改成 403）。
     *
     * <p>public 是给同模块的其它服务与控制器用的（例如评分要拿到宠物算年龄、打卡要校验归属），
     * 本模块之外不要用——跨模块要走 {@link PetQueryApi}。
     */
    /**
     * 宠物快照（{@link AiPetApi}）：给 ph-ai 组装 AI 请求用。
     *
     * <p>越权与不存在一律返回空——调用方按 40400 处理，不区分两种情况（docs/conventions.md）。
     */
    @Override
    public java.util.Optional<AiPetApi.PetSnapshot> snapshotOwnedBy(long userId, long petId) {
        Pet pet = petMapper.selectOne(Wrappers.<Pet>lambdaQuery()
                .eq(Pet::getId, petId)
                .eq(Pet::getUserId, userId));
        if (pet == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new AiPetApi.PetSnapshot(
                pet.getId(),
                pet.getUserId(),
                pet.getSpecies(),
                pet.getBreed(),
                pet.getBirthday(),
                pet.getWeight(),
                pet.getIsChronic() != null && pet.getIsChronic() == 1 ? pet.getChronicDesc() : null));
    }

    public Pet requireOwned(long userId, long petId) {
        Pet pet = petMapper.selectOne(Wrappers.<Pet>lambdaQuery()
                .eq(Pet::getId, petId)
                .eq(Pet::getUserId, userId));
        if (pet == null) {
            throw BusinessException.notFound();
        }
        return pet;
    }

    private LocalDateTime restoreDeadline() {
        return AppTime.now().minusDays(RESTORE_WINDOW_DAYS);
    }

    private static PetView toView(Pet pet) {
        boolean deleted = pet.getIsDeleted() != null && pet.getIsDeleted() == 1;
        return new PetView(
                pet.getId(),
                pet.getName(),
                pet.getSpecies(),
                pet.getBreed(),
                pet.getGender(),
                pet.getBirthday(),
                pet.getWeight() == null ? null : pet.getWeight().toPlainString(),
                pet.getAvatar(),
                pet.isSterilizedFlag(),
                pet.isChronicFlag(),
                pet.getChronicDesc(),
                deleted && pet.getDeletedAt() != null
                        ? pet.getDeletedAt().plusDays(RESTORE_WINDOW_DAYS)
                        : null,
                pet.getCreatedAt(),
                pet.getUpdatedAt());
    }

    private void validateChronic(boolean chronic, String desc) {
        if (chronic && (desc == null || desc.isBlank())) {
            throw BusinessException.paramInvalid("标记为慢病时必须填写慢病描述");
        }
    }

    /** 生日不能晚于今天——将来还能再校验一条「不能早于 1990」，现在没有依据，不编。 */
    private void validateBirthday(LocalDate birthday) {
        if (birthday != null && birthday.isAfter(AppTime.today())) {
            throw BusinessException.paramInvalid("生日不能晚于今天");
        }
    }

    /**
     * 体重解析与校验都走 {@link Weight}——范围（0.01–999.99）与形状只在那一个类里定义。
     *
     * <p>这里只补一件事：**归一化到两位小数**。DB 是 {@code DECIMAL(6,2)}，读回来的值天然是两位；
     * 而建档/编辑的响应用的是内存里的实体，不归一化就会返回 {@code "12.5"}——同一个字段两个接口
     * 给出两种格式，前端没法写。（{@link Weight} 已限死最多两位小数，所以这里不会截断用户的值。）
     */
    private BigDecimal toWeight(String weight) {
        BigDecimal parsed = Weight.parse(weight);
        return parsed == null ? null : parsed.setScale(2, RoundingMode.HALF_UP);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
