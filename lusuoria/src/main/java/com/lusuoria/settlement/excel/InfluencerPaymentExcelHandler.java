package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.entity.InfluencerPayment;
import com.lusuoria.settlement.entity.InfluencerTeam;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 红人结款 Excel 导出（2026-07 重构：模块整体收紧到"管理层/财务/法务"才能看到，
 * 不再区分敏感列脱敏；同时下掉了导入/下载模板功能，只保留导出）。
 */
@Component
public class InfluencerPaymentExcelHandler {

    @Autowired private InfluencerTeamCache teamCache;

    public void export(List<InfluencerPayment> payments, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = "红人结款_" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("红人结款");
        XSSFCellStyle hdr   = createHeaderStyle(wb);
        XSSFCellStyle money = createMoneyStyle(wb);
        XSSFCellStyle nor   = createNormalStyle(wb);

        List<String> cols = new ArrayList<String>();
        cols.add("结款单号");
        cols.add("品牌方");
        cols.add("红人团队");
        cols.add("结算月份");
        cols.add("合作数量");
        cols.add("应付金额");
        cols.add("币种");
        cols.add("汇率");
        cols.add("人民币金额");
        cols.add("对账日期");
        cols.add("预计付款日");
        cols.add("实际付款日");
        cols.add("付款状态");
        cols.add("备注");

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < cols.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(cols.get(i));
            cell.setCellStyle(hdr);
            sheet.setColumnWidth(i, 16 * 256);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        for (int i = 0; i < payments.size(); i++) {
            InfluencerPayment p = payments.get(i);
            Row row = sheet.createRow(i + 1);
            int c = 0;

            setCellStr(row, c++, p.getPaymentNo(), nor);
            setCellStr(row, c++, p.getBrand() != null ? p.getBrand().getName() : "", nor);
            setCellStr(row, c++, teamNamesLabel(p), nor);
            setCellStr(row, c++, p.getSettlementMonth(), nor);
            setCellNum(row, c++, p.getCooperationQuantity() != null ? (double) p.getCooperationQuantity() : null, nor);
            setCellMoney(row, c++, p.getPayableAmount(), money);
            setCellStr(row, c++, p.getCurrency(), nor);
            setCellMoney(row, c++, p.getExchangeRate(), money);
            setCellMoney(row, c++, p.getRmbAmount(), money);
            setCellStr(row, c++, p.getReconcileDate()       != null ? sdf.format(p.getReconcileDate())       : "", nor);
            setCellStr(row, c++, p.getExpectedPaymentDate() != null ? sdf.format(p.getExpectedPaymentDate()) : "", nor);
            setCellStr(row, c++, p.getActualPaymentDate()   != null ? sdf.format(p.getActualPaymentDate())   : "", nor);
            setCellStr(row, c++, p.getPaymentStatus() != null ? p.getPaymentStatus().getLabel() : "", nor);
            setCellStr(row, c++, p.getNotes(), nor);
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    /** 涉及的团队可能有多个（跨团队合并结款），拼成逗号分隔的名字，"不选团队"显示为"（不选团队）" */
    private String teamNamesLabel(InfluencerPayment p) {
        if (p.getTeamIds() == null || p.getTeamIds().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Long teamId : p.getTeamIds()) {
            if (sb.length() > 0) sb.append("、");
            if (teamId == null) {
                sb.append("（不选团队）");
            } else {
                InfluencerTeam team = teamCache.findById(teamId);
                sb.append(team != null ? team.getName() : teamId);
            }
        }
        return sb.toString();
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

    private XSSFCellStyle createHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(new byte[]{(byte)39,(byte)174,(byte)96}, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private XSSFCellStyle createMoneyStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return style;
    }

    private XSSFCellStyle createNormalStyle(XSSFWorkbook wb) {
        return wb.createCellStyle();
    }
}
