package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.entity.Employee;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 员工管理 Excel 导出（只导出，不导入，照抄 InfluencerPaymentExcelHandler 的先例）。
 */
@Component
public class EmployeeExcelHandler {

    public void export(List<Employee> employees, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = "员工_" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("员工");
        XSSFCellStyle hdr   = createHeaderStyle(wb);
        XSSFCellStyle money = createMoneyStyle(wb);
        XSSFCellStyle nor   = createNormalStyle(wb);

        List<String> cols = new ArrayList<String>();
        cols.add("姓名");
        cols.add("角色");
        cols.add("邮箱");
        cols.add("联系电话");
        cols.add("入职时间");
        cols.add("离职时间");
        cols.add("默认提成比例");
        cols.add("固定月薪（人民币）");
        cols.add("实拍新视频（元/条）");
        cols.add("AI新素材（元/条）");
        cols.add("旧素材重发1-50条（元/条）");
        cols.add("旧素材重发51-100条（元/条）");
        cols.add("旧素材重发101条+（元/条）");
        cols.add("旧素材重发101条+当月封顶（元）");
        cols.add("备注");

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < cols.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(cols.get(i));
            cell.setCellStyle(hdr);
            sheet.setColumnWidth(i, 16 * 256);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        for (int i = 0; i < employees.size(); i++) {
            Employee e = employees.get(i);
            Row row = sheet.createRow(i + 1);
            int c = 0;

            setCellStr(row, c++, e.getName(), nor);
            setCellStr(row, c++, e.getRole(), nor);
            setCellStr(row, c++, e.getEmail(), nor);
            setCellStr(row, c++, e.getContactPhone(), nor);
            setCellStr(row, c++, e.getHireDate()   != null ? sdf.format(e.getHireDate())   : "", nor);
            setCellStr(row, c++, e.getResignDate() != null ? sdf.format(e.getResignDate()) : "", nor);
            setCellStr(row, c++, e.getDefaultCommissionRate() != null
                    ? e.getDefaultCommissionRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%" : "", nor);
            setCellMoney(row, c++, e.getFixedMonthlySalary(), money);
            setCellMoney(row, c++, e.getRateRealShotNew(), money);
            setCellMoney(row, c++, e.getRateAiNewMaterial(), money);
            setCellMoney(row, c++, e.getRateOldMaterialTier1(), money);
            setCellMoney(row, c++, e.getRateOldMaterialTier2(), money);
            setCellMoney(row, c++, e.getRateOldMaterialTier3(), money);
            setCellMoney(row, c++, e.getOldMaterialMonthlyCap(), money);
            setCellStr(row, c++, e.getNotes(), nor);
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

    private void setCellMoney(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value.doubleValue());
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

    private XSSFCellStyle createMoneyStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return style;
    }

    private XSSFCellStyle createNormalStyle(XSSFWorkbook wb) {
        return wb.createCellStyle();
    }
}
