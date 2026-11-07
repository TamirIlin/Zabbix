package com.example.Zabbix.repository;

import com.example.Zabbix.entity.ZabbixSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ZabbixSettingsRepository extends JpaRepository<ZabbixSettings, Long> {

    Optional<ZabbixSettings> findFirstByOrderByIdAsc();
}
