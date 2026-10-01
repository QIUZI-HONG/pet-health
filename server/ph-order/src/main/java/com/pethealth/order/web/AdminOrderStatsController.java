package com.pethealth.order.web;

import com.pethealth.api.admin.AdminOrderStatsView;
import com.pethealth.common.api.ApiResponse;
import com.pethealth.order.service.AdminOrderStatsService;
import com.pethealth.common.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营看板的订单聚合（契约 `admin.yaml` 的 `/orders/stats`）。
 *
 * <p>为什么在 ph-order：订单数据归它，跨模块取数要走端口——一个只给运营看板的聚合不值得
 * 为此多一条端口（端口是给**别的模块**用的）。运营后台与它是同一套账号与令牌体系，
 * 只是登录域不同（ADR-0012），所以这里 `requireAdmin()` 挡一道。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOrderStatsController {

    private final AdminOrderStatsService stats;

    public AdminOrderStatsController(AdminOrderStatsService stats) {
        this.stats = stats;
    }

    /** 某账期的订单统计：按状态分组 + 合计金额 + 取消率。不传账期按当月。 */
    @GetMapping("/orders/stats")
    public ApiResponse<AdminOrderStatsView> orderStats(@RequestParam(required = false) String period) {
        CurrentUser.requireAdmin();
        return ApiResponse.ok(stats.statsOf(period));
    }
}
