package com.pethealth.boot.contract;

import com.pethealth.boot.contract.DtoIndex.DtoField;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * 契约（{@code components/schemas}）与 {@code ph-api} DTO 的**静态比对**。
 *
 * <p>比对的四条规则，逐条都能追溯到 ADR-0047 与 conventions.md：
 *
 * <ol>
 *   <li><b>名字</b>：schema 名 ↔ 类名一一对应（conventions.md 的命名表）。找不到同名类时，
 *       退一步试 {@code XxxView} / {@code XxxRequest} 并**记一条改名漂移**——
 *       退这一步是为了不把「类名漂了」误报成「整张 schema 没实现」，字段照样逐项比。</li>
 *   <li><b>字段名</b>：契约属性是 snake_case 的 JSON 名；DTO 侧取 record 组件名，按运行时同一套
 *       Jackson {@code SNAKE_CASE} 规则换算，显式 {@code @JsonProperty} 优先。</li>
 *   <li><b>数量</b>：契约有而 DTO 没有 = 漏实现；DTO 有而契约没有 = 未声明字段。</li>
 *   <li><b>必填性</b>：只对请求体（schema 名以 {@code Request} 结尾）判。契约 {@code required}
 *       ↔ Java 的 {@code @NotNull/@NotBlank/@NotEmpty}，两个方向都判。
 *       <p><b>为什么响应体不判必填</b>：响应侧没有诚实的 Java 对应物——record 组件都可为 null，
 *       契约的 {@code required} 说的是「这个字段总会下发」，而「总会下发」在静态代码里读不出来
 *       （读得出来的是运行时，那属于 {@code ContractJsonTest}）。硬判会产出几百条噪音，
 *       噪音会让人把这条护栏整个关掉，那才是真的输。</li>
 * </ol>
 *
 * <p>内联（匿名）对象会**向下递归**：契约里 {@code items: {type: object, properties: ...}} 这种
 * 写法没有名字，没法单独比对，就按 DTO 字段的 Java 类型往下比一层。带 {@code $ref} 的属性
 * 不递归——被引用的 schema 自己有名字，会作为一张 schema 单独比，递归只会把同一处差异报两遍。
 */
final class ContractDtoDrift {

    /** 差异分类。基线文件里写的是枚举名（英文），中文文案改了不会让基线失效。 */
    enum Kind {        SCHEMA_WITHOUT_DTO("契约有 schema、ph-api 无对应 DTO"),
        SCHEMA_CLASS_NAME_MISMATCH("类名与 schema 名不一致"),
        DTO_WITHOUT_SCHEMA("ph-api 有 DTO、契约无对应 schema"),
        MISSING_IN_DTO("漏实现：契约有、DTO 无"),
        UNDECLARED_IN_DTO("未声明字段：DTO 有、契约无"),
        REQUIRED_NOT_ENFORCED("必填未约束：契约 required、DTO 无校验注解"),
        REQUIRED_NOT_DECLARED("必填未声明：DTO 有必填注解、契约未写 required"),
        SHAPE_MISMATCH("形状不一致：对象/数组/标量对不上");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** 一处差异。{@code path} 为空表示差异在 schema 这一层（没有对应 DTO、类名不一致）。 */
    record Finding(String file, String schema, String path, Kind kind, String detail) {

        /** 基线文件里的一行；字段顺序固定，便于人工维护与 grep。 */
        String key() {
            return file + "|" + schema + "|" + path + "|" + kind.name();
        }

        String location() {
            return file + " · " + schema + (path.isEmpty() ? "" : "." + path);
        }

        String render() {
            return "[" + kind.label() + "] " + location() + System.lineSeparator() + "      " + detail;
        }
    }

