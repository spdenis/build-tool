package com.example.multibuild.testutil;

import com.example.multibuild.gradle.MavenToGradleConverter;
import org.springframework.test.util.ReflectionTestUtils;

// Converters for tests: the default build-logic layout, or the root-script layout (versions in
// gradle.properties, parent configuration in root script blocks) the older converter tests describe.
public final class Converters {

    private Converters() {}

    public static MavenToGradleConverter rootScriptLayout() {
        MavenToGradleConverter converter = new MavenToGradleConverter();
        ReflectionTestUtils.setField(converter, "layout", "root-script");
        return converter;
    }
}
