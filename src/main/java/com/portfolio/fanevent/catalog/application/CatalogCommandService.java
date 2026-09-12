package com.portfolio.fanevent.catalog.application;

import com.portfolio.fanevent.catalog.domain.Artist;
import com.portfolio.fanevent.catalog.domain.Event;
import com.portfolio.fanevent.catalog.domain.EventSession;
import com.portfolio.fanevent.catalog.domain.EventStatus;
import com.portfolio.fanevent.catalog.domain.EventType;
import com.portfolio.fanevent.catalog.domain.InventoryType;
import com.portfolio.fanevent.catalog.domain.SellableInventory;
import com.portfolio.fanevent.catalog.infrastructure.ArtistRepository;
import com.portfolio.fanevent.catalog.infrastructure.EventRepository;
import com.portfolio.fanevent.catalog.infrastructure.EventSessionRepository;
import com.portfolio.fanevent.catalog.infrastructure.SellableInventoryRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CatalogCommandService {

    private final ArtistRepository artistRepository;
    private final EventRepository eventRepository;
    private final EventSessionRepository eventSessionRepository;
    private final SellableInventoryRepository inventoryRepository;

    public CatalogCommandService(
            ArtistRepository artistRepository,
            EventRepository eventRepository,
            EventSessionRepository eventSessionRepository,
            SellableInventoryRepository inventoryRepository
    ) {
        this.artistRepository = artistRepository;
        this.eventRepository = eventRepository;
        this.eventSessionRepository = eventSessionRepository;
        this.inventoryRepository = inventoryRepository;
    }

    public Long createArtist(String name, String description) {
        return artistRepository.save(Artist.create(name, description)).getId();
    }

    public void updateArtist(Long artistId, String name, String description) {
        Artist artist = artistRepository.findById(artistId)
                .orElseThrow(() -> new EntityNotFoundException("아티스트를 찾을 수 없습니다: " + artistId));
        artist.update(name, description);
    }

    public Long createEvent(
            Long artistId,
            String title,
            String description,
            EventType type,
            Instant salesStartAt,
            Instant salesEndAt
    ) {
        Artist artist = artistRepository.findById(artistId)
                .orElseThrow(() -> new EntityNotFoundException("아티스트를 찾을 수 없습니다: " + artistId));
        Event event = Event.create(
                artist, title, description, type, salesStartAt, salesEndAt);
        return eventRepository.save(event).getId();
    }

    public void updateEvent(
            Long eventId,
            String title,
            String description,
            EventType type,
            Instant salesStartAt,
            Instant salesEndAt
    ) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("이벤트를 찾을 수 없습니다: " + eventId));
        event.update(title, description, type, salesStartAt, salesEndAt);
    }

    public void changeEventStatus(Long eventId, EventStatus targetStatus) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("이벤트를 찾을 수 없습니다: " + eventId));
        event.changeStatus(targetStatus);
    }

    public Long createSession(
            Long eventId,
            String name,
            String venue,
            Instant startsAt,
            Instant salesStartAt,
            Instant salesEndAt
    ) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("이벤트를 찾을 수 없습니다: " + eventId));
        EventSession session = EventSession.create(
                event, name, venue, startsAt, salesStartAt, salesEndAt);
        return eventSessionRepository.save(session).getId();
    }

    public void updateSession(
            Long sessionId,
            String name,
            String venue,
            Instant startsAt,
            Instant salesStartAt,
            Instant salesEndAt
    ) {
        EventSession session = eventSessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("회차를 찾을 수 없습니다: " + sessionId));
        session.update(name, venue, startsAt, salesStartAt, salesEndAt);
    }

    public Long createInventory(
            Long sessionId,
            InventoryType type,
            String name,
            BigDecimal price,
            int quantity
    ) {
        EventSession session = eventSessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("회차를 찾을 수 없습니다: " + sessionId));
        SellableInventory inventory = SellableInventory.create(session, type, name, price, quantity);
        return inventoryRepository.save(inventory).getId();
    }

    public void updateInventory(
            Long inventoryId,
            InventoryType type,
            String name,
            BigDecimal price,
            int quantity
    ) {
        SellableInventory inventory = inventoryRepository.findById(inventoryId)
                .orElseThrow(() -> new EntityNotFoundException("재고를 찾을 수 없습니다: " + inventoryId));
        inventory.update(type, name, price, quantity);
    }
}
