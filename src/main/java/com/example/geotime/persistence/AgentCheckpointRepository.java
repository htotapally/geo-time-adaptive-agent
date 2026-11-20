package com.example.geotime.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentCheckpointRepository extends JpaRepository<AgentCheckpoint, String> {
}
