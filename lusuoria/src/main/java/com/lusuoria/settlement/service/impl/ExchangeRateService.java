package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.dto.response.ExchangeRateInfo;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.ExchangeRateCache;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.ExchangeRateCacheRepository;
import com.lusuoria.settlement.util.ProfitCalculator;
import com.lusuoria.settlement.util.RoleUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 月度汇率服务（人工维护，仅 ADMIN 可写）
 *
 * 中国法定节假日及调休规则逐年变化，自动判断"上月最后一个工作日"风险较高，
 * 故汇率改为完全人工维护：管理员对照中国银行官网手动填写每月汇率。
 *
 * 关键行为：
 *   1. 修改某月汇率会强制覆盖该月所有已存在红人合作跟踪记录的 exchangeRate 字段
 *      （2026-07 随"项目订单"模块废弃从那边迁移过来，月份口径从"项目建立月份"
 *      改成"发布时间"，与看板的月份口径保持一致），并连带重新计算这些记录的项目毛利/
 *      可分配利润/负责人提成/公司利润（美金+人民币）——2026-08 补上的，之前只覆盖了
 *      exchangeRate 原始字段本身，没有触发 ProfitCalculator，导致改汇率后这几个存库的
 *      派生字段还是按旧汇率算出来的旧值，直到有人手动编辑保存一次才会更新，容易被忽略。
 *      跟"红人合作跟踪"列表页"重新计算利润"按钮（CollaborationTrackingService.
 *      recomputeAllProfits()）是同一个 ProfitCalculator，那个按钮是全量兜底修复入口
 *      （汇率异常/绕过系统改了原始值这类场景），这里是"改汇率"这个动作自己的关联更新，
 *      两者不冲突，都要保留。
 *   2. 看板/合作跟踪查询某月汇率时，若该月还未维护，返回 isMissing=true，
 *      由调用方决定如何提示，不再有兜底默认值，避免用一个虚假汇率误导业务决策
 */
@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    @Autowired private ExchangeRateCacheRepository rateRepo;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private ProfitCalculator profitCalculator;

    /** 查询某月汇率（看板、合作跟踪生成订单等场景调用） */
    public ExchangeRateInfo getRateForMonth(String yearMonth) {
        ExchangeRateCache cached = rateRepo.findByYearMonth(yearMonth).orElse(null);
        if (cached == null) {
            return ExchangeRateInfo.builder()
                    .yearMonth(yearMonth)
                    .usdToCny(null)
                    .isMissing(true)
                    .build();
        }
        return ExchangeRateInfo.builder()
                .yearMonth(cached.getYearMonth())
                .usdToCny(cached.getUsdToCny())
                .isMissing(false)
                .updatedBy(cached.getUpdatedBy())
                .lastUpdatedAt(cached.getLastUpdatedAt())
                .build();
    }

    /** 获取所有已维护月份的汇率列表（汇率维护模块列表页用），按月份倒序 */
    public List<ExchangeRateCache> listAll() {
        return rateRepo.findAllByOrderByYearMonthDesc();
    }

    /**
     * 新增或修改某月汇率（仅 ADMIN 可调用，权限校验在 Controller 层做）。
     * 会强制覆盖该月（按"发布时间"匹配）所有已存在红人合作跟踪记录的 exchangeRate 字段，
     * 并连带重新计算这些记录的项目毛利/可分配利润/负责人提成/公司利润，见类注释。
     */
    @Transactional
    public ExchangeRateCache saveRate(String yearMonth, BigDecimal newRate) {
        String operator = RoleUtil.getCurrentUsername();
        Date now = new Date();

        ExchangeRateCache cache = rateRepo.findByYearMonth(yearMonth).orElse(null);
        BigDecimal oldRate = cache != null ? cache.getUsdToCny() : null;

        if (cache == null) {
            cache = ExchangeRateCache.builder()
                    .yearMonth(yearMonth)
                    .usdToCny(newRate)
                    .updatedBy(operator)
                    .lastUpdatedAt(now)
                    .build();
        } else {
            cache.setUsdToCny(newRate);
            cache.setUpdatedBy(operator);
            cache.setLastUpdatedAt(now);
        }
        ExchangeRateCache saved = rateRepo.save(cache);

        // 强制覆盖该月（按发布时间匹配）所有已存在红人合作跟踪记录的汇率，并连带重新计算
        // 这些记录的项目毛利/可分配利润/负责人提成/公司利润——这几个是存库的派生字段，
        // 不会因为 exchangeRate 本身变了就自动跟着变，必须显式调用 ProfitCalculator
        List<CollaborationTracking> orders = trackingRepo.findByPublishMonth(yearMonth);
        for (CollaborationTracking o : orders) {
            o.setExchangeRate(newRate);
            profitCalculator.calculate(o);
        }
        trackingRepo.saveAll(orders);
        log.info("汇率维护：月份={}，操作人={}，{} -> {}，已回填红人合作跟踪 {} 条并重新计算利润",
                yearMonth, operator, oldRate, newRate, orders.size());

        return saved;
    }
}
