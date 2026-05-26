package com.example.multibuild.web.dto;

import com.example.multibuild.model.BuildMode;
import com.example.multibuild.model.RepoConfig;

import java.util.List;

public record BuildRequest(
        List<RepoConfig> repos,
        BuildMode buildMode,
        Boolean buildEnabled,
        Boolean dryMode,
        String integrationBranch,
        String buildTarget,
        Boolean buildTargetWithDeps
) {}
