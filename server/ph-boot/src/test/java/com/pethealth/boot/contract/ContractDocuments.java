package com.pethealth.boot.contract;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 读 {@code contract/*.yaml} 并解析 {@code $ref}。
 *
 * <p><b>为什么用 SnakeYAML</b>：{@code spring-boot-starter} 已经带了它
 * （{@code org.yaml:snakeyaml}，Spring Boot 解析 {@code application.yml} 用的就是它），
 * 而仓库里没有 {@code jackson-dataformat-yaml}。这条护栏**不新增任何依赖**——
 * 一个只读契约的静态校验没有理由改变运行时的依赖图。
 *
 * <p><b>只读</b>：契约在本项目里是只读输入（ADR-0047：同一时刻只有一个写入者，且写入者是协调者）。
 * 这个类只把 YAML 读成 {@code Map}/{@code List}/标量，然后按 JSON Pointer 走路，
 * 不做任何 schema 推导——推导出来的形状与 DTO 比会引入「工具自己的解释」，反而看不清差异。
 */
final class ContractDocuments {

    /** 被校验的契约文件（common.yaml 是信封与分页，DTO 在 ph-common，由 ContractJsonTest 守）。 */
    static final List<String> SCANNED_FILES = List.of("app.yaml", "provider.yaml", "admin.yaml");

    /** 文件名 → YAML 根节点。contract/ 下的**全部** yaml 都读：跨文件 $ref 要用到 common.yaml。 */
    private final Map<String, Map<String, Object>> documents = new LinkedHashMap<>();

    static ContractDocuments load(Path contractDir) {
        ContractDocuments docs = new ContractDocuments();
        try (Stream<Path> files = Files.list(contractDir)) {
            List<Path> yamlFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
            if (yamlFiles.isEmpty()) {
                throw new IllegalStateException("contract/ 下没有 yaml：" + contractDir);
            }
            for (Path p : yamlFiles) {
                docs.read(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("列不出 " + contractDir, e);
        }
        for (String scanned : SCANNED_FILES) {
            if (!docs.documents.containsKey(scanned)) {
                throw new IllegalStateException("契约目录里缺少 " + scanned + "：" + contractDir);
            }
        }
        return docs;
    }

    private void read(Path file) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream in = Files.newInputStream(file)) {
            Object root = yaml.load(in);
            documents.put(file.getFileName().toString(), asMap(root, file.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("读不到契约文件 " + file, e);
        }
    }

    /**
     * 某个契约文件的 {@code components.schemas}，保持**声明顺序**（LinkedHashMap）——
     * 报告按声明顺序出，读起来与契约文件一一对应。
     */
    Map<String, Map<String, Object>> schemas(String file) {
        Map<String, Object> raw = at(file, "/components/schemas", file);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        raw.forEach((name, node) -> out.put(name, asMap(node, file + " #/components/schemas/" + name)));
        return out;
    }

    /**
     * 沿 {@code $ref} 走到真正的节点（支持 {@code #/...} 同文件与 {@code ./common.yaml#/...} 跨文件）。
     *
     * <p>返回的是**被引用那个节点本身**，不是副本；调用方只读，别改。
     */
    Map<String, Object> resolve(String file, Map<String, Object> node, String where) {
        Set<String> seen = new LinkedHashSet<>();
        Map<String, Object> current = node;
        while (current.get("$ref") instanceof String ref) {
            if (!seen.add(ref)) {
                throw new IllegalStateException("$ref 成环：" + seen + "（" + where + "）");
            }
            current = follow(file, ref, where);
        }
        return current;
    }

    private Map<String, Object> follow(String file, String ref, String where) {
        int hash = ref.indexOf('#');
        String targetPointer = hash < 0 ? "" : ref.substring(hash + 1);
        if (hash <= 0) {
            return at(file, targetPointer, where);
        }
        String targetFile = ref.substring(0, hash);
        if (targetFile.startsWith("./")) {
            targetFile = targetFile.substring(2);
        }
        if (!documents.containsKey(targetFile)) {
            throw new IllegalArgumentException(
                    "$ref 指向的文件不在 contract/ 下：" + ref + "（" + where + "）");
        }
        return at(targetFile, targetPointer, where);
    }

    /** JSON Pointer 走路（只用到 {@code /a/b/c} 与数组下标，够用且不会误解契约）。 */
    private Map<String, Object> at(String file, String pointer, String where) {
        Object node = documents.get(file);
        if (node == null) {
            throw new IllegalArgumentException("contract/ 里没有 " + file + "（$ref 或文件名写错了？）：" + where);
        }
        for (String token : pointer.split("/")) {
            if (token.isEmpty()) {
                continue;
            }
            String key = token.replace("~1", "/").replace("~0", "~");
            if (node instanceof List<?> list) {
                int index = Integer.parseInt(key);
                if (index < 0 || index >= list.size()) {
                    throw new IllegalArgumentException("指针越界：" + pointer + "（" + where + "）");
                }
                node = list.get(index);
            } else if (node instanceof Map<?, ?> map) {
                if (!map.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "指针走不通：" + pointer + " 里没有 " + key + "（" + where + "）");
                }
                node = map.get(key);
            } else {
                throw new IllegalArgumentException("指针走进了标量：" + pointer + "（" + where + "）");
            }
        }
        return asMap(node, where);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node, String where) {
        if (!(node instanceof Map)) {
            throw new IllegalArgumentException("期望是对象（mapping），实际是 " + node + "：" + where);
        }
        return (Map<String, Object>) node;
    }
}
