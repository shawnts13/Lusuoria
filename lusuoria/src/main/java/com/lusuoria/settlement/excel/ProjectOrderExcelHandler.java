package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.entity.ProjectOrder;
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
 * canViewSensitive = true  (ADMIN / AUDITOR)：导出包含完整财务列
 * canViewSensitive = false (STAFF / GUEST)  ：导出完全不含财务列，列不存在
 */
@Component
public class ProjectOrderExcelHandler {


    // ===================================================================
    // 列定义（统一管理，导出和模板共用同一套列顺序）
    // sensitive=true 的列只有有权限的角色才会出现在文件里
    // ===================================================================
    private static class ColDef {
        final String title;
        final boolean sensitive;
        ColDef(String title, boolean sensitive) {
            this.title = title;
            this.sensitive = sensitive;
        }
    }

    /** 导出列定义（有序），按需求4指定的展示顺序排列 */
    private static final List<ColDef> EXPORT_COLS = new ArrayList<ColDef>();
    static {
        EXPORT_COLS.add(new ColDef("甲方订单号",       false));
        EXPORT_COLS.add(new ColDef("项目月份",         false));
        EXPORT_COLS.add(new ColDef("品牌方",           false));
        EXPORT_COLS.add(new ColDef("项目类型",         false));
        EXPORT_COLS.add(new ColDef("项目视频类型",     false));
        EXPORT_COLS.add(new ColDef("红人社媒完整名字", false));
        EXPORT_COLS.add(new ColDef("项目负责人",       false));
        EXPORT_COLS.add(new ColDef("甲方状态",         false));
        EXPORT_COLS.add(new ColDef("内部状态",         false));
        // 敏感（收入/利润/提成，仅 ADMIN / AUDITOR）
        EXPORT_COLS.add(new ColDef("客户合作价格",     true));
        EXPORT_COLS.add(new ColDef("红人成本",         true));
        EXPORT_COLS.add(new ColDef("币种",             false));
        EXPORT_COLS.add(new ColDef("汇率",             false));
        EXPORT_COLS.add(new ColDef("项目毛利",         true));
        EXPORT_COLS.add(new ColDef("公司利润（美金）", true));
        EXPORT_COLS.add(new ColDef("公司利润（人民币）", true));
        EXPORT_COLS.add(new ColDef("负责人提成",       true));
        EXPORT_COLS.add(new ColDef("提成比例",         true));
        EXPORT_COLS.add(new ColDef("已到账金额",       false));
        EXPORT_COLS.add(new ColDef("内部项目编号",     false));
        // 其余信息列（非展示顺序要求里的，附在后面）
        EXPORT_COLS.add(new ColDef("红人团队",         false));
        EXPORT_COLS.add(new ColDef("合作内容",         false));
        EXPORT_COLS.add(new ColDef("其他外部成本",     true));
        EXPORT_COLS.add(new ColDef("内部执行成本",     true));
        EXPORT_COLS.add(new ColDef("可分配利润",       true));
        EXPORT_COLS.add(new ColDef("合同签署",         false));
        EXPORT_COLS.add(new ColDef("预计到账日",       false));
        EXPORT_COLS.add(new ColDef("实际到账日",       false));
        EXPORT_COLS.add(new ColDef("备注",             false));
        EXPORT_COLS.add(new ColDef("创建时间",         false));
    }

