package com.example.multibuild.pipeline;

import com.example.multibuild.model.BuildMode;
import com.example.multibuild.model.BuildServiceType;
import com.example.multibuild.model.RepoConfig;

import java.nio.file.Path;
import java.util.List;

public record BuildContext(
        List<RepoConfig> repos,
        Path cloneDir,
        String integrationBranch,
        BuildMode buildMode,
        BuildServiceType buildService,
        boolean buildEnabled,
        boolean dryMode,
        boolean skipGit,
        String buildTarget,
        boolean buildTargetWithDeps,
        String defaultSourceBranch,
        String githubToken,
        boolean gitAuthTokenInUrl,
        String resumeStateFile
) {}
