package com.example.multibuild.web.controller;

import com.example.multibuild.pipeline.BuildContext;
import com.example.multibuild.session.BuildOrchestrator;
import com.example.multibuild.session.BuildSession;
import com.example.multibuild.session.BuildSessionRegistry;
import com.example.multibuild.web.dto.BuildRequest;
import com.example.multibuild.web.dto.BuildStatusResponse;
import com.example.multibuild.web.service.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/builds")
public class BuildController {

    private final BuildSessionRegistry registry;
    private final BuildOrchestrator orchestrator;
    private final SettingsService settingsService;

    public BuildController(BuildSessionRegistry registry, BuildOrchestrator orchestrator,
                           SettingsService settingsService) {
        this.registry = registry;
        this.orchestrator = orchestrator;
        this.settingsService = settingsService;
    }

    @PostMapping
    public ResponseEntity<BuildStatusResponse> startBuild(@RequestBody BuildRequest request) {
        BuildSession session = registry.create();
        BuildContext context = settingsService.toBuildContext(request);
        orchestrator.start(session, context);
        return ResponseEntity.accepted().body(toResponse(session, 200));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<BuildStatusResponse> getStatus(@PathVariable String sessionId) {
        return registry.find(sessionId)
                .map(s -> ResponseEntity.ok(toResponse(s, 200)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<BuildStatusResponse> listSessions() {
        return registry.listRecent().stream()
                .map(s -> toResponse(s, 200))
                .toList();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> cancelBuild(@PathVariable String sessionId) {
        return registry.find(sessionId)
                .<ResponseEntity<Void>>map(s -> {
                    s.cancel();
                    return ResponseEntity.<Void>noContent().build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private BuildStatusResponse toResponse(BuildSession session, int maxLogLines) {
        List<String> logLines = session.getLogLines();
        List<String> recent = logLines.size() <= maxLogLines
                ? List.copyOf(logLines)
                : logLines.subList(logLines.size() - maxLogLines, logLines.size());
        return new BuildStatusResponse(
                session.getId(),
                session.getStatus(),
                session.getStartedAt(),
                session.getErrorMessage(),
                recent
        );
    }
}
