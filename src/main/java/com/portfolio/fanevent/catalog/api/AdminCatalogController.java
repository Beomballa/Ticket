package com.portfolio.fanevent.catalog.api;

import com.portfolio.fanevent.catalog.application.CatalogCommandService;
import com.portfolio.fanevent.catalog.domain.EventStatus;
import com.portfolio.fanevent.catalog.domain.EventType;
import com.portfolio.fanevent.catalog.domain.InventoryType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminCatalogController {

    private final CatalogCommandService catalogCommandService;

    public AdminCatalogController(CatalogCommandService catalogCommandService) {
        this.catalogCommandService = catalogCommandService;
    }

    @PostMapping("/artists")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse createArtist(@Valid @RequestBody CreateArtistRequest request) {
        return new IdResponse(catalogCommandService.createArtist(request.name(), request.description()));
    }

    @PutMapping("/artists/{artistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateArtist(
            @PathVariable Long artistId,
            @Valid @RequestBody UpdateArtistRequest request
    ) {
        catalogCommandService.updateArtist(artistId, request.name(), request.description());
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse createEvent(@Valid @RequestBody CreateEventRequest request) {
        return new IdResponse(catalogCommandService.createEvent(
                request.artistId(),
                request.title(),
                request.description(),
                request.type(),
                request.salesStartAt(),
                request.salesEndAt()));
    }

    @PutMapping("/events/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateEvent(
            @PathVariable Long eventId,
            @Valid @RequestBody UpdateEventRequest request
    ) {
        catalogCommandService.updateEvent(
                eventId,
                request.title(),
                request.description(),
                request.type(),
                request.salesStartAt(),
                request.salesEndAt());
    }

    @PatchMapping("/events/{eventId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeEventStatus(
            @PathVariable Long eventId,
            @Valid @RequestBody ChangeEventStatusRequest request
    ) {
        catalogCommandService.changeEventStatus(eventId, request.status());
    }

    @PostMapping("/event-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse createSession(@Valid @RequestBody CreateEventSessionRequest request) {
        return new IdResponse(catalogCommandService.createSession(
                request.eventId(),
                request.name(),
                request.venue(),
                request.startsAt(),
                request.salesStartAt(),
                request.salesEndAt()));
    }

    @PutMapping("/event-sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateSession(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateEventSessionRequest request
    ) {
        catalogCommandService.updateSession(
                sessionId,
                request.name(),
                request.venue(),
                request.startsAt(),
                request.salesStartAt(),
                request.salesEndAt());
    }

    @PostMapping("/inventory")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse createInventory(@Valid @RequestBody CreateInventoryRequest request) {
        return new IdResponse(catalogCommandService.createInventory(
                request.eventSessionId(),
                request.type(),
                request.name(),
                request.price(),
                request.quantity()));
    }

    @PutMapping("/inventory/{inventoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateInventory(
            @PathVariable Long inventoryId,
            @Valid @RequestBody UpdateInventoryRequest request
    ) {
        catalogCommandService.updateInventory(
                inventoryId,
                request.type(),
                request.name(),
                request.price(),
                request.quantity());
    }

    public record IdResponse(Long id) {
    }

    public record CreateArtistRequest(
            @NotBlank String name,
            String description
    ) {
    }

    public record UpdateArtistRequest(
            @NotBlank String name,
            String description
    ) {
    }

    public record CreateEventRequest(
            @NotNull Long artistId,
            @NotBlank String title,
            String description,
            @NotNull EventType type,
            @NotNull Instant salesStartAt,
            @NotNull Instant salesEndAt
    ) {
    }

    public record UpdateEventRequest(
            @NotBlank String title,
            String description,
            @NotNull EventType type,
            @NotNull Instant salesStartAt,
            @NotNull Instant salesEndAt
    ) {
    }

    public record ChangeEventStatusRequest(@NotNull EventStatus status) {
    }

    public record CreateEventSessionRequest(
            @NotNull Long eventId,
            @NotBlank String name,
            String venue,
            @NotNull Instant startsAt,
            @NotNull Instant salesStartAt,
            @NotNull Instant salesEndAt
    ) {
    }

    public record UpdateEventSessionRequest(
            @NotBlank String name,
            String venue,
            @NotNull Instant startsAt,
            @NotNull Instant salesStartAt,
            @NotNull Instant salesEndAt
    ) {
    }

    public record CreateInventoryRequest(
            @NotNull Long eventSessionId,
            @NotNull InventoryType type,
            @NotBlank String name,
            @NotNull @DecimalMin("0.00") BigDecimal price,
            @Min(0) int quantity
    ) {
    }

    public record UpdateInventoryRequest(
            @NotNull InventoryType type,
            @NotBlank String name,
            @NotNull @DecimalMin("0.00") BigDecimal price,
            @Min(0) int quantity
    ) {
    }
}
