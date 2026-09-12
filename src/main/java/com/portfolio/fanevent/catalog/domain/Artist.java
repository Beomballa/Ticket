package com.portfolio.fanevent.catalog.domain;

import com.portfolio.fanevent.support.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "artists")
public class Artist extends BaseEntity {

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    protected Artist() {
    }

    private Artist(String name, String description) {
        this.name = requireText(name, "아티스트 이름");
        this.description = description;
    }

    public static Artist create(String name, String description) {
        return new Artist(name, description);
    }

    public void update(String name, String description) {
        this.name = requireText(name, "아티스트 이름");
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
        }
        return value.trim();
    }
}
