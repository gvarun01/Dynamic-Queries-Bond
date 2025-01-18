package com.qualcomm.qti.qa.ml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A simple LRU (Least Recently Used) cache for storing QA answers.
 * This cache helps improve performance by storing previously computed answers.
 */
public class QaAnswerCache {
    // Maximum number of entries in the cache
    private static final int MAX_CACHE_SIZE = 2;

    // Internal cache using LinkedHashMap for LRU eviction strategy
    private final Map<CacheKey, String> cache;

    /**
     * Private constructor to enforce singleton pattern
     */
    private QaAnswerCache() {
        // Create a thread-safe LinkedHashMap with access-order and fixed maximum size
        cache = new LinkedHashMap<CacheKey, String>(MAX_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, String> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };
    }

    // Singleton instance
    private static QaAnswerCache instance;

    /**
     * Get the singleton instance of the cache
     * @return QaAnswerCache instance
     */
    public static synchronized QaAnswerCache getInstance() {
        if (instance == null) {
            instance = new QaAnswerCache();
        }
        return instance;
    }

    /**
     * Retrieve an answer from the cache
     * @param content The context content
     * @param question The question asked
     * @return Cached answer or null if not found
     */
    public synchronized String getAnswer(String content, String question) {
        return cache.get(new CacheKey(content, question));
    }

    /**
     * Store an answer in the cache
     * @param content The context content
     * @param question The question asked
     * @param answer The computed answer
     */
    public synchronized void putAnswer(String content, String question, String answer) {
        cache.put(new CacheKey(content, question), answer);
    }

    /**
     * Check if an answer exists in the cache
     * @param content The context content
     * @param question The question asked
     * @return true if the answer is cached, false otherwise
     */
    public synchronized boolean hasAnswer(String content, String question) {
        return cache.containsKey(new CacheKey(content, question));
    }

    /**
     * Internal cache key that combines content and question
     */
    private static class CacheKey {
        private final String content;
        private final String question;

        public CacheKey(String content, String question) {
            this.content = content;
            this.question = question;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return content.equals(cacheKey.content) && question.equals(cacheKey.question);
        }

        @Override
        public int hashCode() {
            int result = content.hashCode();
            result = 31 * result + question.hashCode();
            return result;
        }
    }
}
