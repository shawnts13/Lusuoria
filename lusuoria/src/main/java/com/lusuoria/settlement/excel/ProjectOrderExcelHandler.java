package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.util.ProjectFieldVisibility;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Excel 导出（项目订单只能从"红人合作跟踪"联动生成，不支持手工新建/批量导入，
 * 所以这里只保留导出功能，不再有导入模板下载和导入解析）
 *
 * 权限逻辑跟在线列表一致（ProjectFieldVisibility）：
 *   - FULL（ADMIN/财务/管理层）：所有列都能看到真实值
 *   - 项目负责人：其他外部成本/内部执行成本/提成比例/负责人提成 这几列存在，
 *     但只有自己负责的行显示真实值，其他行显示"脱敏处理"；项目毛利等纯利润列完全不出现
 *   - 执行人员：内部执行成本这一列存在，自己执行的行显示真实值，其他行"脱敏处理"；
 *     其他外部成本/提成/利润列完全不出现
 *   - 基础角色（IT后勤/法务/未关联员工）：只有红人成本/客户合作价格/已到账金额这几个基础列
 *   - GUEST：连基础列都没有
 */
@Component
public class ProjectOrderExcelHandler {


    // ===================================================================
    // 列定义（统一管理，导出和模板共用同一套列顺序）
    // 每列归属一个可见性类别，由 ProjectFieldVisibility 决定该角色能不能看到这一列，
    // 部分类别（OTHER_EXTERNAL_COST / INTERNAL_EXEC_COST / COMMISSION）即使列可见，
    // 具体到某一行是否显示脱敏值还要看这行是不是本人负责/执行的项目
    // ===================================================================
    private enum ColCategory {
        NONE,                 // 不受限，所有角色可见
        BASELINE,             // 红人成本/客户合作价格/已到账金额：除 GUEST 外都可见
        OTHER_EXTERNAL_COST,  // 其他外部成本：FULL 全看；项目负责人仅自己负责的行；执行人员/基础角色不可见
        INTERNAL_EXEC_COST,   // 内部执行成本：FULL 全看；项目负责人/执行人员仅自己的行；基础角色不可见
        COMMISSION,           // 提成比例/负责人提成：FULL 全看；项目负责人仅自己负责的行（只读）；执行人员/基础角色不可见
        PURE_PROFIT           // 项目毛利/公司利润/可分配利润：只有 FULL 可见，跟是不是本人负责/执行无关
    }

    private static class ColDef {
        final String title;
        final ColCategory category;
        ColDef(String title, ColCategory category) {
            this.title = title;
            this.category = category;
        }
    }

