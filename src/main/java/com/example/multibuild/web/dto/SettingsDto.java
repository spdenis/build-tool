package com.example.multibuild.web.dto;

import com.example.multibuild.model.BuildMode;
import com.example.multibuild.model.BuildServiceType;

public record SettingsDto(
        String cloneDir,
        String integrationBranch,
        BuildMode buildMode,
        BuildServiceType buildService,
        boolean buildEnabled,
        boolean dryMode,
        boolean skipGit,
        String defaultSourceBranch,
        String githubToken,
        boolean gitAuthTokenInUrl,
        String teamcityUrl,
        String teamcityToken,
        Integer teamcityPollIntervalMs,
        String lightspeedSnapshotsUrl,
        String lightspeedReleasesUrl,
        String lightspeedUsername,
        String lightspeedPassword
) {}
