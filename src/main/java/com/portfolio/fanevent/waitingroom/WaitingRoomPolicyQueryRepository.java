package com.portfolio.fanevent.waitingroom;

import static com.portfolio.fanevent.catalog.domain.QEvent.event;
import static com.portfolio.fanevent.waitingroom.QWaitingRoomPolicy.waitingRoomPolicy;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class WaitingRoomPolicyQueryRepository {

    private final JPAQueryFactory queryFactory;

    public WaitingRoomPolicyQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public List<WaitingRoomPolicyView> findAll() {
        return queryFactory.select(Projections.constructor(
                        WaitingRoomPolicyView.class,
                        event.id,
                        event.title,
                        waitingRoomPolicy.enabled,
                        waitingRoomPolicy.batchSize,
                        waitingRoomPolicy.activeCapacity,
                        waitingRoomPolicy.admissionTtlSeconds))
                .from(waitingRoomPolicy)
                .join(waitingRoomPolicy.event, event)
                .orderBy(waitingRoomPolicy.enabled.desc(), event.id.asc())
                .fetch();
    }
}
