package com.example.multibuild.web.controller;

import com.example.multibuild.session.BuildOrchestrator;
import com.example.multibuild.web.dto.BuildRequest;
import com.example.multibuild.web.dto.GraphResponse;
import com.example.multibuild.web.service.SettingsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final BuildOrchestrator orchestrator;
    private final SettingsService settingsService;

    public GraphController(BuildOrchestrator orchestrator, SettingsService settingsService) {
        this.orchestrator = orchestrator;
        this.settingsService = settingsService;
    }

    @PostMapping
    public GraphResponse previewGraph(@RequestBody BuildRequest request) {
        return orchestrator.preview(settingsService.toBuildContext(request));
    }
}
