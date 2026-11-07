package com.example.Zabbix.web;

import com.example.Zabbix.model.DashboardData;
import com.example.Zabbix.model.ServerRow;
import com.example.Zabbix.service.DashboardService;
import com.example.Zabbix.service.SettingsService;
import com.example.Zabbix.service.ZabbixApiException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class DashboardController {

    private final DashboardService dashboardService;
    private final SettingsService settingsService;

    public DashboardController(DashboardService dashboardService, SettingsService settingsService) {
        this.dashboardService = dashboardService;
        this.settingsService = settingsService;
    }

    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model) {
        model.addAttribute("configured", settingsService.isConfigured());
        try {
            DashboardData data = dashboardService.getDashboard();
            model.addAttribute("servers", data.servers());
            model.addAttribute("demoMode", data.demo());
            model.addAttribute("totalServers", data.totalServers());
            model.addAttribute("criticalServers", data.criticalServers());
            model.addAttribute("totalProblems", data.totalProblems());
            model.addAttribute("updatedAt", data.updatedAt());
        } catch (ZabbixApiException ex) {
            List<ServerRow> empty = List.of();
            model.addAttribute("servers", empty);
            model.addAttribute("demoMode", false);
            model.addAttribute("totalServers", 0);
            model.addAttribute("criticalServers", 0);
            model.addAttribute("totalProblems", 0);
            model.addAttribute("updatedAt", null);
            model.addAttribute("errorMessage", ex.getUserMessage());
        }
        return "index";
    }
}
