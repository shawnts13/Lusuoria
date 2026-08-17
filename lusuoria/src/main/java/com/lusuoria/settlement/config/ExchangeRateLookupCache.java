package com.lusuoria.settlement.config;

import com.lusuoria.settlement.entity.ExchangeRateCache;
import com.lusuoria.settlement.repository.ExchangeRateCacheRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 月度汇率内存缓存（2026-08-17 新增）。
 *
 * 命名提醒，容易搞混：这里说的"缓存"是内存里的 ConcurrentHashMap，跟 BrandCache/EmployeeCache
 * 那一套写法一样；{@link ExchangeRateCache}（entity 包下那个）是数据库表实体，名字也带
 * "...Cache" 是历史命名（见它自己的类注释——早年汇率是自动抓外部 API、"缓存"到这张表里，后来
 * 改成完全人工维护，表名沿用了下来），指的是"数据库里那张表"，不是内存缓存。本类是这张表对应的
 * 真正的内存缓存，两者是完全不同的两个东西，只是恰好撞了名字。
 *
 * {@link com.lusuoria.settlement.service.impl.ExchangeRateService#getRateForMonth} 被数据看板/
 * 工资单/红人合作跟踪等几乎所有涉及美元-人民币换算的地方反复调用——一次年度报告请求就可能要
 * 查全年12个月的汇率，而汇率本身一个月最多改一次（管理员人工对照中国银行官网填写），读写比例
 * 悬殊，是这套 *Cache 系列里最典型的适用场景。
 *
 * 启动时加载，每4小时自动刷新，{@link com.lusuoria.settlement.service.impl.ExchangeRateService#saveRate}
 * 写完后主动调用 refresh()。这张表目前没有软删除/删除入口（只有新增/修改，见 saveRate 的
 * "有就更新、没有就插入"逻辑），所以直接 findAll() 全量加载，不需要过滤 isDeleted。
 */
@Component
public class ExchangeRateLookupCache {

    @Autowired private ExchangeRateCacheRepository rateRepo;

    // key: 业务月份（如 "202606"），value: 该月的汇率记录
    private volatile Map<String, ExchangeRateCache> byYearMonth = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() { refresh(); }

    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000)
    public synchronized void refresh() {
        Map<String, ExchangeRateCache> map = new ConcurrentHashMap<>();
        for (ExchangeRateCache c : rateRepo.findAll()) {
            map.put(c.getYearMonth(), c);
        }
        byYearMonth = map;
    }

    /** 某个业务月份的汇率记录，没维护过则返回 null */
    public ExchangeRateCache findByYearMonth(String yearMonth) {
        if (yearMonth == null) return null;
        return byYearMonth.get(yearMonth);
    }

    /** 全部已维护月份的汇率，按月份倒序（"汇率维护"列表页用），防御性拷贝 */
    public List<ExchangeRateCache> getAll() {
        List<ExchangeRateCache> list = new ArrayList<>(byYearMonth.values());
        list.sort(Comparator.comparing(ExchangeRateCache::getYearMonth).reversed());
        return list;
    }
}
