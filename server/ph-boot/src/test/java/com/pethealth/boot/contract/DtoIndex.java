package com.pethealth.boot.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 把 {@code ph-api} 里编译好的 DTO 反射成「字段清单」。
 *
 * <p><b>为什么是反射</b>：契约一侧有上百个 schema、上千个字段；人写的清单
 * （{@code ContractJsonTest.implementedFieldsMatchDocumentedSet} 那种）只覆盖它写下时的那几个，
 * 而且契约一改、DTO 一改，清单不会自己变。反射把「清单」变成契约文件 × 编译产物的**实时投影**：
 * 谁改了字段名、加了字段、忘了 {@code @NotNull}，下一次 {@code mvn test} 就是红灯，
 * 不需要任何人记得回来改清单。这是 ADR-0047 说的「把『人盯字段名』换成『机器判』」。
 *
 * <p>字段的 JSON 名按**运行时同一套规则**算：显式 {@code @JsonProperty} 优先，
 * 否则用 Jackson 的 {@code SNAKE_CASE}（{@code application.yml} 里配的就是它，ADR-0011）。
 * 这里刻意调用 Jackson 自己的策略类，而不是自己写一遍下划线转换——自己写的那份一旦与
 * Jackson 的行为分叉，校验就会对着一个「不存在的运行时」点头，比不校验更坏。
 */
final class DtoIndex {

    /** 常量放代码（ADR-0010 的分层）：DTO 只住这一个包，扫描范围不做成可配置项。 */
    private static final String API_PACKAGE_NAME = "com.pethealth.api.";
    private static final String API_PACKAGE_PATH = "com/pethealth/api/";
    private static final String API_CLASS_PATTERN = "classpath*:com/pethealth/api/**/*.class";

    /** 表达「这个字段不允许缺」的校验注解。按**简单名**匹配，jakarta / javax 两种都能认。 */
    private static final Set<String> REQUIRED_ANNOTATIONS = Set.of("NotNull", "NotBlank", "NotEmpty");

    private static final PropertyNamingStrategies.SnakeCaseStrategy SNAKE_CASE =
            new PropertyNamingStrategies.SnakeCaseStrategy();

    /** 顶层 DTO：简单名 → 类。嵌套 record（{@code AccountExportView.Pet} 这类）不进这张表。 */
    private final Map<String, Class<?>> topLevelRecords = new LinkedHashMap<>();

    private DtoIndex() {
    }

