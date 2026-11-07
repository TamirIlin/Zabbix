package com.example.Zabbix.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "zabbix_settings")
@Getter
@Setter
@NoArgsConstructor
public class ZabbixSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zabbix_url", nullable = false, length = 1000)
    private String zabbixUrl;

    @Column(name = "api_token", nullable = false, length = 4000)
    private String apiToken;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ZabbixSettings(String zabbixUrl, String apiToken) {
        this.zabbixUrl = zabbixUrl;
        this.apiToken = apiToken;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
