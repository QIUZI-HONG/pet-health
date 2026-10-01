package com.pethealth.order.service;

import com.pethealth.api.admin.AdminOrderStatsView;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.order.domain.OrderStatus;
import com.pethealth.order.domain.OrderStatusStat;
import com.pethealth.order.mapper.ServiceOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 运营看板的订单统计（`GET /admin/orders/stats`，D-30）。
 *
 * <p>**与 {@link OrderStatsService} 是两件事**：那个实现的是 `OrderStatsApi`（给考核用的
 * 服务者维度只读统计，口径在 SQL 里、null 不补 0）；这里是**运营维度的聚合视图**
 * （按状态分组 + 金额 + 取消率）。名字差点撞上——写这一条时它已经被我覆盖过一次，
 * 恢复后改成现在这个类名。
 *
 * <p>金额是**展示口径**：平台不经手资金（ADR-0002），这里的合数是「门店应收」的合计。
 */
@Service
public class AdminOrderStatsService {

    private final ServiceOrderMapper orderMapper;

    public AdminOrderStatsService(ServiceOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Transactional(readOnly = true)
    public AdminOrderStatsView statsOf(String period) {
        YearMonth month;
        try {
            month = period == null || period.isBlank() ? YearMonth.from(AppTime.today()) : YearMonth.parse(period);
        } catch (DateTimeParseException e) {
            throw BusinessException.paramInvalid("账期格式应为 yyyy-MM");
        }
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();

        List<OrderStatusStat> rows = orderMapper.statsByStatus(from, to);
        BigDecimal payAmount = BigDecimal.ZERO;
        long total = 0;
        long cancelled = 0;
        List<AdminOrderStatsView.StatusRow> byStatus = new ArrayList<>();
        for (OrderStatusStat row : rows) {
            long count = row.getCount() == null ? 0 : row.getCount();
            BigDecimal amount = row.getPayAmount() == null ? BigDecimal.ZERO : row.getPayAmount();
            int status = row.getStatus() == null ? 0 : row.getStatus();
            total += count;
            payAmount = payAmount.add(amount);
            if (status == OrderStatus.CANCELLED) {
                cancelled = count;
            }
            byStatus.add(new AdminOrderStatsView.StatusRow(status, OrderStatus.labelOf(status), count,
                    amount.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        }
        String cancelRate = total == 0 ? "0.00"
                : BigDecimal.valueOf(cancelled).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
                        .toPlainString();
        return new AdminOrderStatsView(month.toString(), total, byStatus,
                payAmount.setScale(2, RoundingMode.HALF_UP).toPlainString(), cancelRate);
    }
}
