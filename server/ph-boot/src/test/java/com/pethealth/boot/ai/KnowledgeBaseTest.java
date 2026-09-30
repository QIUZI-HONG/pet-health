package com.pethealth.boot.ai;

import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库结构与三层检索在**真 MySQL** 上的验收（切片 #100 / ADR-0022）。
 *
 * <p>为什么在 Java 侧测这些：检索本身实现在 Python（`ai/app/knowledge.py`），但它的三条腿都
 * 依赖**数据库能力**——ngram 全文索引、JSON 载荷、关系表的 JOIN。这些能力在内存库（H2）上
 * 跑通不代表 MySQL 跑通（ADR-0014 的理由），而 Python 侧没有连着真库的 CI 通道。
 * 于是分工是：算法与口径在 `ai/tests`，**SQL 与迁移的真实性在这里**。
 *
 * <p>代价说清楚：`ngramKeywordSearch` 里的 SQL 是 `knowledge._query_entries` 的**镜像**——
 * 两份写法必须一起改（Python 那份是产品路径，这份是「MySQL 到底支不支持」的证据）。
 * 这也是本项目唯一一处刻意重复 SQL 的地方，写在这里免得下次有人以为可以删掉其中之一。
 */
@DisplayName("知识库结构与三层检索（#100 / ADR-0022）")
class KnowledgeBaseTest extends IntegrationTestBase {

    /** 检索侧的可见状态：种子全部是 pending_review，所以两种都要取。 */
    private static final String VISIBLE = "('vetted','pending_review')";

    @Test
    @DisplayName("中文子串检索：ngram 全文索引真的能命中中文词（#63 实测结论的回归）")
    void ngramKeywordSearch() {
        // 与 ai/app/knowledge.py 的 _query_entries 同形状：布尔模式 + 短语 + 同一组过滤条件
        List<String> codes = jdbc.queryForList("""
                SELECT code FROM knowledge_entry
                 WHERE MATCH(title, summary, body) AGAINST ('"呕吐"' IN BOOLEAN MODE)
                   AND review_status IN ('vetted','pending_review') AND is_deleted = 0
                   AND species_scope IN ('all', 'dog') AND age_stage_scope IN ('all', 'adult')
                 ORDER BY MATCH(title, summary, body) AGAINST ('"呕吐"' IN BOOLEAN MODE) DESC
                 LIMIT 10
                """, String.class);

        assertThat(codes)
                .as("「呕吐」是中文子串，ngram 解析器认得它（换成默认的英文分词器这条会空）")
                .contains("K-0010");

        // 索引确实是 ngram 解析器：ngram_token_size 是只读参数，改了必须重建索引（63 号调研 §3）。
        // DDL 里它写作 `/*!50100 WITH PARSER `ngram` */`——带版本注释与反引号，
        // 所以这里分开断言（写成一个整串「WITH PARSER ngram」会匹配不到，踩过）
        String ddl = jdbc.queryForObject("SHOW CREATE TABLE knowledge_entry", (rs, rowNum) -> rs.getString(2));
        assertThat(ddl).contains("WITH PARSER").contains("ngram");
    }

    @Test
    @DisplayName("L3 过滤生效：超出物种/复核状态的条目不会被召回（犬猫混答与未复核内容是两条红线）")
    void keywordSearchRespectsScopeFilters() {
        // K-0041 是对猫有毒的植物（species_scope=cat）——用 dog 过滤时不该出现
        List<String> dogCodes = jdbc.queryForList("""
                SELECT code FROM knowledge_entry
                 WHERE MATCH(title, summary, body) AGAINST ('"百合"' IN BOOLEAN MODE)
                   AND species_scope IN ('all', 'dog')
                """, String.class);

        assertThat(dogCodes).doesNotContain("K-0041");
    }

