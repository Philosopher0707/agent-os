package com.agentos.kernel.impl;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class DirectoryCacheTest {

    @Test
    void shouldCacheAndRetrieve() {
        var cache = new DirectoryCache(Duration.ofSeconds(5));
        cache.put("agent-1", "container-1");
        assertThat(cache.get("agent-1")).hasValue("container-1");
        assertThat(cache.get("agent-2")).isEmpty();
    }

    @Test
    void shouldExpireAfterTtl() throws InterruptedException {
        var cache = new DirectoryCache(Duration.ofMillis(50));
        cache.put("agent-1", "c1");
        assertThat(cache.get("agent-1")).hasValue("c1");
        Thread.sleep(100);
        assertThat(cache.get("agent-1")).isEmpty();
    }

    @Test
    void shouldInvalidateEntry() {
        var cache = new DirectoryCache(Duration.ofSeconds(10));
        cache.put("agent-1", "c1");
        cache.invalidate("agent-1");
        assertThat(cache.get("agent-1")).isEmpty();
    }
}
