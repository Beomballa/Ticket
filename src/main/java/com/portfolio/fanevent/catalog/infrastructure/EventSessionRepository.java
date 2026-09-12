package com.portfolio.fanevent.catalog.infrastructure;

import com.portfolio.fanevent.catalog.domain.EventSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSessionRepository extends JpaRepository<EventSession, Long> {
}
