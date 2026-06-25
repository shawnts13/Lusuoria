package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.ExchangeRateRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.ExchangeRateInfo;
import com.lusuoria.settlement.entity.ExchangeRateCache;
import com.lusuoria.settlement.service.impl.ExchangeRateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 月度汇率维护
 *
 * 仅 ADMIN 可新增/修改；查询接口对所有登录角色开放（看板/订单模块需要读取汇率，
 * 但前端只在管理员视角下展示"汇率维护"菜单和编辑入口）。
 */
@RestController
@RequestMapping("/api/exchange-rates")
public class ExchangeRateController {

    @Autowired private ExchangeRateService exchangeRateService;

    /** 查询某月汇率 */
    @GetMapping("/{yearMonth}")
    public ApiResponse<ExchangeRateInfo> getOne(@PathVariable String yearMonth) {
        return ApiResponse.success(exchangeRateService.getRateForMonth(yearMonth));
    }

    /** 维护模块列表：所有已填写月份的汇率 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<ExchangeRateCache>> list() {
        return ApiResponse.success(exchangeRateService.listAll());
    }

    /**
     * 新增/修改某月汇率。
     * 会强制覆盖该月所有已存在项目订单的汇率字段。
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ExchangeRateCache> save(@Valid @RequestBody ExchangeRateRequest req) {
        return ApiResponse.success(
                exchangeRateService.saveRate(req.getYearMonth(), req.getUsdToCny()));
    }
}
