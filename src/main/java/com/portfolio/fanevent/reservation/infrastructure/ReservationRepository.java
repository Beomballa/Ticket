package com.portfolio.fanevent.reservation.infrastructure;

import com.portfolio.fanevent.reservation.domain.Reservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @Query(value = """
            SELECT reservation.id
            FROM reservations reservation
            WHERE reservation.status = 'PENDING'
              AND reservation.expires_at <= :now
            ORDER BY reservation.expires_at, reservation.id
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findExpiredIdsForUpdate(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    @Query("""
            select distinct reservation
            from Reservation reservation
            left join fetch reservation.items
            where reservation.id in :reservationIds
            """)
    List<Reservation> findAllWithItemsByIdIn(
            @Param("reservationIds") List<Long> reservationIds
    );

    @Query("""
            select distinct reservation
            from Reservation reservation
            left join fetch reservation.items item
            left join fetch item.inventory
            where reservation.id = :reservationId
              and reservation.member.id = :memberId
            """)
    Optional<Reservation> findOwnedWithItems(
            @Param("reservationId") Long reservationId,
            @Param("memberId") Long memberId
    );
}
