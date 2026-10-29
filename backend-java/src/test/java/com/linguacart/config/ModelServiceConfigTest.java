package com.linguacart.config;

import com.linguacart.client.HttpModelServiceClient;
import com.linguacart.client.MockModelServiceClient;
import com.linguacart.client.ModelServiceClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ModelServiceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ModelServiceConfig.class);

    @Test
    @DisplayName("defaults to MockModelServiceClient when mode is not set")
    void testDefaultMockClient() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ModelServiceClient.class);
            assertThat(context.getBean(ModelServiceClient.class)).isInstanceOf(MockModelServiceClient.class);
        });
    }

    @Test
    @DisplayName("loads MockModelServiceClient when mode=mock")
    void testExplicitMockClient() {
        contextRunner.withPropertyValues("linguacart.model-service.mode=mock")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelServiceClient.class);
                    assertThat(context.getBean(ModelServiceClient.class)).isInstanceOf(MockModelServiceClient.class);
                });
    }

    @Test
    @DisplayName("loads HttpModelServiceClient when mode=http")
    void testHttpClient() {
        contextRunner.withPropertyValues("linguacart.model-service.mode=http")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelServiceClient.class);
                    assertThat(context.getBean(ModelServiceClient.class)).isInstanceOf(HttpModelServiceClient.class);
                });
    }
}
