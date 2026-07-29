package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.entity.Brand;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 品牌方 Excel 导出（2026-07-30 起去掉了导入/下载模板功能，只保留导出——品牌方/红人团队管理
 * 页面不再需要批量导入这条路径，改成都走管理页面手动新增/编辑）。
 * 品牌方无敏感字段，所有角色可见完整数据。
 */
@Component
public class BrandExcelHandler {

    public void export(List<Brand> brands, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = "品牌方_" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("品牌方");
        XSSFCellStyle hdr  = createHeaderStyle(wb);
        XSSFCellStyle nor  = createNormalStyle(wb);

        String[] headers = {
            "品牌方名称", "国家/市场", "联系人", "结算币种",
            "付款周期类型", "阈值分档-成本阈值", "阈值分档-阈值以内天数", "阈值分档-阈值以上天数", "月结-对账日后天数",
            "是否需要Invoice", "合同签订周期",
            "备注"
        };
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(hdr);
            sheet.setColumnWidth(i, 20 * 256);
        }

        for (int i = 0; i < brands.size(); i++) {
            Brand b  = brands.get(i);
            Row row  = sheet.createRow(i + 1);
            int c    = 0;
            setCellStr(row, c++, b.getName(),              nor);
            setCellStr(row, c++, b.getCountryMarket(),     nor);
            setCellStr(row, c++, b.getContactPerson(),     nor);
            setCellStr(row, c++, b.getSettlementCurrency(), nor);
            setCellStr(row, c++, b.getPaymentCycleType() != null ? b.getPaymentCycleType().getLabel() : "", nor);
            setCellStr(row, c++, b.getCostThresholdAmount() != null ? b.getCostThresholdAmount().toPlainString() : "", nor);
            setCellStr(row, c++, b.getDaysWithinThreshold() != null ? String.valueOf(b.getDaysWithinThreshold()) : "", nor);
            setCellStr(row, c++, b.getDaysAboveThreshold()  != null ? String.valueOf(b.getDaysAboveThreshold())  : "", nor);
            setCellStr(row, c++, b.getDaysAfterMonthEnd()   != null ? String.valueOf(b.getDaysAfterMonthEnd())   : "", nor);
            // requiresInvoice 是"事后加的、默认更安全的"字段——null 按 requiresInvoiceUpload() 同一套
            // null 安全语义处理，跟前端列表页/表单的展示口径保持一致，不要在这里另写一套判断
            setCellStr(row, c++, b.requiresInvoiceUpload() ? "需要" : "不需要", nor);
            setCellStr(row, c++, b.getContractCycleType() != null ? b.getContractCycleType().getLabel() : "", nor);
            setCellStr(row, c++, b.getNotes(),             nor);
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    private void setCellStr(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private XSSFCellStyle createHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(new byte[]{(byte)41,(byte)128,(byte)185}, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private XSSFCellStyle createNormalStyle(XSSFWorkbook wb) {
        return wb.createCellStyle();
    }
}
