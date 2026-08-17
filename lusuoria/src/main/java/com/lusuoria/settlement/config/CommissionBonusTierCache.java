package com.lusuoria.settlement.config;

import com.lusuoria.settlement.entity.CommissionBonusTier;
import com.lusuoria.settlement.repository.CommissionBonusTierRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目负责人/管理层提成 Bonus 阶梯内存缓存（2026-08-17 新增，做法照抄
 * {@link ExecutorPayRateTierCache}）。
 *
 * 跟执行人员费率梯度同一类场景：配置改动很少（项目负责人偶尔调整自己的 bonus 阶梯），
 * 但被数据看板下钻（{@link com.lusuoria.settlement.service.impl.DashboardStatsService}）、
 * 工资单计算（{@link com.lusuoria.settlement.service.impl.PayslipService}，经
 * {@link com.lusuoria.settlement.service.impl.CommissionBonusService} 中转）反复查询——
 * 其中 DashboardStatsService 的"提成"下钻是按项目负责人维度循环调用
 * {@link com.lusuoria.settlement.service.impl.CommissionBonusService#hasBonusTierConfigured}/
 * {@link com.lusuoria.settlement.service.impl.CommissionBonusService#computeBonus}，
 * 每个负责人两次查库，是一处之前没被发现的 N+1（CommissionBonusService 类注释里提到过
 * "之前 PayslipService 这么写过"已经改掉，但 DashboardStatsService 这边还是这个写法）——
 * 引入这个缓存后，这两个方法内部改成读内存，这处 N+1 也随之解决，不需要再单独按调用方式改。
 *
 * 启动时加载，每4小时自动刷新，{@link com.lusuoria.settlement.controller.EmployeeController}
 * 保存员工（连带整批替换 bonus 阶梯）后主动调用 refresh()。
 */
@Component
public class CommissionBonusTierCache {

    @Autowired private CommissionBonusTierRepository bonusTierRepo;

    // key: employeeId，value: 按 minAmount 升序排好的阶梯列表
    private volatile Map<Long, List<CommissionBonusTier>> byEmployeeId = new ConcurrentHashMap<>();

    /** Bean 构造完成后首次加载 */
    @PostConstruct
    public void init() { refresh(); }

    /** 全量重查一遍未软删的阶梯配置，按 employeeId 分组、组内按 minAmount 排好序，整体替换 map */
    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000)
    public synchronized void refresh() {
        Map<Long, List<CommissionBonusTier>> map = new ConcurrentHashMap<>();
        for (CommissionBonusTier t : bonusTierRepo.findByIsDeletedFalse()) {
            map.computeIfAbsent(t.getEmployeeId(), k -> new ArrayList<>()).add(t);
        }
        Comparator<CommissionBonusTier> byMinAmount = Comparator.comparing(CommissionBonusTier::getMinAmount);
        for (List<CommissionBonusTier> list : map.values()) list.sort(byMinAmount);
        byEmployeeId = map;
    }

    /**
     * 某个员工配置的全部阶梯，按 minAmount 升序；没配置过返回空列表（不是 null）。
     * 返回防御性拷贝，调用方可以放心处理返回值，不会污染缓存里的共享数据。
     */
    public List<CommissionBonusTier> find(Long employeeId) {
        if (employeeId == null) return Collections.emptyList();
        return new ArrayList<>(byEmployeeId.getOrDefault(employeeId, Collections.emptyList()));
    }
}
