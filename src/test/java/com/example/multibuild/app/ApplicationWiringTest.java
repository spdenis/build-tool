package com.example.multibuild.app;

import com.example.multibuild.build.BuildService;
import com.example.multibuild.build.DispatchingBuildService;
import com.example.multibuild.buildsystem.DependencyUpdater;
import com.example.multibuild.buildsystem.ProjectDependencyUpdater;
import com.example.multibuild.buildsystem.ProjectParser;
import com.example.multibuild.buildsystem.ProjectParserDispatcher;
import com.example.multibuild.buildsystem.ProjectVersionUpdater;
import com.example.multibuild.buildsystem.VersionUpdater;
import com.example.multibuild.gradle.GradleMigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;

// Starts the real component graph (minus the CommandLineRunner) to catch ambiguous or missing beans:
// several beans implement ProjectParser / VersionUpdater / DependencyUpdater, the dispatchers must win.
class ApplicationWiringTest {

    @Configuration
    @ComponentScan(basePackages = "com.example.multibuild",
            excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = Main.class))
    static class Components {}

    @Test
    void contextStarts_andDispatchersArePrimary() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(Components.class)
                .withPropertyValues("integration.branch=int")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(ProjectParser.class)).isInstanceOf(ProjectParserDispatcher.class);
                    assertThat(ctx.getBean(VersionUpdater.class)).isInstanceOf(ProjectVersionUpdater.class);
                    assertThat(ctx.getBean(DependencyUpdater.class)).isInstanceOf(ProjectDependencyUpdater.class);
                    assertThat(ctx.getBean(BuildService.class)).isInstanceOf(DispatchingBuildService.class);
                    assertThat(ctx).hasSingleBean(GradleMigrationService.class);
                });
    }
}