    /**
     * 一次的比对结果。
     *
     * <p>{@code schemasSeen} / {@code fieldsCompared} / {@code inlineObjectsCompared} 是**计数护栏**：
     * 一个静态校验最坏的失败不是「红」，而是「因为什么都没比而绿」。这三个数与
     * {@link DtoIndex} 的「一个 record 都没扫到就抛错」一起，让「扫描范围缩水」变成红灯。
     */
    record Report(List<Finding> findings, int schemasSeen, int fieldsCompared, int inlineObjectsCompared,
                  int dtosMatched) {
    }

    /** 契约一侧的形状，只分到「对象 / 数组 / 标量」这一层。 */
    private enum Shape { OBJECT, ARRAY, SCALAR, ANY }

    /**
     * 找不到同名类时按这个顺序退一步。是 **List 不是 Set**：{@code Set.of} 的迭代顺序未定义，
     * 会让「响应 shape 认哪个类」在不同 JVM 上给出不同结果（实测踩过：EpidemicRecord 一会儿认
     * 到 EpidemicRecordRequest、一会儿认到 EpidemicRecordView）。诊断工具的结果必须可复现。
     */
    private static final List<String> KNOWN_FIELD_SUFFIXES = List.of("View", "Request");

    private final ContractDocuments docs;
    private final DtoIndex index;
    private final List<Finding> findings = new ArrayList<>();
    /** 被这次比对「用过」的 ph-api 类：用来判「DTO 有、契约无」（用过的不算孤儿）。 */
    private final Set<Class<?>> consumed = new LinkedHashSet<>();
    /** 计数护栏用，见 {@link Report}。 */
    private int schemasSeen;
    private int fieldsCompared;
    private int inlineObjectsCompared;
    private int dtosMatched;

    private ContractDtoDrift(ContractDocuments docs, DtoIndex index) {
        this.docs = docs;
        this.index = index;
    }

    static Report scan(ContractDocuments docs, DtoIndex index) {
        ContractDtoDrift drift = new ContractDtoDrift(docs, index);
        drift.compareAll();
        return new Report(List.copyOf(drift.findings), drift.schemasSeen, drift.fieldsCompared,
                drift.inlineObjectsCompared, drift.dtosMatched);
    }

    private void compareAll() {
        for (String file : ContractDocuments.SCANNED_FILES) {
            docs.schemas(file).forEach((schemaName, schemaNode) -> {
                schemasSeen++;
                compareSchema(file, schemaName, schemaNode);
            });
            // 同一张 schema 可能出现在两份契约里（provider.yaml 与 admin.yaml 共用 Coupon* 等），
            // 那是契约各写一遍的固有重复，DTO 是同一个：两份都比，差异各自记账。
        }
        reportDtosWithoutSchema();
    }

    private void compareSchema(String file, String schemaName, Map<String, Object> schemaNode) {
        Match match = matchDto(schemaName);
        if (match.dto() == null) {
            findings.add(new Finding(file, schemaName, "", Kind.SCHEMA_WITHOUT_DTO,
                    "ph-api 里找不到 " + schemaName + " / " + schemaName + "View / " + schemaName
                            + "Request 这三个类名——要么这个接口还没实现，要么类名漂了。"
                            + "（契约是只读输入：发现的差异先报告，由契约写入者裁决改哪边）"));
            return;
        }
        Class<?> dto = match.dto();
        consumed.add(dto);
        dtosMatched++;
        if (match.renamed()) {
            findings.add(new Finding(file, schemaName, "", Kind.SCHEMA_CLASS_NAME_MISMATCH,
                    "契约里叫 " + schemaName + "，ph-api 里是 " + DtoIndex.fqn(dto)
                            + "——conventions.md 要求类名与 schema 名一致，两边挑一边改名（改契约请找契约写入者）"));
        }
        boolean request = schemaName.endsWith("Request");
        compare(file, schemaName, "", schemaNode, dto, request, new ArrayDeque<>());
    }