    @Test
    @DisplayName("L1 结构化事实：疫苗/驱虫条目带结构化载荷，查询走它而不是靠模型读散文")
    void structuredFactsAreStored() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT code, category_code, structured_payload FROM knowledge_entry
                 WHERE category_code IN ('vaccine','antiparasitic','nutrition')
                   AND structured_payload IS NOT NULL AND is_deleted = 0
                 ORDER BY code
                """);

        assertThat(rows).as("三大类里都要有可判定的载荷（交付文档的分工：周期类问题走结构化查询）")
                .isNotEmpty();
        assertThat(rows).extracting(row -> row.get("category_code").toString())
                .contains("vaccine", "antiparasitic", "nutrition");
        assertThat(rows.get(0).get("structured_payload").toString()).contains("kind");
    }

    @Test
    @DisplayName("L2 关系层：症状→疾病→条目 的两跳查询可用，且每条边都能追到来源条目")
    void relationLayerIsQueryableAndTraceable() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.src_code, dst.entry_code, e.weight, e.note, e.evidence_entry_code
                  FROM knowledge_edge e
                  JOIN knowledge_node dst ON dst.code = e.dst_code
                  JOIN knowledge_entry kb ON kb.code = dst.entry_code
                 WHERE e.relation = 'may_indicate' AND e.src_code = 'SYM-VOMIT'
                   AND e.enabled = 1 AND e.is_deleted = 0
                 ORDER BY e.weight DESC
                """);

        assertThat(rows).as("「呕吐」这个症状要能经关系层引到条目上（这是换一种说法的召回手段）")
                .isNotEmpty();
        assertThat(rows.get(0).get("entry_code")).isNotNull();
        assertThat(rows.get(0).get("note").toString()).isNotBlank();

        // 一致性校验：**没有来源的边不允许存在**（63 号调研 §5.1 的建模约定）
        List<String> dangling = jdbc.queryForList("""
                SELECT e.id FROM knowledge_edge e
                 LEFT JOIN knowledge_entry kb ON kb.code = e.evidence_entry_code
                 WHERE kb.id IS NULL
                """, String.class);
        assertThat(dangling).as("每条边都必须能追到来源条目").isEmpty();
    }

    @Test
    @DisplayName("种子一律 pending_review：工程不许给没复核过的内容盖兽医的章")
    void seedsAreNotVetted() {
        Integer vetted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_entry WHERE review_status = 'vetted'", Integer.class);
        Integer reviewed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_entry WHERE review_status = 'vetted' "
                        + "AND reviewed_by IS NOT NULL", Integer.class);

        // 这条守卫的是流程而不是数据：vetted 只能由兽医复核产出（ADR-0040 第二节），
        // 工程为了让 citations 有内容而给种子盖章，正是「在代码里假造复核状态」
        assertThat(vetted).as("种子里不该有 vetted（复核流程还没落地）").isZero();
        assertThat(reviewed).isZero();
    }

    @Test
    @DisplayName("类目字典覆盖交付文档的十项（F024/F025 的品种库与疾病/行为/营养/护理/急救/药物库）")
    void categoryDictionaryCoversAllTen() {
        List<String> codes = jdbc.queryForList(
                "SELECT code FROM knowledge_category WHERE enabled = 1 ORDER BY sort_order", String.class);

        assertThat(codes).containsExactlyInAnyOrder(
                "breed", "vaccine", "antiparasitic", "triage", "disease",
                "behavior", "nutrition", "care", "first_aid", "drug");

        // 每个类目至少有一条可用条目：空类目在界面上与「没做」没有区别
        List<String> empty = jdbc.queryForList("""
                SELECT c.code FROM knowledge_category c
                 LEFT JOIN knowledge_entry e ON e.category_code = c.code AND e.is_deleted = 0
                 GROUP BY c.code HAVING COUNT(e.id) = 0
                """, String.class);
        assertThat(empty).as("这些类目一条条目都没有").isEmpty();
    }

    @Test
    @DisplayName("向量层是「留了接口没实现」：分块表在、embedding_model 列为空、检索侧不读它")
    void vectorLayerIsDeferredNotForgotten() {
        Integer chunks = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class);
        Integer embedded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunk WHERE embedding_model IS NOT NULL", Integer.class);

        // 表在（换嵌入模型时不必再补迁移）但没有一条向量化的分块——
        // ADR-0022 的「留接口不实现」在数据上是可见的，不是一句声明
        assertThat(chunks).isNotNull();
        assertThat(embedded).as("向量层挂起：不该有任何 embedding_model 被写上").isZero();
    }
}