    // ===================================================================
    // 导出
    // ===================================================================
    public void export(List<ProjectOrder> orders, boolean canViewSensitive,
                       HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = (canViewSensitive ? "项目结算_完整版_" : "项目结算_")
                + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb      = new XSSFWorkbook();
        XSSFSheet    sheet   = wb.createSheet("项目订单");
        XSSFCellStyle hdrN   = createHeaderStyle(wb, false); // 普通表头（蓝色）
        XSSFCellStyle hdrS   = createHeaderStyle(wb, true);  // 敏感表头（橙色，有权限才出现）
        XSSFCellStyle money  = createMoneyStyle(wb);
        XSSFCellStyle normal = createNormalStyle(wb);

        // 写表头，跳过无权限的敏感列
        Row headerRow = sheet.createRow(0);
        int colIdx = 0;
        for (ColDef col : EXPORT_COLS) {
            if (col.sensitive && !canViewSensitive) continue;
            Cell cell = headerRow.createCell(colIdx++);
            cell.setCellValue(col.title);
            cell.setCellStyle(col.sensitive ? hdrS : hdrN);
        }
        for (int i = 0; i < colIdx; i++) sheet.setColumnWidth(i, 18 * 256);

        SimpleDateFormat sdf  = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat sdtf = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        for (int i = 0; i < orders.size(); i++) {
            ProjectOrder o = orders.get(i);
            Row row = sheet.createRow(i + 1);
            int c = 0;

            // 非敏感基础列（按需求4指定顺序）
            setCellStr(row, c++, o.getClientOrderNo(), normal);
            setCellStr(row, c++, o.getProjectMonth(), normal);
            setCellStr(row, c++, o.getBrand() != null ? o.getBrand().getName() : "", normal);
            setCellStr(row, c++, o.getProjectType() != null ? o.getProjectType().getLabel() : "", normal);
            setCellStr(row, c++, o.getVideoType() != null ? o.getVideoType().getLabel() : "", normal);
            setCellStr(row, c++, o.getInfluencer() != null ? o.getInfluencer().getAccountName() : "", normal);
            setCellStr(row, c++, o.getProjectManager() != null ? o.getProjectManager().getName() : "", normal);
            setCellStr(row, c++, o.getClientStatus() != null ? o.getClientStatus().getLabel() : "", normal);
            setCellStr(row, c++, o.getInternalStatus() != null ? o.getInternalStatus().getLabel() : "", normal);

            // 敏感列（只有有权限才写入，无权限跳过，列根本不存在）
            if (canViewSensitive) {
                setCellMoney(row, c++, o.getClientPrice(), money);
                setCellMoney(row, c++, o.getInfluencerCost(), money);
            }
            setCellStr(row, c++, "美元", normal);
            setCellMoney(row, c++, o.getExchangeRate(), money);
            if (canViewSensitive) {
                setCellMoney(row, c++, o.getGrossProfit(), money);
                setCellMoney(row, c++, o.getCompanyNetProfit(), money);
                setCellMoney(row, c++, o.getRmbRevenue(), money);
                setCellMoney(row, c++, o.getCommissionAmount(), money);
                Cell rateCell = row.createCell(c++);
                rateCell.setCellValue(o.getCommissionRate() != null
                        ? o.getCommissionRate().doubleValue() * 100 + "%" : "");
                rateCell.setCellStyle(normal);
            }
            setCellMoney(row, c++, o.getReceivedAmount(), money);
            setCellStr(row, c++, o.getInternalProjectNo(), normal);

            // 其余信息列
            setCellStr(row, c++, o.getInfluencer() != null ? o.getInfluencer().getTeamName() : "", normal);
            setCellStr(row, c++, o.getCooperationContent(), normal);
            if (canViewSensitive) {
                setCellMoney(row, c++, o.getOtherExternalCost(), money);
                setCellMoney(row, c++, o.getInternalExecutionCost(), money);
                setCellMoney(row, c++, o.getDistributableProfit(), money);
            }
            setCellStr(row, c++, Boolean.TRUE.equals(o.getContractSigned()) ? "是" : "否", normal);
            setCellStr(row, c++, o.getExpectedReceiptDate() != null ? sdf.format(o.getExpectedReceiptDate()) : "", normal);
            setCellStr(row, c++, o.getActualReceiptDate()   != null ? sdf.format(o.getActualReceiptDate())   : "", normal);
            setCellStr(row, c++, o.getNotes(), normal);
            setCellStr(row, c++, o.getCreatedAt() != null ? sdtf.format(o.getCreatedAt()) : "", normal);
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
