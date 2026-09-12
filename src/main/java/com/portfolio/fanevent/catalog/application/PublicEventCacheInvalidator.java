package com.portfolio.fanevent.catalog.application;

import java.util.Collection;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class PublicEventCacheInvalidator {

    private final PublicEventCache publicEventCache;

    public PublicEventCacheInvalidator(PublicEventCache publicEventCache) {
        this.publicEventCache = publicEventCache;
    }

    public void evictAfterCommit(Long eventId) {
        evictAllAfterCommit(java.util.List.of(eventId));
    }

    public void evictAllAfterCommit(Collection<Long> eventIds) {
        java.util.List<Long> targets = eventIds.stream().distinct().toList();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            targets.forEach(publicEventCache::evict);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                targets.forEach(publicEventCache::evict);
            }
        });
    }
}
