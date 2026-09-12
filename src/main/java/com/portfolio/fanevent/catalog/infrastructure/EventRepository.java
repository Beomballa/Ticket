package com.portfolio.fanevent.catalog.infrastructure;

import com.portfolio.fanevent.catalog.domain.Event;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Query("select event.id from Event event where event.artist.id = :artistId")
    List<Long> findIdsByArtistId(@Param("artistId") Long artistId);
}
