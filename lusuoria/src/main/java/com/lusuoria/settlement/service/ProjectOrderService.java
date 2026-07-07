package com.lusuoria.settlement.service;

import com.lusuoria.settlement.dto.request.ProjectOrderRequest;
import com.lusuoria.settlement.dto.response.ExecutorCostSuggestionResponse;
import com.lusuoria.settlement.dto.response.MonthlySummaryResponse;
import com.lusuoria.settlement.dto.response.ProjectOrderResponse;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.enums.VideoType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public interface ProjectOrderService {

    ProjectOrderResponse save(ProjectOrderRequest request);

    ProjectOrderResponse getById(Long id);

    Page<ProjectOrderResponse> list(Long brandId, String projectMonth, String videoPublishMonth, ProjectType projectType,
                                    ClientStatus clientStatus, InternalSettlementStatus internalStatus,
                                    VideoType videoType, String internalProjectNo,
                                    Long influencerId, String accountName, Long projectManagerId,
                                    String keyword, Pageable pageable);

    /**
     * 发起删除申请（不直接删除）：生成一条"待处理"审核事项，
     * 由 ADMIN 在"待处理"模块同意后才真正删除。
     */
    PendingApproval requestDelete(Long id, String reason);

    MonthlySummaryResponse getMonthlySummary(String month);

    /** 老板审核通过：PENDING_APPROVAL -> CONFIRMED */
    ProjectOrderResponse approve(Long id);

    /** 老板驳回：PENDING_APPROVAL -> CALCULATED */
    ProjectOrderResponse reject(Long id);

    /**
     * 状态流转：只修改甲方状态 + 内部状态，不接收、也不会改动其他任何字段。
     * 配合前端专门的"状态流转"弹窗使用。
     */
    ProjectOrderResponse updateStatus(Long id, ClientStatus clientStatus, InternalSettlementStatus internalStatus);

    ExecutorCostSuggestionResponse suggestExecutorCost(Long orderId);

    ProjectOrderResponse setExecutorCost(Long orderId, java.math.BigDecimal amount);

    void exportExcel(String projectMonth, HttpServletResponse response) throws IOException;
}
