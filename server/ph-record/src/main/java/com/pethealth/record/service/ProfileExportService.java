package com.pethealth.record.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.record.api.ProfileExportApi;
import com.pethealth.record.domain.ArchiveRecord;
import com.pethealth.record.domain.HealthReport;
import com.pethealth.record.domain.HealthScore;
import com.pethealth.record.domain.Pet;
import com.pethealth.record.mapper.ArchiveRecordMapper;
import com.pethealth.record.mapper.HealthReportMapper;
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
    private final HealthReportMapper reportMapper;

    public ProfileExportService(PetMapper petMapper, ArchiveRecordMapper recordMapper,
                                HealthScoreMapper scoreMapper, HealthReportMapper reportMapper) {
        this.petMapper = petMapper;
        this.recordMapper = recordMapper;
        this.scoreMapper = scoreMapper;
        this.reportMapper = reportMapper;
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
                recordsOf(pet.getId()), scoresOf(pet.getId()), reportsOf(pet.getId()))).toList();
    }

    /**
     * 一条记录导出成一个扁平的 {@code content}：打卡与防疫取 {@code content}，
     * 分项记录取 {@code structured_payload}（ADR-0030 第五条）。
     *
     * <p>为什么合成一列：导出视图是给人工与医院看的原始记录，而「分项载荷在哪一列」
     * 是平台内部的字段划分——把它摊到导出的结构里，接收方要按列名猜内容类型。
     */
    private List<RecordExport> recordsOf(long petId) {
        return recordMapper.selectList(Wrappers.<ArchiveRecord>lambdaQuery()
                        .eq(ArchiveRecord::getPetId, petId)
                        .orderByAsc(ArchiveRecord::getRecordDate))
                .stream()
                .map(record -> new RecordExport(record.getRecordDate(), record.getCategory(),
                        record.getStructuredPayload() != null
                                ? record.getStructuredPayload() : record.getContent(),
                        record.getSource(), record.getDueOn(), record.getNumericValue()))
                .toList();
    }

    /** 健康报告（ADR-0031 决定六）。按周期正序导出，没有报告就是空列表。 */
    private List<ReportExport> reportsOf(long petId) {
        return reportMapper.selectList(Wrappers.<HealthReport>lambdaQuery()
                        .eq(HealthReport::getPetId, petId)
                        .orderByAsc(HealthReport::getPeriodStart))
                .stream()
                .map(report -> new ReportExport(report.getType(),
                        report.getType() == HealthReport.TYPE_MONTHLY ? "健康月报" : "健康周报",
                        report.getPeriodStart(), report.getPeriodEnd(), report.getGrade(),
                        report.getTotalScore(), ReportPayloads.read(report.getPayload())))
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
     * 注销时软删该用户的全部宠物**与它们名下的档案**（打卡/防疫记录、健康评分）。
     *
     * <p>三条都做，是因为契约里写的就是「宠物与档案软删」（`app.yaml` 的注销说明），
     * 而早先的实现只软删了宠物——档案还在，只是没人再查得到（2026-09-28 测试报告 D12）。
     *
     * <p>宠物走 {@link PetMapper#softDelete} 而不是 {@code deleteById}：前者会写 {@code deleted_at}，
     * 也就是「30 天内可恢复」这个承诺的起点；漏写会让这只宠物既不在列表里、也不在回收站里。
     *
     * <p>不物理删除：注销后用户仍可能行使「导出」权利，历史也另有留痕用途；
     * 物理删除的窗口留给数据清理任务（V9 的迁移注释里写了这条分工）。
     */
    @Override
    @Transactional
    public int softDeleteAll(long userId) {
        List<Pet> pets = petMapper.selectList(Wrappers.<Pet>lambdaQuery().eq(Pet::getUserId, userId));
        var now = AppTime.now();
        long operatorId = TraceIds.currentOperatorId();
        String traceId = TraceIds.currentTraceId();
        for (Pet pet : pets) {
            recordMapper.softDeleteByPet(pet.getId(), now, operatorId, traceId);
            reportMapper.update(null, Wrappers.<HealthReport>lambdaUpdate()
                    .eq(HealthReport::getPetId, pet.getId())
                    .set(HealthReport::getIsDeleted, 1)
                    .set(HealthReport::getUpdatedAt, now)
                    .set(HealthReport::getUpdatedBy, operatorId)
                    .set(HealthReport::getTraceId, traceId));
            scoreMapper.update(null, Wrappers.<HealthScore>lambdaUpdate()
                    .eq(HealthScore::getPetId, pet.getId())
                    .set(HealthScore::getIsDeleted, 1)
                    .set(HealthScore::getUpdatedAt, now)
                    .set(HealthScore::getUpdatedBy, operatorId)
                    .set(HealthScore::getTraceId, traceId));
            petMapper.softDelete(pet.getId(), userId, now, operatorId, traceId);
        }
        return pets.size();
    }
}
