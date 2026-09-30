package com.pethealth.boot.contract;

import com.pethealth.boot.contract.ContractDtoDrift.Finding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判定规则的自测：每条规则各造一个**最小契约**，DTO 用 ph-api 里真实的类
 * （因此不需要改任何生产代码，也不需要造假的 DTO）。
 *
 * <p>为什么必须单独有这么一层：{@link ContractDtoDriftTest} 全绿有两种含义——
 * 「两边真的对齐」或者「校验根本没比」。这一层用「已知会红的契约」把第二种可能钉死：
 * 该红的必须红（含内联对象的递归），不该红的必须一处都不多。
 *
 * <p>契约内容全部写在 {@code @TempDir} 里，仓库里的 {@code contract/**} 不动一个字。
 */
class ContractDtoDriftRulesTest {

    /** 期望的差异清单，用的是与基线文件同一套键：{@code 文件|schema|字段路径|差异类型}。 */
    private static final Set<String> EXPECTED = Set.of(
            // 契约有 schema、ph-api 里没有对应类
            "admin.yaml|GhostSchema||SCHEMA_WITHOUT_DTO",
            // 类名漂移：契约叫 Pet，DTO 是 PetView（字段全对，所以只有这一条）
            "app.yaml|Pet||SCHEMA_CLASS_NAME_MISMATCH",
            // 漏实现：契约有 ghost，DTO（CareModeRequest）只有 enabled
            "app.yaml|CareModeRequest|ghost|MISSING_IN_DTO",
            // 未声明字段：DTO（ReminderSettingRequest）有 enabled，契约只声明了 type
            "app.yaml|ReminderSettingRequest|enabled|UNDECLARED_IN_DTO",
            // 同一处未声明字段必然伴随「DTO 有必填注解、契约没写 required」——两条都要出，不许只出一条
            "app.yaml|ReminderSettingRequest|enabled|REQUIRED_NOT_DECLARED",
            // 必填未声明：DTO 的 refresh_token 带 @NotBlank，契约没写 required
            "app.yaml|LogoutRequest|refresh_token|REQUIRED_NOT_DECLARED",
            // 必填未约束：契约把 breed 写进 required，DTO 的 breed 只有 @Size、没有 @NotNull
            "app.yaml|PetCreateRequest|breed|REQUIRED_NOT_ENFORCED",
            // 形状不一致：契约说是数组 / 对象，DTO 是 String / long
            "app.yaml|FilePresignView|upload_url|SHAPE_MISMATCH",
            "app.yaml|FilePresignView|max_bytes|SHAPE_MISMATCH",
            // 内联对象向下递归：items 的匿名元素形状也要比（漏 role、size_bytes 必填未约束）
            "app.yaml|FilePresignRequest|items[].role|UNDECLARED_IN_DTO",
            "app.yaml|FilePresignRequest|items[].size_bytes|REQUIRED_NOT_ENFORCED",
            // 契约把 snake_case 写成了 camelCase：漏实现 + 未声明字段 + 必填未声明，三条一起出
            // （同一处漂移在不同角度各记一条，比只报一条更容易让人看清是「拼法」而不是「改了字段」）
            "app.yaml|RefreshRequest|refreshToken|MISSING_IN_DTO",
            "app.yaml|RefreshRequest|refresh_token|UNDECLARED_IN_DTO",
            "app.yaml|RefreshRequest|refresh_token|REQUIRED_NOT_DECLARED"
    );

    @TempDir
    Path contractDir;

    @BeforeEach
    void writeFixtures() throws IOException {
        write("app.yaml", APP_YAML);
        write("provider.yaml", PROVIDER_YAML);
        write("admin.yaml", ADMIN_YAML);
        write("common.yaml", "openapi: 3.1.0\ncomponents:\n  schemas: {}\n");
    }

    @Test
    @DisplayName("每条规则在该红的地方红、在不该红的地方不红（含内联对象递归与跨文件 $ref）")
    void rulesFireExactly() {
        List<Finding> findings = ContractDtoDrift.scan(ContractDocuments.load(contractDir), DtoIndex.scan())
                .findings();

        // 「ph-api 有 DTO、契约无 schema」在最小契约里必然成片出现（契约只声明了几张），
        // 单独用 AiConsultCitation 断言它在——其余的和 EXPECTED 一起精确比对。
        Set<String> actual = findings.stream()
                .filter(f -> f.kind() != ContractDtoDrift.Kind.DTO_WITHOUT_SCHEMA)
                .map(Finding::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED);

        assertThat(findings.stream()
                .filter(f -> f.kind() == ContractDtoDrift.Kind.DTO_WITHOUT_SCHEMA)
                .map(Finding::schema)
                .toList())
                .as("契约里一个字都没提的 ph-api 类要报成「契约无对应 schema」")
                .contains("AiConsultCitation");
    }

    @Test
    @DisplayName("契约属性写成 camelCase 时，详情里要指出 DTO 里最像它的是哪个字段")
    void namingDriftPointsAtTheLikelyCulprit() {
        List<Finding> findings = ContractDtoDrift.scan(ContractDocuments.load(contractDir), DtoIndex.scan())
                .findings();

        assertThat(findings)
                .filteredOn(f -> f.kind() == ContractDtoDrift.Kind.MISSING_IN_DTO
                        && f.schema().equals("RefreshRequest"))
                .singleElement()
                .extracting(Finding::detail)
                .asString()
                .as("只说「缺 refreshToken」等于把找字段的活又推回给人")
                .contains("DTO 里最像它的是 `refreshToken → refresh_token`");
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(contractDir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private static final String APP_YAML = """
            openapi: 3.1.0
            components:
              schemas:
                # 类名漂移：契约叫 Pet，DTO 是 PetView；字段逐个对得上，所以只该出一条改名差异
                Pet:
                  type: object
                  required: [id, name, species, gender]
                  properties:
                    id: { type: integer, format: int64 }
                    name: { type: string }
                    species: { type: integer }
                    breed: { type: string }
                    gender: { type: integer }
                    birthday: { type: string }
                    weight: { type: string }
                    avatar: { type: string }
                    is_sterilized: { type: boolean }
                    is_chronic: { type: boolean }
                    chronic_desc: { type: string }
                    restorable_until: { type: string }
                    created_at: { type: string }
                    updated_at: { type: string }

                # 对照 1：请求体完全对齐（字段、必填、形状），一处都不该报
                ActivatePetRequest:
                  type: object
                  required: [pet_id]
                  properties:
                    pet_id: { type: integer, format: int64 }

                # 对照 2：响应体的 required 不参与判定（DTO 上没有校验注解是正常的），
                # 且 is_ 前缀的 @JsonProperty 要能被认出来
                TokenPair:
                  type: object
                  required: [access_token, refresh_token, expires_in, user]
                  properties:
                    access_token: { type: string }
                    refresh_token: { type: string }
                    expires_in: { type: integer }
                    user: { $ref: "#/components/schemas/UserProfile" }

                # 契约属性误写成 camelCase：与 DTO 的 refresh_token 对不上
                RefreshRequest:
                  type: object
                  required: [refreshToken]
                  properties:
                    refreshToken: { type: string }

                # 单测里被引用的对照：跨文件 $ref 的目标
                UserProfile:
                  type: object
                  required: [id, phone, nickname, gender]
                  properties:
                    id: { type: integer, format: int64 }
                    phone: { type: string }
                    nickname: { type: string }
                    avatar: { type: string }
                    gender: { type: integer }
                    active_pet_id: { type: integer, format: int64 }
                    created_at: { type: string }

                # 漏实现：契约多一个字段
                CareModeRequest:
                  type: object
                  required: [enabled]
                  properties:
                    enabled: { type: boolean }
                    ghost: { type: string }

                # 未声明字段：DTO 有 type 与 enabled，契约只写 type
                ReminderSettingRequest:
                  type: object
                  required: [type]
                  properties:
                    type: { type: integer }

                # 必填未声明：DTO 带 @NotBlank，契约没写 required
                LogoutRequest:
                  type: object
                  properties:
                    refresh_token: { type: string }

                # 必填未约束：契约 required 多写了 breed（DTO 的 breed 只有 @Size）
                PetCreateRequest:
                  type: object
                  required: [name, species, breed]
                  properties:
                    name: { type: string }
                    species: { type: integer }
                    breed: { type: string }
                    gender: { type: integer }
                    birthday: { type: string }
                    weight: { type: string }
                    avatar: { type: string }
                    is_sterilized: { type: boolean }
                    is_chronic: { type: boolean }
                    chronic_desc: { type: string }

                # 形状不一致：数组对 String、对象对 long；file_id / expires_at 的 required 不判
                FilePresignView:
                  type: object
                  required: [file_id, expires_at]
                  properties:
                    file_id: { type: integer, format: int64 }
                    upload_url:
                      type: array
                      items: { type: string }
                    expires_at: { type: string }
                    max_bytes:
                      type: object
                      properties:
                        n: { type: integer }
                    role: { type: string }

                # 内联对象：items 的元素是匿名对象，要按 DTO 的类型往下比一层
                FilePresignRequest:
                  type: object
                  required: [biz_type, items]
                  properties:
                    biz_type: { type: string }
                    pet_id: { type: integer, format: int64 }
                    items:
                      type: array
                      items:
                        type: object
                        required: [mime, size_bytes]
                        properties:
                          mime: { type: string }
                          size_bytes: { type: integer, format: int64 }
            """;

    private static final String PROVIDER_YAML = """
            openapi: 3.1.0
            components:
              schemas:
                # 跨文件 $ref：被引用的 schema 自己有名字，会单独比，这里不该额外报什么
                TokenPair:
                  type: object
                  required: [user]
                  properties:
                    access_token: { type: string }
                    refresh_token: { type: string }
                    expires_in: { type: integer }
                    user: { $ref: "./app.yaml#/components/schemas/UserProfile" }
            """;

    private static final String ADMIN_YAML = """
            openapi: 3.1.0
            components:
              schemas:
                ServiceItemStatusRequest:
                  type: object
                  required: [status]
                  properties:
                    status: { type: integer }

                # 契约声明了、ph-api 里根本没有这个类（接口未实现或类名漂得看不出来）
                GhostSchema:
                  type: object
                  properties:
                    x: { type: string }
            """;
}
