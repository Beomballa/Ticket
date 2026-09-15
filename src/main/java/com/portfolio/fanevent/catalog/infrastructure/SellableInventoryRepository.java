package com.portfolio.fanevent.catalog.infrastructure;

import com.portfolio.fanevent.catalog.domain.SellableInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface SellableInventoryRepository extends JpaRepository<SellableInventory, Long> {

    @Query("""
            select distinct event.id
            from SellableInventory inventory
            join inventory.eventSession session
            join session.event event
            where inventory.id in :inventoryIds
            """)
    List<Long> findDistinctEventIds(@Param("inventoryIds") Collection<Long> inventoryIds);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE sellable_inventory
            SET available_quantity = available_quantity - :quantity,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :inventoryId
              AND available_quantity >= :quantity
            """, nativeQuery = true)
    int decreaseAvailableQuantity(
            @Param("inventoryId") Long inventoryId,
            @Param("quantity") int quantity
    );

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE sellable_inventory
            SET available_quantity = available_quantity + :quantity,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :inventoryId
              AND available_quantity + :quantity <= total_quantity
            """, nativeQuery = true)
    int increaseAvailableQuantity(
            @Param("inventoryId") Long inventoryId,
            @Param("quantity") int quantity
    );
}
