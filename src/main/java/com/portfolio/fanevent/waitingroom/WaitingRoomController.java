package com.portfolio.fanevent.waitingroom;

import com.portfolio.fanevent.support.api.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Waiting room", description = "이벤트별 공정 대기열과 입장 허용")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
public class WaitingRoomController {

    private final WaitingRoomService service;

    public WaitingRoomController(WaitingRoomService service) {
        this.service = service;
    }

    @PostMapping("/api/events/{eventId}/waiting-room")
    WaitingRoomEntry join(@AuthenticationPrincipal Jwt jwt, @PathVariable Long eventId) {
        return service.join(eventId, Long.valueOf(jwt.getSubject()));
    }

    @GetMapping("/api/events/{eventId}/waiting-room")
    WaitingRoomEntry status(@AuthenticationPrincipal Jwt jwt, @PathVariable Long eventId) {
        return service.status(eventId, Long.valueOf(jwt.getSubject()));
    }

    @GetMapping("/api/admin/waiting-rooms")
    List<WaitingRoomSummary> summaries() {
        return service.summaries();
    }

    @PostMapping("/api/admin/events/{eventId}/waiting-room")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void open(@PathVariable Long eventId) {
        service.open(eventId);
    }

    @PostMapping("/api/admin/events/{eventId}/waiting-room/admit")
    AdmitResult admit(@PathVariable Long eventId) {
        return new AdmitResult(eventId, service.admit(eventId));
    }

    @DeleteMapping("/api/admin/events/{eventId}/waiting-room")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(@PathVariable Long eventId) {
        service.close(eventId);
    }

    public record AdmitResult(Long eventId, int admittedCount) {}
}
