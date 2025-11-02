package com.carddemo.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Boot configuration class for Redis integration providing session management
 * and application-level caching.
 * 
 * <p>This configuration replaces CICS pseudo-conversational COMMAREA state management
 * pattern with Redis-backed stateless session storage, enabling horizontal scalability
 * while maintaining user context between REST API calls.</p>
 * 
 * <p>Key Features:</p>
 * <ul>
 *   <li>RedisConnectionFactory with Lettuce client for connection pooling</li>
 *   <li>RedisTemplate with Jackson JSON serialization for object storage</li>
 *   <li>Spring Session Redis for HTTP session persistence (30-minute timeout)</li>
 *   <li>RedisCacheManager for application-level caching with configurable TTL</li>
 *   <li>Cache names: accounts, cards, transactions, users (1-hour TTL)</li>
 * </ul>
 * 
 * <p>COBOL/CICS to Spring Boot Transformation:</p>
 * <ul>
 *   <li>CICS COMMAREA state → Redis-backed HTTP session</li>
 *   <li>Pseudo-conversational processing → Stateless REST + Redis session</li>
 *   <li>CICS transaction timeout → 30-minute session timeout</li>
 * </ul>
 * 
 * @see org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession
 * @see org.springframework.cache.annotation.EnableCaching
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800) // 30 minutes session timeout
@EnableCaching
public class RedisConfig {

    /**
     * Session timeout constant: 30 minutes (1800 seconds).
     * Matches typical CICS transaction timeout patterns.
     */
    private static final int SESSION_TIMEOUT_SECONDS = 1800;

    /**
     * Default cache TTL constant: 1 hour (3600 seconds).
     * Used for frequently accessed reference data caching.
     */
    private static final long DEFAULT_CACHE_TTL_SECONDS = 3600;

    /**
     * Redis connection timeout constant: 2 seconds (2000 milliseconds).
     */
    private static final long CONNECTION_TIMEOUT_MILLIS = 2000;

    /**
     * Session namespace prefix for Redis keys.
     */
    private static final String SESSION_NAMESPACE = "carddemo:session";

    /**
     * Cache key prefix for application-level caching.
     */
    private static final String CACHE_KEY_PREFIX = "carddemo:cache:";

    /**
     * Configures RedisConnectionFactory with Lettuce client for connection pooling.
     * 
     * <p>Connection properties are externalized in application.yml:</p>
     * <ul>
     *   <li>spring.data.redis.host - Redis server hostname (default: localhost)</li>
     *   <li>spring.data.redis.port - Redis server port (default: 6379)</li>
     *   <li>spring.data.redis.password - Redis password (if authentication enabled)</li>
     *   <li>spring.data.redis.database - Redis database number (default: 0)</li>
     * </ul>
     * 
     * <p>Lettuce is used over Jedis for better performance and reactive support.</p>
     * 
     * @param env Spring Environment for reading configuration properties
     * @return RedisConnectionFactory configured with Lettuce driver
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory(Environment env) {
        // Read Redis connection properties from application.yml
        String host = env.getProperty("spring.data.redis.host", "localhost");
        int port = env.getProperty("spring.data.redis.port", Integer.class, 6379);
        String password = env.getProperty("spring.data.redis.password");
        int database = env.getProperty("spring.data.redis.database", Integer.class, 0);

        // Configure Redis standalone server connection
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(host);
        redisConfig.setPort(port);
        redisConfig.setDatabase(database);
        
        // Set password if authentication is enabled
        if (password != null && !password.isEmpty()) {
            redisConfig.setPassword(password);
        }

        // Configure Lettuce client with connection timeout and pooling
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(CONNECTION_TIMEOUT_MILLIS))
                .build();

        // Create and return Lettuce connection factory
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisConfig, clientConfig);
        factory.afterPropertiesSet();
        
        return factory;
    }

    /**
     * Configures RedisTemplate for object serialization and storage in Redis.
     * 
     * <p>Serialization Configuration:</p>
     * <ul>
     *   <li>Key Serializer: StringRedisSerializer (for readable string keys)</li>
     *   <li>Value Serializer: GenericJackson2JsonRedisSerializer (for JSON object storage)</li>
     *   <li>Hash Key Serializer: StringRedisSerializer</li>
     *   <li>Hash Value Serializer: GenericJackson2JsonRedisSerializer</li>
     * </ul>
     * 
     * <p>Transaction support is enabled to ensure consistency with database operations.</p>
     * 
     * @param connectionFactory Redis connection factory
     * @return RedisTemplate configured with Jackson JSON serialization
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Configure serializers for key-value pairs
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

        // Set key and value serializers
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jsonSerializer);

        // Enable transaction support for consistency with database transactions
        template.setEnableTransactionSupport(true);

        // Initialize template after configuration
        template.afterPropertiesSet();

        return template;
    }

    /**
     * Configures RedisCacheManager for application-level caching of frequently
     * accessed reference data.
     * 
     * <p>Cache Configuration:</p>
     * <ul>
     *   <li>Default TTL: 1 hour (3600 seconds)</li>
     *   <li>Cache names: accounts, cards, transactions, users</li>
     *   <li>Null values: not cached (to avoid cache pollution)</li>
     *   <li>Key prefix: carddemo:cache: (for namespace isolation)</li>
     * </ul>
     * 
     * <p>Replaces frequent VSAM file reads with Redis cache for improved performance
     * while maintaining data consistency through TTL-based expiration.</p>
     * 
     * <p>Cache Usage Examples:</p>
     * <pre>
     * &#64;Cacheable(value = "accounts", key = "#accountId")
     * public Account getAccount(Long accountId) { ... }
     * 
     * &#64;CacheEvict(value = "accounts", key = "#account.id")
     * public Account updateAccount(Account account) { ... }
     * </pre>
     * 
     * @param connectionFactory Redis connection factory
     * @return CacheManager configured with Redis caching
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Configure default cache settings
        RedisCacheConfiguration defaultCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(DEFAULT_CACHE_TTL_SECONDS))
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> CACHE_KEY_PREFIX + cacheName + ":")
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()
                        )
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()
                        )
                );

        // Configure specific cache names with custom TTLs if needed
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        
        // Account cache: 1 hour TTL (default)
        cacheConfigurations.put("accounts", defaultCacheConfig);
        
        // Card cache: 1 hour TTL (default)
        cacheConfigurations.put("cards", defaultCacheConfig);
        
        // Transaction cache: 1 hour TTL (default)
        cacheConfigurations.put("transactions", defaultCacheConfig);
        
        // User cache: 1 hour TTL (default)
        cacheConfigurations.put("users", defaultCacheConfig);

        // Build and return RedisCacheManager
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultCacheConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware() // Enable transaction-aware caching
                .build();
    }
}
