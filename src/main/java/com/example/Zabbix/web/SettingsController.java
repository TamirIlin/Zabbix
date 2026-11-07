package com.example.Zabbix.web;

import com.example.Zabbix.entity.ZabbixSettings;
import com.example.Zabbix.service.SettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        ZabbixSettings settings = settingsService.getSettings().orElse(null);
        model.addAttribute("configured", settings != null);
        model.addAttribute("zabbixUrl", settings == null ? "" : settings.getZabbixUrl());
        model.addAttribute("apiToken", settings == null ? "" : settings.getApiToken());
        return "settings";
    }

    @PostMapping("/settings")
    public String save(@RequestParam(required = false) String zabbixUrl,
                       @RequestParam(required = false) String apiToken,
                       RedirectAttributes redirectAttributes) {
        try {
            settingsService.save(zabbixUrl, apiToken);
            redirectAttributes.addFlashAttribute("successMessage", "Настройки подключения сохранены.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/settings";
    }
}
