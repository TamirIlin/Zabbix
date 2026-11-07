package com.example.Zabbix.web;

import com.example.Zabbix.model.ServerSummary;
import com.example.Zabbix.service.ReportService;
import com.example.Zabbix.service.SettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Instant;

@Controller
public class ReportController {

    private final ReportService reportService;
    private final SettingsService settingsService;

    public ReportController(ReportService reportService, SettingsService settingsService) {
        this.reportService = reportService;
        this.settingsService = settingsService;
    }

    @GetMapping("/servers/{hostId}")
    public String report(@PathVariable String hostId, Model model) {
        ServerSummary summary = reportService.getSummary(hostId);
        long now = Instant.now().getEpochSecond();
        model.addAttribute("server", summary);
        model.addAttribute("defaultFrom", now - 24 * 3600);
        model.addAttribute("defaultTo", now);
        model.addAttribute("demoMode", !settingsService.isConfigured());
        return "report";
    }
}
