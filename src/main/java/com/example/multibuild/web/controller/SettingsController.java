package com.example.multibuild.web.controller;

import com.example.multibuild.web.dto.SettingsDto;
import com.example.multibuild.web.service.SettingsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public SettingsDto getSettings() {
        return settingsService.load();
    }

    @PutMapping
    public SettingsDto saveSettings(@RequestBody SettingsDto settings) {
        return settingsService.save(settings);
    }
}