    /** 导出列定义（有序），按需求4指定的展示顺序排列 */
    private static final List<ColDef> EXPORT_COLS = new ArrayList<ColDef>();
    static {
        EXPORT_COLS.add(new ColDef("甲方订单号",       ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("项目月份",         ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("品牌方",           ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("项目类型",         ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("项目视频类型",     ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("红人社媒完整名字", ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("项目负责人",       ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("甲方状态",         ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("内部状态",         ColCategory.NONE));
        // 敏感（收入/利润/提成，仅 ADMIN / AUDITOR）
        EXPORT_COLS.add(new ColDef("客户合作价格",     ColCategory.BASELINE));
        EXPORT_COLS.add(new ColDef("红人成本",         ColCategory.BASELINE));
        EXPORT_COLS.add(new ColDef("币种",             ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("汇率",             ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("项目毛利",         ColCategory.PURE_PROFIT));
        EXPORT_COLS.add(new ColDef("公司利润（美金）", ColCategory.PURE_PROFIT));
        EXPORT_COLS.add(new ColDef("公司利润（人民币）", ColCategory.PURE_PROFIT));
        EXPORT_COLS.add(new ColDef("负责人提成",       ColCategory.COMMISSION));
        EXPORT_COLS.add(new ColDef("提成比例",         ColCategory.COMMISSION));
        EXPORT_COLS.add(new ColDef("已到账金额",       ColCategory.BASELINE));
        EXPORT_COLS.add(new ColDef("内部项目编号",     ColCategory.NONE));
        // 其余信息列（非展示顺序要求里的，附在后面）
        EXPORT_COLS.add(new ColDef("红人团队",         ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("合作内容",         ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("其他外部成本（人民币）",     ColCategory.OTHER_EXTERNAL_COST));
        EXPORT_COLS.add(new ColDef("内部执行成本（人民币）",     ColCategory.INTERNAL_EXEC_COST));
        EXPORT_COLS.add(new ColDef("可分配利润",       ColCategory.PURE_PROFIT));
        EXPORT_COLS.add(new ColDef("合同签署",         ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("预计到账日",       ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("实际到账日",       ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("备注",             ColCategory.NONE));
        EXPORT_COLS.add(new ColDef("创建时间",         ColCategory.NONE));
    }

    // ===================================================================
    // 导出
    // ===================================================================
    public void export(List<ProjectOrder> orders, ProjectFieldVisibility.Context ctx,
                       HttpServletResponse response) throws IOException {
        boolean isFull          = ctx.isFull();
        boolean canViewBaseline = ctx.tier != ProjectFieldVisibility.Tier.GUEST;
        boolean isManagerTier   = ctx.tier == ProjectFieldVisibility.Tier.PROJECT_MANAGER;
        boolean isExecutorTier  = ctx.tier == ProjectFieldVisibility.Tier.EXECUTOR;

        // 每个类别的列，这个角色能不能"看到这一列"（列存不存在）
        Map<ColCategory, Boolean> colVisible = new EnumMap<ColCategory, Boolean>(ColCategory.class);
        colVisible.put(ColCategory.NONE, true);
        colVisible.put(ColCategory.BASELINE, canViewBaseline);
        colVisible.put(ColCategory.OTHER_EXTERNAL_COST, isFull || isManagerTier);
        colVisible.put(ColCategory.INTERNAL_EXEC_COST, isFull || isManagerTier || isExecutorTier);
        colVisible.put(ColCategory.COMMISSION, isFull || isManagerTier);
        colVisible.put(ColCategory.PURE_PROFIT, isFull);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = (isFull ? "项目结算_完整版_" : "项目结算_")
                + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb      = new XSSFWorkbook();
        XSSFSheet    sheet   = wb.createSheet("项目订单");
        XSSFCellStyle hdrN   = createHeaderStyle(wb, false);
        XSSFCellStyle hdrS   = createHeaderStyle(wb, true);
        XSSFCellStyle money  = createMoneyStyle(wb);
        XSSFCellStyle normal = createNormalStyle(wb);

        // 写表头：只跳过这个角色完全看不到的类别；能看到的列（哪怕某些行会被脱敏）都会出现
        Row headerRow = sheet.createRow(0);
        int colIdx = 0;
        List<ColDef> visibleCols = new ArrayList<ColDef>();
        for (ColDef col : EXPORT_COLS) {
            if (!colVisible.get(col.category)) continue;
            visibleCols.add(col);
            Cell cell = headerRow.createCell(colIdx++);
            cell.setCellValue(col.title);
            cell.setCellStyle(col.category == ColCategory.NONE || col.category == ColCategory.BASELINE ? hdrN : hdrS);
        }
        for (int i = 0; i < colIdx; i++) sheet.setColumnWidth(i, 18 * 256);

        SimpleDateFormat sdf  = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat sdtf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        String MASKED = "脱敏处理";

        for (int i = 0; i < orders.size(); i++) {
            ProjectOrder o = orders.get(i);
            Row row = sheet.createRow(i + 1);

            boolean isOwnManager = ctx.employeeId != null && o.getProjectManagerId() != null
                    && ctx.employeeId.equals(o.getProjectManagerId());
            boolean isOwnExecutor = ctx.employeeId != null && o.getExecutorId() != null
                    && ctx.employeeId.equals(o.getExecutorId());
            // 这一行，OTHER_EXTERNAL_COST / INTERNAL_EXEC_COST / COMMISSION 这几类字段是不是"本人负责/执行"，
            // 决定显示真实值还是"脱敏处理"（PURE_PROFIT 类不受行影响，能看到列就是真实值，因为只有 FULL 能看到这列）
            boolean rowOwnOtherExternal = isFull || (isManagerTier && isOwnManager);
            boolean rowOwnInternalExec  = isFull || (isManagerTier && isOwnManager) || (isExecutorTier && isOwnExecutor);
            boolean rowOwnCommission    = isFull || (isManagerTier && isOwnManager);

            int c = 0;
            for (ColDef col : visibleCols) {
                switch (col.title) {
                    case "甲方订单号": setCellStr(row, c++, o.getClientOrderNo(), normal); break;
                    case "项目月份": setCellStr(row, c++, o.getProjectMonth(), normal); break;
                    case "品牌方": setCellStr(row, c++, o.getBrand() != null ? o.getBrand().getName() : "", normal); break;
                    case "项目类型": setCellStr(row, c++, o.getProjectType() != null ? o.getProjectType().getLabel() : "", normal); break;
                    case "项目视频类型": setCellStr(row, c++, o.getVideoType() != null ? o.getVideoType().getLabel() : "", normal); break;
                    case "红人社媒完整名字": setCellStr(row, c++, o.getInfluencer() != null ? o.getInfluencer().getAccountName() : "", normal); break;
                    case "项目负责人": setCellStr(row, c++, o.getProjectManager() != null ? o.getProjectManager().getName() : "", normal); break;
                    case "甲方状态": setCellStr(row, c++, o.getClientStatus() != null ? o.getClientStatus().getLabel() : "", normal); break;
                    case "内部状态": setCellStr(row, c++, o.getInternalStatus() != null ? o.getInternalStatus().getLabel() : "", normal); break;
                    case "客户合作价格": setCellMoney(row, c++, o.getClientPrice(), money); break;
                    case "红人成本": setCellMoney(row, c++, o.getInfluencerCost(), money); break;
                    case "币种": setCellStr(row, c++, "美元", normal); break;
                    case "汇率": setCellMoney(row, c++, o.getExchangeRate(), money); break;
                    case "项目毛利": setCellMoney(row, c++, o.getGrossProfit(), money); break;
                    case "公司利润（美金）": setCellMoney(row, c++, o.getCompanyNetProfit(), money); break;
                    case "公司利润（人民币）": setCellMoney(row, c++, o.getRmbRevenue(), money); break;
                    case "负责人提成":
                        if (rowOwnCommission) setCellMoney(row, c++, o.getCommissionAmount(), money);
                        else setCellStr(row, c++, MASKED, normal);
                        break;
                    case "提成比例": {
                        Cell rateCell = row.createCell(c++);
                        if (rowOwnCommission) {
                            rateCell.setCellValue(o.getCommissionRate() != null
                                    ? o.getCommissionRate().doubleValue() * 100 + "%" : "");
                        } else {
                            rateCell.setCellValue(MASKED);
                        }
                        rateCell.setCellStyle(normal);
                        break;
                    }
                    case "已到账金额": setCellMoney(row, c++, o.getReceivedAmount(), money); break;
                    case "内部项目编号": setCellStr(row, c++, o.getInternalProjectNo(), normal); break;
                    case "红人团队": setCellStr(row, c++, o.getTeam() != null ? o.getTeam().getName() : "", normal); break;
                    case "合作内容": setCellStr(row, c++, o.getCooperationContent(), normal); break;
                    case "其他外部成本（人民币）":
                        if (rowOwnOtherExternal) setCellMoney(row, c++, o.getOtherExternalCost(), money);
                        else setCellStr(row, c++, MASKED, normal);
                        break;
                    case "内部执行成本（人民币）":
                        if (rowOwnInternalExec) setCellMoney(row, c++, o.getInternalExecutionCost(), money);
                        else setCellStr(row, c++, MASKED, normal);
                        break;
                    case "可分配利润": setCellMoney(row, c++, o.getDistributableProfit(), money); break;
                    case "合同签署": setCellStr(row, c++, Boolean.TRUE.equals(o.getContractSigned()) ? "是" : "否", normal); break;
                    case "预计到账日": setCellStr(row, c++, o.getExpectedReceiptDate() != null ? sdf.format(o.getExpectedReceiptDate()) : "", normal); break;
                    case "实际到账日": setCellStr(row, c++, o.getActualReceiptDate() != null ? sdf.format(o.getActualReceiptDate()) : "", normal); break;
                    case "备注": setCellStr(row, c++, o.getNotes(), normal); break;
                    case "创建时间": setCellStr(row, c++, o.getCreatedAt() != null ? sdtf.format(o.getCreatedAt()) : "", normal); break;
                    default: c++; // 不应该发生，占位避免列错位
                }
            }
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===================================================================
    // 样式
    // ===================================================================
    private XSSFCellStyle createHeaderStyle(XSSFWorkbook wb, boolean sensitive) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        // 蓝色：普通列；橙色：敏感财务列
        byte[] color = sensitive
                ? new byte[]{(byte)211, (byte)84,  (byte)0}    // 橙色
                : new byte[]{(byte)41,  (byte)128, (byte)185};  // 蓝色
        style.setFillForegroundColor(new XSSFColor(color, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private XSSFCellStyle createMoneyStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }

    private XSSFCellStyle createNormalStyle(XSSFWorkbook wb) {
        return wb.createCellStyle();
    }

    private void setCellStr(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private void setCellMoney(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private void setCellNum(Row row, int col, Double value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value);
        cell.setCellStyle(style);
    }

}
