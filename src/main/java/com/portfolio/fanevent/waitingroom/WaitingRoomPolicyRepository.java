package com.portfolio.fanevent.waitingroom;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitingRoomPolicyRepository extends JpaRepository<WaitingRoomPolicy, Long> {
    @Query("select policy from WaitingRoomPolicy policy where policy.event.id = :eventId")
    Optional<WaitingRoomPolicy> findByEventId(@Param("eventId") Long eventId);

    List<WaitingRoomPolicy> findAllByEnabledTrueOrderByIdAsc();
}
