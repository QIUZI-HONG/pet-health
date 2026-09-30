package com.pethealth.common.persistence;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.pethealth.common.api.PageResult;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 持久层公共配置（ADR-0011）：Mapper 扫描、分页拦截器、逻辑删除、审计填充。
 *
 * <p>放在 ph-common 而不是各模块——这些约定必须全局一致，各模块自己配就会出现
 * 「这个模块的分页是 20 条、那个模块是 10 条」这种事。
 */
@Configuration
// 扫全库、但只认打了 @Mapper 的接口：MyBatis 自动扫描只覆盖启动类所在包（com.pethealth.boot），
// 各模块的 mapper 在 com.pethealth.<模块>.mapper 下，不显式声明就会「找不到 Mapper Bean」。
// 带上 annotationClass 是为了不把普通接口（比如 PetQueryApi）误当成 Mapper 注册。
@MapperScan(basePackages = "com.pethealth", annotationClass = Mapper.class)
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 上限与 DTO 校验、契约共用同一份定义（PageResult.MAX_PAGE_SIZE），不在这里另写一个数
        pagination.setMaxLimit(PageResult.MAX_PAGE_SIZE);
        // 翻页越界时返回空列表而不是回到第一页——回到第一页会让前端以为请求到了别的内容
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        // 拦住没有 WHERE 条件的全表 update / delete
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }

    /**
     * 审计字段填充器。显式构造而不是让 Spring 扫——它是 BaseEntity 的配套，声明在一起更清楚。
     */
    @Bean
    public MetaObjectHandler auditMetaObjectHandler() {
        return new AuditMetaObjectHandler();
    }
}
