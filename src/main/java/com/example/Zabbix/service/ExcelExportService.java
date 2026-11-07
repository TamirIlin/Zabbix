package com.example.Zabbix.service;

import com.example.Zabbix.model.MetricView;
import com.example.Zabbix.model.ServerRow;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ExcelExportService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public byte[] export(List<ServerRow> servers) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Серверы");
            Styles styles = createStyles(workbook);

            Row title = sheet.createRow(0);
            title.setHeightInPoints(28);
            Cell titleCell = title.createCell(0);
            titleCell.setCellValue("Сводная аналитика ИТ-инфраструктуры");
            titleCell.setCellStyle(styles.title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));

            Row subtitle = sheet.createRow(1);
            Cell subtitleCell = subtitle.createCell(0);
            subtitleCell.setCellValue("Отчёт сформирован " + LocalDateTime.now().format(DATE_FORMAT));
            subtitleCell.setCellStyle(styles.subtitle);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 6));

            Row header = sheet.createRow(3);
            header.setHeightInPoints(24);
            String[] headers = {"Сервер", "IP-адрес", "Статус", "CPU", "ОЗУ", "Диск", "Проблемы"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(styles.header);
            }

            int rowIndex = 4;
            for (ServerRow server : servers) {
                Row row = sheet.createRow(rowIndex++);
                row.setHeightInPoints(20);

                Cell name = row.createCell(0);
                name.setCellValue(server.name());
                name.setCellStyle(styles.text);

                Cell ip = row.createCell(1);
                ip.setCellValue(server.ip());
                ip.setCellStyle(styles.text);

                Cell status = row.createCell(2);
                status.setCellValue(server.up() ? "Работает" : "Недоступен");
                status.setCellStyle(server.up() ? styles.statusUp : styles.statusDown);

                fillMetric(row, 3, server.cpu(), styles);
                fillMetric(row, 4, server.memory(), styles);
                fillMetric(row, 5, server.disk(), styles);

                Cell problems = row.createCell(6);
                problems.setCellValue(server.problems());
                problems.setCellStyle(server.problems() > 0 ? styles.problems : styles.number);
            }

            Row footer = sheet.createRow(rowIndex + 1);
            Cell footerLabel = footer.createCell(0);
            footerLabel.setCellValue("Всего серверов: " + servers.size());
            footerLabel.setCellStyle(styles.footer);

            Cell footerCritical = footer.createCell(1);
            long critical = servers.stream().filter(ServerRow::critical).count();
            footerCritical.setCellValue("Критических: " + critical);
            footerCritical.setCellStyle(styles.footer);

            Cell footerProblems = footer.createCell(2);
            int totalProblems = servers.stream().mapToInt(ServerRow::problems).sum();
            footerProblems.setCellValue("Проблем: " + totalProblems);
            footerProblems.setCellStyle(styles.footer);
            sheet.addMergedRegion(new CellRangeAddress(rowIndex + 1, rowIndex + 1, 2, 6));

            sheet.createFreezePane(0, 4);
            sheet.setAutoFilter(new CellRangeAddress(3, Math.max(3, rowIndex - 1), 0, 6));
            sheet.setColumnWidth(0, 26 * 256);
            sheet.setColumnWidth(1, 18 * 256);
            sheet.setColumnWidth(2, 15 * 256);
            sheet.setColumnWidth(3, 12 * 256);
            sheet.setColumnWidth(4, 12 * 256);
            sheet.setColumnWidth(5, 12 * 256);
            sheet.setColumnWidth(6, 13 * 256);

            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось сформировать Excel-файл.", ex);
        }
    }

    private void fillMetric(Row row, int column, MetricView metric, Styles styles) {
        Cell cell = row.createCell(column);
        if (metric.value() == null) {
            cell.setCellValue("—");
            cell.setCellStyle(styles.number);
            return;
        }
        cell.setCellValue(metric.value());
        CellStyle style = switch (metric.cssClass()) {
            case "ok" -> styles.percentOk;
            case "warn" -> styles.percentWarn;
            case "bad" -> styles.percentBad;
            default -> styles.percent;
        };
        cell.setCellStyle(style);
    }

    private Styles createStyles(XSSFWorkbook workbook) {
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 16);
        titleFont.setColor(IndexedColors.DARK_BLUE.getIndex());

        CellStyle title = workbook.createCellStyle();
        title.setFont(titleFont);
        title.setVerticalAlignment(VerticalAlignment.CENTER);

        Font subtitleFont = workbook.createFont();
        subtitleFont.setFontHeightInPoints((short) 10);
        subtitleFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

        CellStyle subtitle = workbook.createCellStyle();
        subtitle.setFont(subtitleFont);
        subtitle.setVerticalAlignment(VerticalAlignment.CENTER);

        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());

        CellStyle header = workbook.createCellStyle();
        header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setBorderBottom(BorderStyle.THIN);
        header.setBorderTop(BorderStyle.THIN);
        header.setBorderLeft(BorderStyle.THIN);
        header.setBorderRight(BorderStyle.THIN);

        CellStyle text = workbook.createCellStyle();
        text.setVerticalAlignment(VerticalAlignment.CENTER);
        text.setBorderBottom(BorderStyle.HAIR);
        text.setBorderTop(BorderStyle.HAIR);
        text.setBorderLeft(BorderStyle.HAIR);
        text.setBorderRight(BorderStyle.HAIR);

        CellStyle number = workbook.createCellStyle();
        number.cloneStyleFrom(text);
        number.setAlignment(HorizontalAlignment.CENTER);
        number.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle percent = workbook.createCellStyle();
        percent.cloneStyleFrom(number);
        percent.setDataFormat(workbook.createDataFormat().getFormat("0.0\"%\""));

        CellStyle percentOk = workbook.createCellStyle();
        percentOk.cloneStyleFrom(percent);
        percentOk.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        percentOk.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle percentWarn = workbook.createCellStyle();
        percentWarn.cloneStyleFrom(percent);
        percentWarn.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        percentWarn.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle percentBad = workbook.createCellStyle();
        percentBad.cloneStyleFrom(percent);
        percentBad.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        percentBad.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Font upFont = workbook.createFont();
        upFont.setColor(IndexedColors.GREEN.getIndex());
        upFont.setBold(true);
        CellStyle statusUp = workbook.createCellStyle();
        statusUp.cloneStyleFrom(text);
        statusUp.setFont(upFont);
        statusUp.setAlignment(HorizontalAlignment.CENTER);

        Font downFont = workbook.createFont();
        downFont.setColor(IndexedColors.RED.getIndex());
        downFont.setBold(true);
        CellStyle statusDown = workbook.createCellStyle();
        statusDown.cloneStyleFrom(text);
        statusDown.setFont(downFont);
        statusDown.setAlignment(HorizontalAlignment.CENTER);

        Font problemsFont = workbook.createFont();
        problemsFont.setColor(IndexedColors.RED.getIndex());
        problemsFont.setBold(true);
        CellStyle problems = workbook.createCellStyle();
        problems.cloneStyleFrom(number);
        problems.setFont(problemsFont);

        Font footerFont = workbook.createFont();
        footerFont.setBold(true);
        footerFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        CellStyle footer = workbook.createCellStyle();
        footer.setFont(footerFont);
        footer.setVerticalAlignment(VerticalAlignment.CENTER);

        return new Styles(title, subtitle, header, text, number, percent, percentOk, percentWarn,
                percentBad, statusUp, statusDown, problems, footer);
    }

    private record Styles(CellStyle title, CellStyle subtitle, CellStyle header, CellStyle text,
                          CellStyle number, CellStyle percent, CellStyle percentOk, CellStyle percentWarn,
                          CellStyle percentBad, CellStyle statusUp, CellStyle statusDown,
                          CellStyle problems, CellStyle footer) {
    }
}
