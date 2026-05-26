package com.example.multibuild.web.service;

import com.example.multibuild.model.BuildMode;
import com.example.multibuild.model.BuildServiceType;
import com.example.multibuild.pipeline.BuildContext;
import com.example.multibuild.web.dto.BuildRequest;
import com.example.multibuild.web.dto.SettingsDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SettingsService {

    private static final String MASKED = "***";

    @Value("${clone.dir}")
    private String defaultCloneDir;

    @Value("${integration.branch:}")
    private String defaultIntegrationBranch;

    @Value("${build.mode:SNAPSHOT}")
    private BuildMode defaultBuildMode;

    @Value("${build.service:LOCAL}")
    private BuildServiceType defaultBuildService;

    @Value("${build.enabled:false}")
    private boolean defaultBuildEnabled;

    @Value("${dry.mode:false}")
    private boolean defaultDryMode;

    @Value("${skip.git:false}")
    private boolean defaultSkipGit;

    @Value("${default.source.branch:main}")
    private String defaultSourceBranch;

    @Value("${github.token:}")
    private String defaultGithubToken;

    @Value("${git.auth.token.in.url:false}")
    private boolean defaultGitAuthTokenInUrl;

    @Value("${teamcity.url:}")
    private String defaultTeamcityUrl;

    @Value("${teamcity.token:}")
    private String defaultTeamcityToken;

    @Value("${teamcity.poll-interval-ms:5000}")
    private int defaultTeamcityPollIntervalMs;

    @Value("${lightspeed.maven-repo.snapshots-url:}")
    private String defaultLightspeedSnapshotsUrl;

    @Value("${lightspeed.maven-repo.releases-url:}")
    private String defaultLightspeedReleasesUrl;

    @Value("${lightspeed.maven-repo.username:}")
    private String defaultLightspeedUsername;

    @Value("${lightspeed.maven-repo.password:}")
    private String defaultLightspeedPassword;

    private final ConcurrentHashMap<String, Object> overrides = new ConcurrentHashMap<>();

    public SettingsDto load() {
        return new SettingsDto(
                str("cloneDir", defaultCloneDir),
                str("integrationBranch", defaultIntegrationBranch),
                enumVal("buildMode", defaultBuildMode, BuildMode.class),
                enumVal("buildService", defaultBuildService, BuildServiceType.class),
                bool("buildEnabled", defaultBuildEnabled),
                bool("dryMode", defaultDryMode),
                bool("skipGit", defaultSkipGit),
                str("defaultSourceBranch", defaultSourceBranch),
                maskToken(str("githubToken", defaultGithubToken)),
                bool("gitAuthTokenInUrl", defaultGitAuthTokenInUrl),
                str("teamcityUrl", defaultTeamcityUrl),
                maskToken(str("teamcityToken", defaultTeamcityToken)),
                intVal("teamcityPollIntervalMs", defaultTeamcityPollIntervalMs),
                str("lightspeedSnapshotsUrl", defaultLightspeedSnapshotsUrl),
                str("lightspeedReleasesUrl", defaultLightspeedReleasesUrl),
                str("lightspeedUsername", defaultLightspeedUsername),
                maskToken(str("lightspeedPassword", defaultLightspeedPassword))
        );
    }

    public SettingsDto save(SettingsDto dto) {
        if (dto.cloneDir() != null) overrides.put("cloneDir", dto.cloneDir());
        if (dto.integrationBranch() != null) overrides.put("integrationBranch", dto.integrationBranch());
        if (dto.buildMode() != null) overrides.put("buildMode", dto.buildMode());
        if (dto.buildService() != null) overrides.put("buildService", dto.buildService());
        overrides.put("buildEnabled", dto.buildEnabled());
        overrides.put("dryMode", dto.dryMode());
        overrides.put("skipGit", dto.skipGit());
        if (dto.defaultSourceBranch() != null) overrides.put("defaultSourceBranch", dto.defaultSourceBranch());
        if (dto.githubToken() != null && !MASKED.equals(dto.githubToken())) overrides.put("githubToken", dto.githubToken());
        overrides.put("gitAuthTokenInUrl", dto.gitAuthTokenInUrl());
        if (dto.teamcityUrl() != null) overrides.put("teamcityUrl", dto.teamcityUrl());
        if (dto.teamcityToken() != null && !MASKED.equals(dto.teamcityToken())) overrides.put("teamcityToken", dto.teamcityToken());
        if (dto.teamcityPollIntervalMs() != null) overrides.put("teamcityPollIntervalMs", dto.teamcityPollIntervalMs());
        if (dto.lightspeedSnapshotsUrl() != null) overrides.put("lightspeedSnapshotsUrl", dto.lightspeedSnapshotsUrl());
        if (dto.lightspeedReleasesUrl() != null) overrides.put("lightspeedReleasesUrl", dto.lightspeedReleasesUrl());
        if (dto.lightspeedUsername() != null) overrides.put("lightspeedUsername", dto.lightspeedUsername());
        if (dto.lightspeedPassword() != null && !MASKED.equals(dto.lightspeedPassword())) overrides.put("lightspeedPassword", dto.lightspeedPassword());
        return load();
    }

    public BuildContext toBuildContext(BuildRequest request) {
        BuildMode buildMode = request.buildMode() != null ? request.buildMode()
                : enumVal("buildMode", defaultBuildMode, BuildMode.class);
        boolean buildEnabled = request.buildEnabled() != null ? request.buildEnabled()
                : bool("buildEnabled", defaultBuildEnabled);
        boolean dryMode = request.dryMode() != null ? request.dryMode()
                : bool("dryMode", defaultDryMode);
        String integrationBranch = request.integrationBranch() != null ? request.integrationBranch()
                : str("integrationBranch", defaultIntegrationBranch);
        String buildTarget = request.buildTarget() != null ? request.buildTarget() : "";
        boolean buildTargetWithDeps = request.buildTargetWithDeps() != null ? request.buildTargetWithDeps() : false;

        return new BuildContext(
                request.repos(),
                Paths.get(str("cloneDir", defaultCloneDir)),
                integrationBranch,
                buildMode,
                enumVal("buildService", defaultBuildService, BuildServiceType.class),
                buildEnabled,
                dryMode,
                bool("skipGit", defaultSkipGit),
                buildTarget,
                buildTargetWithDeps,
                str("defaultSourceBranch", defaultSourceBranch),
                str("githubToken", defaultGithubToken),
                bool("gitAuthTokenInUrl", defaultGitAuthTokenInUrl),
                ""
        );
    }

    private String str(String key, String defaultVal) {
        Object v = overrides.get(key);
        return v instanceof String s ? s : defaultVal;
    }

    private boolean bool(String key, boolean defaultVal) {
        Object v = overrides.get(key);
        return v instanceof Boolean b ? b : defaultVal;
    }

    private int intVal(String key, int defaultVal) {
        Object v = overrides.get(key);
        return v instanceof Integer i ? i : defaultVal;
    }

    @SuppressWarnings("unchecked")
    private <E extends Enum<E>> E enumVal(String key, E defaultVal, Class<E> type) {
        Object v = overrides.get(key);
        return type.isInstance(v) ? (E) v : defaultVal;
    }

    private String maskToken(String value) {
        if (value == null || value.isBlank()) return value;
        return MASKED;
    }
}