    /** 先找同名类；找不到就试 {@code *View} / {@code *Request}，并把「改过名」记下来。 */
    private Match matchDto(String schemaName) {
        Optional<Class<?>> exact = index.topLevel(schemaName);
        if (exact.isPresent()) {
            return new Match(exact.get(), false);
        }
        for (String suffix : KNOWN_FIELD_SUFFIXES) {
            Optional<Class<?>> candidate = index.topLevel(schemaName + suffix);
            if (candidate.isPresent()) {
                return new Match(candidate.get(), true);
            }
        }
        return new Match(null, false);
    }

    private record Match(Class<?> dto, boolean renamed) {
    }

    /**
     * 比一张 schema（或内联对象）的字段。
     *
     * @param stack 当前递归路径上的类，防自引用类型把校验拖进死循环
     */
    private void compare(String file, String schemaName, String path, Map<String, Object> node,
                         Class<?> dto, boolean request, Deque<Class<?>> stack) {
        Map<String, Object> properties = childMap(node, "properties");
        List<DtoField> fields = DtoIndex.fieldsOf(dto);
        fieldsCompared += fields.size();
        if (!path.isEmpty()) {
            inlineObjectsCompared++;
        }
        Map<String, DtoField> dtoByName = new LinkedHashMap<>();
        for (DtoField field : fields) {
            dtoByName.put(field.jsonName(), field);
            consumed.add(field.rawType());
            DtoIndex.elementType(field.rawType(), field.genericType()).ifPresent(consumed::add);
        }

        // 1) 契约有、DTO 没有 = 漏实现
        properties.forEach((name, propNode) -> {
            if (!dtoByName.containsKey(name)) {
                findings.add(new Finding(file, schemaName, join(path, name), Kind.MISSING_IN_DTO,
                        "契约声明了这一项，但 " + DtoIndex.fqn(dto) + " 里没有对应的 field"
                                + "（契约要求的 JSON 名 `" + name + "`，即 Java 名 `" + camel(name) + "`）"
                                + similarFieldHint(name, fields)
                                + "；DTO 现有 JSON 名 " + new TreeSet<>(dtoByName.keySet())));
            }
        });

        // 2) DTO 有、契约没有 = 未声明字段
        for (DtoField field : fields) {
            if (!properties.containsKey(field.jsonName())) {
                findings.add(new Finding(file, schemaName, join(path, field.jsonName()), Kind.UNDECLARED_IN_DTO,
                        DtoIndex.fqn(dto) + " 的 `" + field.render() + "` 在契约的这个 schema 里没有声明"
                                + similarPropertyHint(field, properties.keySet())
                                + "（序列化出去就是一个前端类型里没有的字段）"));
            }
        }

        // 3) 必填性（只判请求体，理由见类注释）
        if (request) {
            compareRequired(file, schemaName, path, node, dtoByName, dto);
        }

        // 4) 形状 + 内联对象递归
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            DtoField field = dtoByName.get(entry.getKey());
            if (field != null) {
                compareShape(file, schemaName, join(path, entry.getKey()), entry.getValue(), field, request, stack);
            }
        }
    }

    private void compareRequired(String file, String schemaName, String path, Map<String, Object> node,
                                 Map<String, DtoField> dtoByName, Class<?> dto) {
        List<String> required = stringList(node.get("required"));
        String where = file + " · " + schemaName + (path.isEmpty() ? "" : "." + path) + " → " + DtoIndex.fqn(dto);
        for (String name : required) {
            DtoField field = dtoByName.get(name);
            if (field != null && !field.required()) {
                findings.add(new Finding(file, schemaName, join(path, name), Kind.REQUIRED_NOT_ENFORCED,
                        "契约 required 里有 `" + name + "`，但 " + DtoIndex.fqn(dto) + " 的 `"
                                + field.render() + "` 上没有 @NotNull/@NotBlank/@NotEmpty"
                                + "——缺字段时接口不会以 40001 拒绝，校验注解或契约的 required 有一个是错的"
                                + (field.rawType().isPrimitive()
                                ? "（" + field.rawType().getSimpleName() + " 是原始类型：缺字段会被静默当成 0/false）"
                                : "")));
            }
        }
        for (DtoField field : dtoByName.values()) {
            if (field.required() && !required.contains(field.jsonName())) {
                findings.add(new Finding(file, schemaName, join(path, field.jsonName()), Kind.REQUIRED_NOT_DECLARED,
                        DtoIndex.fqn(dto) + " 的 `" + field.render() + "` 带 " + field.requiredBy()
                                + "（必填），但契约这个 schema 的 required 里没有 `" + field.jsonName()
                                + "`——调用方照契约传会拿到 40001，契约漏写了必填（" + where + "）"));
            }
        }
    }

