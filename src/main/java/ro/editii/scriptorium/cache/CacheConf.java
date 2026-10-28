package ro.editii.scriptorium.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.editii.scriptorium.Util;

import java.io.File;

@Configuration
public class CacheConf {

    public static final String CACHE_TOC = "cacheToc";
    public static final String CACHE_NODE = "cacheNode";
    public static final String CACHE_BINARY_OBJECT = "cacheBinaryObject";
    /** Search hits' plain text by path (PathContentResolver). */
    public static final String CACHE_HIT_TEXT = "hit_text";

    @Value("${cache.dir}")
    String cacheDir;

    @Bean(CACHE_TOC)
    public DiskCache cacheToc() {
        final String replacedTilde = Util.replaceTilde(this.cacheDir);
        // "toc-v2": TOCs cached before TeiElem.dbChildren was ordered may
        // hold divisions in database storage order - never read those again.
        return new DiskCache(new File(replacedTilde), "toc-v2");
    }

    @Bean(CACHE_NODE)
    public DiskCache cacheNode() {
        final String replacedTilde = Util.replaceTilde(this.cacheDir);
        return new DiskCache(new File(replacedTilde), "node");
    }

    @Bean(CACHE_BINARY_OBJECT)
    public DiskCache cacheBinaryObject() {
        final String replacedTilde = Util.replaceTilde(this.cacheDir);
        return new DiskCache(new File(replacedTilde), "binaryObject");
    }

    @Bean("cacheManager")
    public CacheManager cacheManager() {
        final CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .initialCapacity(10)
                .maximumSize(50)
        );
        // Far more entries than the 50 above: a search resolves 10-20 hits,
        // each a short paragraph. Cleared with every other cache on import
        // (TeiFileDbService.evictAllCaches), so edited text is never stale.
        cacheManager.registerCustomCache(CACHE_HIT_TEXT, Caffeine.newBuilder()
                .maximumSize(5_000)
                .build());
        return cacheManager;
    }
}
