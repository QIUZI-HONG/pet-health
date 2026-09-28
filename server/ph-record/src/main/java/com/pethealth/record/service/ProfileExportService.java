package com.pethealth.record.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.record.api.ProfileExportApi;
import com.pethealth.record.domain.ArchiveRecord;
import com.pethealth.record.domain.HealthScore;
import com.pethealth.record.domain.Pet;
import com.pethealth.record.mapper.ArchiveRecordMapper;
import com.pethealth.record.mapper.HealthScoreMapper;
import com.pethealth.record.mapper.PetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 数据导出与注销时的档案处理（切片 #74）。
 *
 * <p>读的是本模块自己的表（ADR-0006 允许），对外只暴露接口，别的模块不碰这三张表。
 */
@Service
public class ProfileExportService implements ProfileExportApi {

    private final PetMapper petMapper;
    private final ArchiveRecordMapper recordMapper;
    private final HealthScoreMapper scoreMapper;

    public ProfileExportService(PetMapper petMapper, ArchiveRecordMapper recordMapper,
                                HealthScoreMapper scoreMapper) {
        this.petMapper = petMapper;
        this.recordMapper = recordMapper;
        this.scoreMapper = scoreMapper;
    }

    @Override
    public List<PetExport> exportOf(long userId) {
        List<Pet> pets = petMapper.selectList(Wrappers.<Pet>lambdaQuery()
                .eq(Pet::getUserId, userId)
                .orderByAsc(Pet::getId));
        return pets.stream().map(pet -> new PetExport(
                pet.getId(), pet.getName(), pet.getSpecies(), pet.getBreed(), pet.getGender(),
                pet.getBirthday(), pet.getWeight(),
                pet.getIsSterilized() != null && pet.getIsSterilized() == 1,
                pet.getChronicDesc(),
                recordsOf(pet.getId()), scoresOf(pet.getId()))).toList();
    }

    private List<RecordExport> recordsOf(long petId) {
        return recordMapper.selectList(Wrappers.<ArchiveRecord>lambdaQuery()
                        .eq(ArchiveRecord::getPetId, petId)
                        .orderByAsc(ArchiveRecord::getRecordDate))
                .stream()
                .map(record -> new RecordExport(record.getRecordDate(), record.getCategory(),
                        record.getContent(), record.getSource(), record.getDueOn(),
                        record.getNumericValue()))
                .toList();
    }

    private List<ScoreExport> scoresOf(long petId) {
        return scoreMapper.selectList(Wrappers.<HealthScore>lambdaQuery()
                        .eq(HealthScore::getPetId, petId)
                        .orderByAsc(HealthScore::getCalcDate))
                .stream()
                .map(score -> new ScoreExport(score.getCalcDate(), score.getTotalScore(),
                        score.getPhysiology(), score.getBehavior(), score.getHygiene(),
                        score.getEpidemic(), score.getElderly()))
                .toList();
    }

    /**
     * 注销时软删全部宠物。
     *
     * <p>**用软删而不是物理删**：注销后用户仍可能行使「导出」权利，也可能有历史订单要留痕；
     * 物理删除的窗口留给数据清理任务（V9 的迁移注释里写了这条分工）。
     */
    @Override
    @Transactional
    public int softDeleteAll(long userId) {
        List<Pet> pets = petMapper.selectList(Wrappers.<Pet>lambdaQuery().eq(Pet::getUserId, userId));
        for (Pet pet : pets) {
            petMapper.deleteById(pet.getId());
        }
        return pets.size();
    }
}