    /** 契约属性 ↔ DTO 类型的形状比对；内联对象往下递归一层。 */
    private void compareShape(String file, String schemaName, String path, Object rawPropNode,
                              DtoField field, boolean request, Deque<Class<?>> stack) {
        Map<String, Object> propNode = asMap(rawPropNode, file + " · " + path);
        boolean viaRef = propNode.containsKey("$ref");
        Map<String, Object> resolved = docs.resolve(file, propNode, file + " · " + path);
        Shape shape = shapeOf(resolved);
        if (shape == Shape.ANY) {
            return;
        }
        if (shape == Shape.ARRAY) {
            if (!DtoIndex.isArrayLike(field.rawType())) {
                shapeMismatch(file, schemaName, path, field, "契约是数组，DTO 是 " + field.rawType().getSimpleName());
                return;
            }
            Optional<Class<?>> element = DtoIndex.elementType(field.rawType(), field.genericType());
            if (element.isEmpty()) {
                return;
            }
            Object rawItems = resolved.get("items");
            if (!(rawItems instanceof Map)) {
                return;
            }
            Map<String, Object> itemsNode = asMap(rawItems, file + " · " + path + "[]");
            boolean itemsViaRef = itemsNode.containsKey("$ref");
            Map<String, Object> resolvedItems = docs.resolve(file, itemsNode, file + " · " + path + "[]");
            Shape itemShape = shapeOf(resolvedItems);
            if (itemShape == Shape.OBJECT) {
                Class<?> elementType = element.get();
                if (!DtoIndex.isApiDto(elementType)) {
                    shapeMismatch(file, schemaName, path + "[]", field,
                            "契约的元素是对象，DTO 的元素是 " + elementType.getSimpleName());
                } else if (!itemsViaRef && !stack.contains(elementType)) {
                    stack.push(elementType);
                    compare(file, schemaName, path + "[]", resolvedItems, elementType, request, stack);
                    stack.pop();
                }
                // itemsViaRef：被引用的 schema 自己有名字，会作为一张 schema 单独比，不重复报
            } else if (itemShape == Shape.SCALAR && DtoIndex.isApiDto(element.get())) {
                shapeMismatch(file, schemaName, path + "[]", field,
                        "契约的元素是标量，DTO 的元素是对象 " + DtoIndex.fqn(element.get()));
            }
            return;
        }
        if (shape == Shape.OBJECT) {
            if (DtoIndex.isArrayLike(field.rawType())) {
                shapeMismatch(file, schemaName, path, field, "契约是对象，DTO 是数组/集合");
                return;
            }
            if (viaRef) {
                return;
            }
            if (DtoIndex.isApiDto(field.rawType())) {
                if (!stack.contains(field.rawType())) {
                    stack.push(field.rawType());
                    compare(file, schemaName, path, resolved, field.rawType(), request, stack);
                    stack.pop();
                }
                return;
            }
            if (Map.class.isAssignableFrom(field.rawType())) {
                // 契约写了 object 但没写属性（形如自由键值），DTO 用 Map 是合理落法，不判
                return;
            }
            shapeMismatch(file, schemaName, path, field,
                    "契约是对象，DTO 是标量 " + field.rawType().getSimpleName());
            return;
        }
        // 标量：只判「契约说标量、DTO 是集合/对象」这种硬冲突。
        // 标量的具体类型（String / Integer / LocalDate / BigDecimal）不判——契约的 string 与 Java 的
        // LocalDate、BigDecimal 在序列化后都是字符串，静态判必误报；那一层由 ContractJsonTest 的运行时断言守。
        if (DtoIndex.isArrayLike(field.rawType()) || DtoIndex.isApiDto(field.rawType())) {
            shapeMismatch(file, schemaName, path, field,
                    "契约是标量，DTO 是 " + (DtoIndex.isArrayLike(field.rawType()) ? "数组/集合" : "对象"));
        }
    }

