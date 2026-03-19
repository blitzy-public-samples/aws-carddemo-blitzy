package com.cardemo.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for configuration classes to improve coverage of the
 * {@code com.cardemo.config} package.
 *
 * <p>Covers {@link AppProperties} (application-level + batch nested props),
 * {@link JpaConfig}, and basic instantiation of config beans.</p>
 */
class ConfigCoverageTest {

    // ══════════════════════════════════════════════════════════════
    //  AppProperties — Application-Level Properties
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AppProperties — Application-Level")
    class AppPropertiesApp {

        @Test
        @DisplayName("Default applicationName is 'CardDemo'")
        void defaultApplicationName() {
            AppProperties props = new AppProperties();
            assertThat(props.getApplicationName()).isEqualTo("CardDemo");
        }

        @Test
        @DisplayName("setApplicationName updates the value")
        void setApplicationName() {
            AppProperties props = new AppProperties();
            props.setApplicationName("TestApp");
            assertThat(props.getApplicationName()).isEqualTo("TestApp");
        }

        @Test
        @DisplayName("Default applicationVersion is '2.0.0'")
        void defaultApplicationVersion() {
            AppProperties props = new AppProperties();
            assertThat(props.getApplicationVersion()).isEqualTo("2.0.0");
        }

        @Test
        @DisplayName("setApplicationVersion updates the value")
        void setApplicationVersion() {
            AppProperties props = new AppProperties();
            props.setApplicationVersion("3.0.0");
            assertThat(props.getApplicationVersion()).isEqualTo("3.0.0");
        }

        @Test
        @DisplayName("Default batch property is not null")
        void defaultBatchNotNull() {
            AppProperties props = new AppProperties();
            assertThat(props.getBatch()).isNotNull();
        }

        @Test
        @DisplayName("setBatch replaces the nested batch object")
        void setBatch() {
            AppProperties props = new AppProperties();
            AppProperties.Batch batch = new AppProperties.Batch();
            batch.setChunkSize(200);
            props.setBatch(batch);
            assertThat(props.getBatch().getChunkSize()).isEqualTo(200);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  AppProperties.Batch — Nested Batch Properties
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AppProperties.Batch — Batch Configuration")
    class AppPropertiesBatch {

        @Test
        @DisplayName("Default inputDirectory")
        void defaultInputDirectory() {
            AppProperties.Batch batch = new AppProperties.Batch();
            assertThat(batch.getInputDirectory()).isNotNull();
        }

        @Test
        @DisplayName("setInputDirectory updates the value")
        void setInputDirectory() {
            AppProperties.Batch batch = new AppProperties.Batch();
            batch.setInputDirectory("/data/input");
            assertThat(batch.getInputDirectory()).isEqualTo("/data/input");
        }

        @Test
        @DisplayName("Default outputDirectory")
        void defaultOutputDirectory() {
            AppProperties.Batch batch = new AppProperties.Batch();
            assertThat(batch.getOutputDirectory()).isNotNull();
        }

        @Test
        @DisplayName("setOutputDirectory updates the value")
        void setOutputDirectory() {
            AppProperties.Batch batch = new AppProperties.Batch();
            batch.setOutputDirectory("/data/output");
            assertThat(batch.getOutputDirectory()).isEqualTo("/data/output");
        }

        @Test
        @DisplayName("Default rejectFilePath")
        void defaultRejectFilePath() {
            AppProperties.Batch batch = new AppProperties.Batch();
            assertThat(batch.getRejectFilePath()).isNotNull();
        }

        @Test
        @DisplayName("setRejectFilePath updates the value")
        void setRejectFilePath() {
            AppProperties.Batch batch = new AppProperties.Batch();
            batch.setRejectFilePath("rejects.txt");
            assertThat(batch.getRejectFilePath()).isEqualTo("rejects.txt");
        }

        @Test
        @DisplayName("Default statementOutputPath")
        void defaultStatementOutputPath() {
            AppProperties.Batch batch = new AppProperties.Batch();
            assertThat(batch.getStatementOutputPath()).isNotNull();
        }

        @Test
        @DisplayName("setStatementOutputPath updates the value")
        void setStatementOutputPath() {
            AppProperties.Batch batch = new AppProperties.Batch();
            batch.setStatementOutputPath("/statements");
            assertThat(batch.getStatementOutputPath()).isEqualTo("/statements");
        }

        @Test
        @DisplayName("Default chunkSize is positive")
        void defaultChunkSize() {
            AppProperties.Batch batch = new AppProperties.Batch();
            assertThat(batch.getChunkSize()).isGreaterThan(0);
        }

        @Test
        @DisplayName("setChunkSize updates the value")
        void setChunkSize() {
            AppProperties.Batch batch = new AppProperties.Batch();
            batch.setChunkSize(500);
            assertThat(batch.getChunkSize()).isEqualTo(500);
        }

        @Test
        @DisplayName("Default maxRetries is positive")
        void defaultMaxRetries() {
            AppProperties.Batch batch = new AppProperties.Batch();
            assertThat(batch.getMaxRetries()).isGreaterThan(0);
        }

        @Test
        @DisplayName("setMaxRetries updates the value")
        void setMaxRetries() {
            AppProperties.Batch batch = new AppProperties.Batch();
            batch.setMaxRetries(5);
            assertThat(batch.getMaxRetries()).isEqualTo(5);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  JpaConfig — Annotation-Driven Configuration
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("JpaConfig")
    class JpaConfigTest {

        @Test
        @DisplayName("JpaConfig is instantiable")
        void isInstantiable() {
            JpaConfig config = new JpaConfig();
            assertThat(config).isNotNull();
        }
    }
}