    static DtoIndex scan() {
        DtoIndex index = new DtoIndex();
        ClassLoader loader = Optional.ofNullable(Thread.currentThread().getContextClassLoader())
                .orElseGet(() -> DtoIndex.class.getClassLoader());
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver(loader).getResources(API_CLASS_PATTERN);
        } catch (IOException e) {
            throw new UncheckedIOException("扫不到 " + API_CLASS_PATTERN + "（ph-api 不在测试类路径上？）", e);
        }
        for (Resource resource : resources) {
            String className;
            try {
                className = classNameOf(resource.getURL());
            } catch (IOException e) {
                throw new UncheckedIOException("拿不到资源 URL：" + resource, e);
            }
            Class<?> type;
            try {
                type = Class.forName(className, false, loader);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("类路径上有 .class 却加载不到：" + className, e);
            }
            if (type.isRecord() && isSchemaCandidate(type)) {
                Class<?> clash = index.topLevelRecords.putIfAbsent(type.getSimpleName(), type);
                if (clash != null && clash != type) {
                    throw new IllegalStateException("两个 DTO 的简单名相同，schema 名 ↔ 类名无法一一对应："
                            + fqn(clash) + " 与 " + fqn(type) + "——先给其中一个改名");
                }
            }
        }
        if (index.topLevelRecords.isEmpty()) {
            throw new IllegalStateException(
                    "ph-api 里一个 record 都没扫到——类路径或扫描模式不对，这时校验会「全绿」，比红更危险");
        }
        return index;
    }

    /**
     * 谁能参与「schema 名 ↔ 类名」的配对。
     *
     * <p>三条，都是被真实类形状逼出来的：
     *
     * <ul>
     *   <li>顶层 record —— 常态；
     *   <li><b>容器类里的 record</b>（{@code CouponDtos.CouponView}、{@code PointsDtos.PointRecordView}）——
     *       它们是把同一个后台页面的形状收在一个文件里的写法，schema 名对的是它们，
     *       所以要算候选；
     *   <li><b>record 里的 record 不算</b>（{@code AccountExportView.Pet}、{@code HealthScoreView.TrendPoint}）——
     *       它们是父视图的内联子形状。若也算进来，{@code AccountExportView.Pet} 会把 app.yaml 的
     *       {@code Pet} 抢走，把一处「改名漂移」伪装成两处「字段漂移」——诊断工具最怕这种指错方向。
     *       它们仍会以「被引用类型」的身份参与比对（父类字段的类型直接指过来）。
     * </ul>
     */
    private static boolean isSchemaCandidate(Class<?> type) {
        if (type.isSynthetic() || type.isAnonymousClass() || type.isLocalClass()) {
            return false;
        }
        Class<?> enclosing = type.getEnclosingClass();
        return enclosing == null || !enclosing.isRecord();
    }

    /** classpath 上的资源 URL → 类名，兼容目录（target/classes）与 jar 两种形态（嵌套类带 $）。 */
    private static String classNameOf(URL url) {
        String path = url.toString();
        int at = path.indexOf(API_PACKAGE_PATH);
        if (at < 0) {
            throw new IllegalStateException("这个类文件不在 " + API_PACKAGE_PATH + " 下：" + url);
        }
        return path.substring(at, path.length() - ".class".length()).replace('/', '.');
    }

    Optional<Class<?>> topLevel(String simpleName) {
        return Optional.ofNullable(topLevelRecords.get(simpleName));
    }

    /** 全部顶层 record，按类名排序（报告顺序稳定，diff 才读得下去）。 */
    List<Class<?>> topLevelRecords() {
        List<Class<?>> all = new ArrayList<>(topLevelRecords.values());
        all.sort((a, b) -> a.getSimpleName().compareTo(b.getSimpleName()));
        return all;
    }

    /** 一个 DTO 字段：Java 名、它序列化出去的 JSON 名、类型、以及「必填」的来源注解。 */
    record DtoField(String javaName, String jsonName, Class<?> rawType, Type genericType, String requiredBy) {

        boolean required() {
            return requiredBy != null;
        }

        /** 报告里用：{@code createdAt → created_at} 这种映射一眼可见。 */
        String render() {
            return javaName.equals(jsonName) ? javaName : javaName + " → " + jsonName;
        }
    }

    /**
     * 字段清单。只认 record（本项目的接口 DTO 全是 record，见 conventions.md）：
     * POJO 的「字段」要连 getter 命名规则一起猜，而猜错会把校验变成噪音源，没人再信它。
     */
    static List<DtoField> fieldsOf(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            return List.of();
        }
        List<DtoField> fields = new ArrayList<>();
        for (RecordComponent component : components) {
            Method accessor = component.getAccessor();
            Field backing = null;
            try {
                backing = type.getDeclaredField(component.getName());
            } catch (NoSuchFieldException ignored) {
                // 反射拿不到 backing field 时只少一个注解来源，不影响字段名与类型
            }
            List<AnnotatedElement> places = new ArrayList<>();
            places.add(component);
            places.add(accessor);
            if (backing != null) {
                places.add(backing);
            }
            fields.add(new DtoField(
                    component.getName(),
                    jsonNameOf(component.getName(), places),
                    component.getType(),
                    component.getGenericType(),
                    requiredBy(places)));
        }
        return fields;
    }

    private static String jsonNameOf(String javaName, List<AnnotatedElement> places) {
        for (AnnotatedElement place : places) {
            JsonProperty explicit = place.getAnnotation(JsonProperty.class);
            if (explicit != null && !explicit.value().isEmpty()
                    && !JsonProperty.USE_DEFAULT_NAME.equals(explicit.value())) {
                return explicit.value();
            }
        }
        // 与运行时同一套规则：application.yml 的 spring.jackson.property-naming-strategy=SNAKE_CASE
        return snakeCaseOf(javaName);
    }

    /** JSON 名的换算规则，单独暴露给自检测试（见 {@code ContractDtoDriftTest.snakeCaseRuleIsJacksons}）。 */
    static String snakeCaseOf(String javaName) {
        return SNAKE_CASE.translate(javaName);
    }

    private static String requiredBy(List<AnnotatedElement> places) {
        for (AnnotatedElement place : places) {
            for (Annotation annotation : place.getAnnotations()) {
                String simpleName = annotation.annotationType().getSimpleName();
                if (REQUIRED_ANNOTATIONS.contains(simpleName)) {
                    return "@" + simpleName;
                }
            }
        }
        return null;
    }

    /** 数组/集合（不含 Map：Map 在 JSON 里是对象，不是数组）。 */
    static boolean isArrayLike(Class<?> type) {
        return type.isArray() || (Iterable.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type));
    }

    /** {@code List<CheckInItem>} → {@code CheckInItem}；取不到元素类型时返回空。 */
    static Optional<Class<?>> elementType(Class<?> rawType, Type genericType) {
        if (!isArrayLike(rawType)) {
            return Optional.empty();
        }
        if (rawType.isArray()) {
            return Optional.of(rawType.getComponentType());
        }
        if (genericType instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class<?> element) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    /** 是不是 ph-api 里的 DTO（record）。不是它就意味着这个字段在 JSON 上是标量或集合，别往下递归。 */
    static boolean isApiDto(Class<?> type) {
        return type.isRecord() && type.getName().startsWith(API_PACKAGE_NAME);
    }

    /** 报告里用的全限定名，便于直接跳到那个文件去改。 */
    static String fqn(Class<?> type) {
        return type.getName();
    }
}