    private void shapeMismatch(String file, String schemaName, String path, DtoField field, String detail) {
        findings.add(new Finding(file, schemaName, path, Kind.SHAPE_MISMATCH,
                detail + "（DTO 字段 `" + field.render() + "`，类型 " + field.rawType().getSimpleName()
                        + "；JSON 形状对不上，前端按契约生成的类型会错位）"));
    }

    private void reportDtosWithoutSchema() {
        for (Class<?> dto : index.topLevelRecords()) {
            if (consumed.contains(dto)) {
                continue;
            }
            findings.add(new Finding("", dto.getSimpleName(), "", Kind.DTO_WITHOUT_SCHEMA,
                    DtoIndex.fqn(dto) + " 在 app/provider/admin 三份契约里都没有对应的 components/schemas 条目"
                            + "——契约漏写，或者这个类不该是接口 DTO（内部形状放 ph-api 会被当成对外承诺）"));
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static Shape shapeOf(Map<String, Object> node) {
        if (node.isEmpty()) {
            return Shape.ANY;
        }
        Object type = node.get("type");
        if ("array".equals(type)) {
            return Shape.ARRAY;
        }
        if ("object".equals(type) || node.containsKey("properties")) {
            return Shape.OBJECT;
        }
        if (type == null) {
            return Shape.ANY;
        }
        return Shape.SCALAR;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node, String where) {
        if (!(node instanceof Map)) {
            throw new IllegalArgumentException("期望是对象，实际是 " + node + "：" + where);
        }
        return (Map<String, Object>) node;
    }

    private static Map<String, Object> childMap(Map<String, Object> node, String key) {
        Object child = node.get(key);
        if (child == null) {
            return Map.of();
        }
        return asMap(child, key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object node) {
        if (!(node instanceof List)) {
            return List.of();
        }
        return ((List<Object>) node).stream().map(String::valueOf).toList();
    }

    private static String join(String path, String field) {
        return path.isEmpty() ? field : path + "." + field;
    }

    /** snake_case → camelCase（只用于提示语，让告警里直接给出该写的 Java 名）。 */
    private static String camel(String snake) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    /** 去掉下划线/短横线再比：用来识破「同一个词、两种拼法」（漏写 @JsonProperty 的典型症状）。 */
    private static String normalize(String name) {
        return name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String similarFieldHint(String contractName, List<DtoField> fields) {
        StringJoiner hints = new StringJoiner("、");
        for (DtoField field : fields) {
            if (normalize(field.javaName()).equals(normalize(contractName))) {
                hints.add("`" + field.render() + "`");
            }
        }
        return hints.length() == 0
                ? ""
                : "；DTO 里最像它的是 " + hints + "（同一个词的两种拼法：改名或补 @JsonProperty）";
    }

    private static String similarPropertyHint(DtoField field, Set<String> contractNames) {
        for (String name : contractNames) {
            if (normalize(name).equals(normalize(field.javaName()))) {
                return "；契约里最像它的是 `" + name + "`（同一个词的两种拼法：改名或补 @JsonProperty）";
            }
        }
        return "";
    }
}
