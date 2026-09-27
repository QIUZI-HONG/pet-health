package com.pethealth.record.api;

/**
 * 档案模块对外暴露的宠物查询接口。
 *
 * <p>存在的意义是让别的模块**不必碰 pet 表**（ADR-0006：禁止 join 对方的表）。
 * 目前唯一的调用方是 ph-account：切「当前宠物」时要确认这只宠物确实属于这个人。
 *
 * <p>实现类在 {@code com.pethealth.record.service}，由 Spring 注入。
 */
public interface PetQueryApi {

    /**
     * 这只宠物是否存在、未删除、且属于该用户。
     *
     * <p>三种情况一律返回 {@code false}——调用方拿到 false 就按「资源不存在」处理（40400），
     * 不区分「没这只」和「不是他的」，避免泄露 id 是否存在。
     */
    boolean existsOwnedBy(long userId, long petId);
}
