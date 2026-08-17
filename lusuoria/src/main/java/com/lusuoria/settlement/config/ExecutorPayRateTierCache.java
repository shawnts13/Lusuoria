package com.lusuoria.settlement.config;

import com.lusuoria.settlement.entity.ExecutorPayRateTier;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.ExecutorPayRateTierRepository;
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
 * 执行人员薪资费率梯度内存缓存（2026-08-17 新增）。
 *
 * 这套梯度价配置本身改动很少（项目负责人偶尔调整名下执行人员的费率），但被红人合作跟踪
 * 保存/Excel导入（{@link com.lusuoria.settlement.service.impl.CollaborationTrackingService}）、
 * 工资单计算（{@link com.lusuoria.settlement.service.impl.PayslipService}）、批量重算利润/
 * 执行成本等好几处高频路径重复查询，其中红人合作跟踪保存这条路径几乎是每次保存都会走到——
 * 符合"读多写少的小规模引用数据"这个 *Cache 系列一直在处理的场景，做法照抄 BrandCache 等：
 * 启动时加载，每4小时自动刷新，{@link com.lusuoria.settlement.controller.ExecutorPayRateController}
 * 的整批替换写入后主动调用 refresh()。
 */
@Component
public class ExecutorPayRateTierCache {

    @Autowired private ExecutorPayRateTierRepository tierRepo;

    // key: managerId + "|" + executorId + "|" + videoType，value: 按 minCount 升序排好的档位列表
    private volatile Map<String, List<ExecutorPayRateTier>> byKey = new ConcurrentHashMap<>();
    // key: managerId，value: 这个负责人名下配置的全部档位（跨执行人员/视频类型），按 minCount 升序
    private volatile Map<Long, List<ExecutorPayRateTier>> byManagerId = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() { refresh(); }

    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000)
    public synchronized void refresh() {
        Map<String, List<ExecutorPayRateTier>> keyMap = new ConcurrentHashMap<>();
        Map<Long, List<ExecutorPayRateTier>> managerMap = new ConcurrentHashMap<>();
        for (ExecutorPayRateTier t : tierRepo.findByIsDeletedFalse()) {
            keyMap.computeIfAbsent(key(t.getManagerId(), t.getExecutorId(), t.getVideoType()), k -> new ArrayList<>()).add(t);
            managerMap.computeIfAbsent(t.getManagerId(), k -> new ArrayList<>()).add(t);
        }
        Comparator<ExecutorPayRateTier> byMinCount = Comparator.comparing(ExecutorPayRateTier::getMinCount);
        for (List<ExecutorPayRateTier> list : keyMap.values()) list.sort(byMinCount);
        for (List<ExecutorPayRateTier> list : managerMap.values()) list.sort(byMinCount);
        byKey = keyMap;
        byManagerId = managerMap;
    }

    private String key(Long managerId, Long executorId, VideoType videoType) {
        return managerId + "|" + executorId + "|" + (videoType != null ? videoType.name() : "null");
    }

    /**
     * 某个 (负责人,执行人员,视频类型) 的档位列表，按 minCount 升序；没配置过返回空列表（不是
     * null，调用方不用另外判空）。返回的是防御性拷贝，调用方可以放心对返回值做排序等操作，
     * 不会污染缓存里的共享数据。
     */
    public List<ExecutorPayRateTier> find(Long managerId, Long executorId, VideoType videoType) {
        if (managerId == null || executorId == null || videoType == null) return Collections.emptyList();
        return new ArrayList<>(byKey.getOrDefault(key(managerId, executorId, videoType), Collections.emptyList()));
    }

    /** 某个负责人名下配置的全部档位（跨执行人员/视频类型），按 minCount 升序，同样是防御性拷贝 */
    public List<ExecutorPayRateTier> findByManagerId(Long managerId) {
        if (managerId == null) return Collections.emptyList();
        return new ArrayList<>(byManagerId.getOrDefault(managerId, Collections.emptyList()));
    }
}
