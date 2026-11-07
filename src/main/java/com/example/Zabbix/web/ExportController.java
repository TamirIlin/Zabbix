package com.example.Zabbix.web;

import com.example.Zabbix.model.DashboardData;
import com.example.Zabbix.service.DashboardService;
import com.example.Zabbix.service.ExcelExportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Controller
public class ExportController {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DashboardService dashboardService;
    private final ExcelExportService excelExportService;

    public ExportController(DashboardService dashboardService, ExcelExportService excelExportService) {
        this.dashboardService = dashboardService;
        this.excelExportService = excelExportService;
    }

    @GetMapping("/export/servers.xlsx")
    public ResponseEntity<byte[]> exportServers() {
        DashboardData data = dashboardService.getDashboard();
        byte[] file = excelExportService.export(data.servers());
        String filename = "servers-" + LocalDate.now().format(FILE_DATE) + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(file.length)
                .body(file);
    }
}
