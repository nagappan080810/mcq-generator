package com.example.hello.config;

import io.lettuce.core.RedisURI;
import io.lettuce.core.resource.ClientResources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;

/**
 * Redis connection factory configured for managed Redis providers such as
 * Upstash, which require TLS.
 *
 * <p>TLS is enabled automatically when the {@code REDIS_URL} uses the
 * {@code rediss://} scheme, when the host is an Upstash host, or when
 * {@code mcq.redis.ssl=true} is set. This avoids the "Connection closed
 * prematurely" / handshake failure caused by attempting a plaintext
 * connection against a TLS-only endpoint.</p>
 *
 * <p>Only activates when a {@code REDIS_URL} / {@code spring.data.redis.url} is
 * configured; otherwise (e.g. in tests) the connection factory is not created.</p>
 */
@Configuration
@ConditionalOnExpression("'${spring.data.redis.url:${REDIS_URL:}}'.trim().length() > 0")
public class RedisConfig {

    @Value("${spring.data.redis.url:${REDIS_URL:}}")
    private String redisUrl;

    @Value("${mcq.redis.ssl:false}")
    private boolean forceSsl;

    @Value("${spring.data.redis.timeout:5000}")
    private long timeoutMillis;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisURI uri = parseUri(redisUrl);
        boolean useSsl = determineSsl(uri, redisUrl);

        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration();
        standalone.setHostName(uri.getHost());
        standalone.setPort(uri.getPort());
        if (uri.getDatabase() != 0) {
            standalone.setDatabase((int) uri.getDatabase());
        }
        if (uri.getPassword() != null && uri.getPassword().length > 0) {
            standalone.setPassword(new String(uri.getPassword()));
        } else if (uri.getUsername() != null) {
            standalone.setUsername(uri.getUsername());
        }

        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder()
                        .clientResources(ClientResources.create())
                        .shutdownTimeout(java.time.Duration.ofMillis(timeoutMillis))
                        .commandTimeout(java.time.Duration.ofMillis(timeoutMillis));
        if (useSsl) {
            builder.useSsl();
        }
        LettuceClientConfiguration clientConfig = builder.build();

        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(standalone, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }

    private RedisURI parseUri(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Redis URL is not configured. Set REDIS_URL or spring.data.redis.url.");
        }
        // Normalize rediss:// -> redis:// for RedisURI parsing (Lettuce scheme handling),
        // we apply TLS ourselves via useSsl().
        String normalized = url.startsWith("rediss://") ? "redis://" + url.substring("rediss://".length()) : url;
        return RedisURI.create(URI.create(normalized));
    }

    private boolean determineSsl(RedisURI uri, String url) {
        if (forceSsl) {
            return true;
        }
        if (url != null && url.startsWith("rediss://")) {
            return true;
        }
        String host = uri.getHost();
        return host != null && host.contains(".upstash.io");
    }
}
