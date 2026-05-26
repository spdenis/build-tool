package com.example.multibuild.web.dto;

import com.example.multibuild.session.BuildSession;

import java.time.Instant;
import java.util.List;

public record BuildStatusResponse(
        String sessionId,
        BuildSession.Status status,
        Instant startedAt,
        String errorMessage,
        List<String> recentLogLines
) {}
