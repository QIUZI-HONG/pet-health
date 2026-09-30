package com.pethealth.api.app;

/**
 * 服务者列表行（C 端「找店」），对应 contract/app.yaml 的 {@code ProviderSummaryView}。
 *
 * <p><b>没有 {@code status} / {@code level} / {@code monthly_score}</b>：这是刻意的。
 * 能出现在列表里就说明状态与资质都过了门槛（见 `GET /api/v1/app/providers` 的可见性口径），
 * 再下发一个 {@code status} 只会让前端有机会把它当成「能不能下单」的第二个判据——
 * 而那个判据只该有一个地方说了算（服务端）。
 *
 * <p>{@code phone} 是**脱敏值**（{@code 138****8888}，ADR-0049 第二节）：门店电话在库里是密文
 * （ADR-0013），C 端只需要能打电话找店，不需要完整号码。
 *
 * <p>{@code typeName} 由**服务端拼好**（分类码 → 中文名）：三端各写一份映射就会出现
 * 「一端叫洗护、一端叫洗护美容」。映射的定义处是
 * {@link com.pethealth.provider.domain.Provider#typeName(Integer)}（C 端不引 provider 的类，
 * 这里只说明口径在哪）。
 *
 * <p>{@code rating} 是字符串（金额 / 数字一律字符串是项目约定，禁止浮点）：
 * 评价体系未落地前恒为门店的默认值。
 */
public record ProviderSummaryView(
        Long id,
        String name,
        Integer type,
        String typeName,
        String logo,
        String intro,
        String address,
        String lng,
        String lat,
        String phone,
        String rating) {
}
