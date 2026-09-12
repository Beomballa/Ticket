package com.portfolio.fanevent.catalog.infrastructure;

import com.portfolio.fanevent.catalog.domain.Artist;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtistRepository extends JpaRepository<Artist, Long> {
}
