package com.lusuoria.settlement.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 给导入模板追加一个"可选值参考"sheet：把模板里枚举类/引用类字段当前实际可选的值罗列出来
 * （品牌方/团队、所属领域这类从数据库读最新数据，红人类型/合作平台这类代码里维护的常量），
 * 供操作人核对怎么填，不然只看列名不知道能填什么，容易导入失败。
 *
 * 每个字段占一列，从上到下按行展开；不同字段值的数量不同没关系，POI 按列独立写，
 * 短的列自然留白。这个 sheet 只是给人看的参考信息，不参与导入解析——导入固定从
 * workbook 的第 0 个 sheet（也就是真正的数据模板 sheet）读数据，这个 sheet 加在后面
 * （或者隐藏 sheet 后面）完全不影响导入功能，两边共用同一个 InfluencerExcelHandler /
 * CollaborationTrackingExcelHandler 调用方各自负责把它摆在 sheet 顺序的哪个位置。
 */
final class ExcelReferenceSheetHelper {

    private ExcelReferenceSheetHelper() {}

    static void append(XSSFWorkbook wb, String sheetName, LinkedHashMap<String, List<String>> columns) {
        XSSFSheet sheet = wb.createSheet(sheetName);
        XSSFCellStyle hdrStyle = headerStyle(wb);
        XSSFCellStyle wrapStyle = wrapStyle(wb);

        int col = 0;
        for (Map.Entry<String, List<String>> entry : columns.entrySet()) {
            Row headerRow = getOrCreateRow(sheet, 0);
            Cell hCell = headerRow.createCell(col);
            hCell.setCellValue(entry.getKey());
            hCell.setCellStyle(hdrStyle);

            List<String> values = entry.getValue();
            for (int r = 0; r < values.size(); r++) {
                Row row = getOrCreateRow(sheet, r + 1);
                Cell cell = row.createCell(col);
                cell.setCellValue(values.get(r));
                cell.setCellStyle(wrapStyle);
            }
            sheet.setColumnWidth(col, 34 * 256);
            col++;
        }
        if (col > 0) sheet.createFreezePane(0, 1);
    }

    private static Row getOrCreateRow(XSSFSheet sheet, int idx) {
        Row row = sheet.getRow(idx);
        return row != null ? row : sheet.createRow(idx);
    }

    private static XSSFCellStyle headerStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 39, (byte) 174, (byte) 96}, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setWrapText(true);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static XSSFCellStyle wrapStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }
}
