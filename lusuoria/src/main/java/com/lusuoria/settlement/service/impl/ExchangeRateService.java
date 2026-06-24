package com.lusuoria.settlement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lusuoria.settlement.dto.response.ExchangeRateInfo;
import com.lusuoria.settlement.entity.ExchangeRateCache;
import com.lusuoria.settlement.repository.ExchangeRateCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 汇率服务
 *
 * 数据来源：Frankfurter API（https://frankfurter.dev），免费、无需密钥、无调用限制，
 * 数据来自欧洲央行等84家央行的官方汇率。
 *
 * 中国银行官网及中国货币网均通过 robots.txt 禁止自动化访问，技术上无法直接对接，
 * 此服务设计为可替换实现（单一职责类），未来如有授权的中国银行数据源，
 * 只需替换 fetchAndCache 里的请求逻辑，不影响其他业务代码。
 *
 * 业务规则：查看 yyyyMM 月份的看板数据时，使用"上一个自然月最后一个工作日"的汇率。
 * Frankfurter API 在指定日期为非交易日（周末/节假日）时，会自动返回该日期之前最近
 * 一个有数据的交易日汇率，因此这里只需算出"上月最后一天"，不需要自己判断节假日。
 *
 * 容错：外部接口请求失败时，返回默认兜底汇率 7.0，并在 ExchangeRateInfo.isFallback
 * 标记为 true，前端据此提示用户"汇率获取失败，当前为默认汇率"。
 * 兜底值不写入缓存表，下次请求会重新尝试调用真实接口，不会永久卡死在兜底值上。
 */
@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    private static final String API_BASE = "https://api.frankfurter.dev/v1/";
    private static final String SOURCE_PAGE = "https://www.frankfurter.app";
    private static final BigDecimal FALLBACK_RATE = new BigDecimal("7.0000");

    @Autowired private ExchangeRateCacheRepository cacheRepo;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取指定月份对应的汇率（含缓存）。
     * @param yearMonth 看板查看的月份，格式 yyyyMM，如 202606
     */
    public ExchangeRateInfo getRateForMonth(String yearMonth) {
        ExchangeRateCache cached = cacheRepo.findByYearMonth(yearMonth).orElse(null);
        if (cached != null) {
            return toInfo(cached);
        }
        return fetchAndCache(yearMonth);
    }

    private synchronized ExchangeRateInfo fetchAndCache(String yearMonth) {
        // 双重检查，避免并发请求时重复抓取
        ExchangeRateCache cached = cacheRepo.findByYearMonth(yearMonth).orElse(null);
        if (cached != null) return toInfo(cached);

        LocalDate lastDayOfPrevMonth = lastDayOfPreviousMonth(yearMonth);
        String dateStr = lastDayOfPrevMonth.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String url = API_BASE + dateStr + "?base=USD&symbols=CNY";

        try {
            String responseBody = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode cnyNode = root.path("rates").path("CNY");
            if (cnyNode.isMissingNode()) {
                throw new IllegalStateException("接口返回数据中未找到 CNY 汇率字段，原始返回：" + responseBody);
            }

            String actualDate = root.path("date").asText(dateStr);
            BigDecimal rate = new BigDecimal(cnyNode.asText());

            ExchangeRateCache entity = ExchangeRateCache.builder()
                    .yearMonth(yearMonth)
                    .rateDate(actualDate)
                    .usdToCny(rate)
                    .sourceUrl(SOURCE_PAGE)
                    .fetchedAt(new Date())
                    .build();
            cacheRepo.save(entity);
            log.info("汇率获取成功：月份={}，取数日期={}，1USD={}CNY", yearMonth, actualDate, rate);
            return toInfo(entity);
        } catch (Exception e) {
            log.error("汇率获取失败：月份={}，请求URL={}，原因：{}", yearMonth, url, e.getMessage(), e);
            // 不写入缓存，下次请求时重新尝试；当前请求返回默认兜底汇率
            return ExchangeRateInfo.builder()
                    .yearMonth(yearMonth)
                    .rateDate(dateStr)
                    .usdToCny(FALLBACK_RATE)
                    .sourceUrl(SOURCE_PAGE)
                    .isFallback(true)
                    .build();
        }
    }

    /** 计算"yyyyMM 月份"的上一个自然月的最后一天 */
    private LocalDate lastDayOfPreviousMonth(String yearMonth) {
        int year  = Integer.parseInt(yearMonth.substring(0, 4));
        int month = Integer.parseInt(yearMonth.substring(4, 6));
        YearMonth current = YearMonth.of(year, month);
        YearMonth previous = current.minusMonths(1);
        return previous.atEndOfMonth();
    }

    private ExchangeRateInfo toInfo(ExchangeRateCache c) {
        return ExchangeRateInfo.builder()
                .yearMonth(c.getYearMonth())
                .rateDate(c.getRateDate())
                .usdToCny(c.getUsdToCny())
                .sourceUrl(c.getSourceUrl())
                .isFallback(false)
                .build();
    }
}
