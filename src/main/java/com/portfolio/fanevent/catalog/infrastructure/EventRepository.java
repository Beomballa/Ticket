package com.portfolio.fanevent.catalog.infrastructure;

import com.portfolio.fanevent.catalog.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {
}
