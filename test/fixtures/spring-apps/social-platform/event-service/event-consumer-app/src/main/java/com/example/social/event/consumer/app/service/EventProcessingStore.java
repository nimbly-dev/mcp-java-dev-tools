package com.example.social.event.consumer.app.service;

import com.example.social.event.consumer.app.model.EventProcessingStatusResponse;
import jakarta.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class EventProcessingStore {
  private final Map<String, EventProcessingStatusResponse> statusByEventId =
      new ConcurrentHashMap<>();
  @Nullable private final String sqliteJdbcUrl;

  public EventProcessingStore(@Value("${fixture.sqlite.file:}") String sqliteFile) {
    if (sqliteFile == null || sqliteFile.isBlank()) {
      this.sqliteJdbcUrl = null;
      return;
    }
    Path sqlitePath = Path.of(sqliteFile).toAbsolutePath();
    this.sqliteJdbcUrl = "jdbc:sqlite:" + sqlitePath;
    initializeSqlite(sqlitePath);
  }

  public void markAccepted(
      String eventId, String tenant, String eventType, String acceptedBy, int indexedCount) {
    EventProcessingStatusResponse status =
        new EventProcessingStatusResponse(
            eventId, "accepted", acceptedBy, tenant, eventType, indexedCount);
    statusByEventId.put(eventId, status);
    persist(status);
  }

  public void markProcessed(
      String eventId, String tenant, String eventType, String acceptedBy, int indexedCount) {
    EventProcessingStatusResponse status =
        new EventProcessingStatusResponse(
            eventId, "processed", acceptedBy, tenant, eventType, indexedCount);
    statusByEventId.put(eventId, status);
    persist(status);
  }

  public EventProcessingStatusResponse require(String eventId) {
    EventProcessingStatusResponse status = statusByEventId.get(eventId);
    if (status == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId);
    }
    return status;
  }

  @Nullable
  public String status(String eventId) {
    EventProcessingStatusResponse status = statusByEventId.get(eventId);
    return status == null ? null : status.status();
  }

  private void initializeSqlite(Path sqlitePath) {
    try {
      Path parent = sqlitePath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (Connection connection = DriverManager.getConnection(sqliteJdbcUrl)) {
        connection.createStatement()
            .execute(
                """
                CREATE TABLE IF NOT EXISTS event_processing_audit (
                  event_id TEXT PRIMARY KEY,
                  status TEXT NOT NULL,
                  accepted_by TEXT NOT NULL,
                  tenant TEXT NOT NULL,
                  event_type TEXT NOT NULL,
                  indexed_count INTEGER NOT NULL,
                  updated_at_epoch_ms INTEGER NOT NULL
                )
                """);
      }
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Failed to initialize fixture SQLite store at " + sqlitePath, exception);
    }
  }

  private void persist(EventProcessingStatusResponse status) {
    if (sqliteJdbcUrl == null) {
      return;
    }
    try (Connection connection = DriverManager.getConnection(sqliteJdbcUrl);
        PreparedStatement statement =
            connection.prepareStatement(
                """
                INSERT INTO event_processing_audit (
                  event_id,
                  status,
                  accepted_by,
                  tenant,
                  event_type,
                  indexed_count,
                  updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(event_id) DO UPDATE SET
                  status = excluded.status,
                  accepted_by = excluded.accepted_by,
                  tenant = excluded.tenant,
                  event_type = excluded.event_type,
                  indexed_count = excluded.indexed_count,
                  updated_at_epoch_ms = excluded.updated_at_epoch_ms
                """)) {
      statement.setString(1, status.eventId());
      statement.setString(2, status.status());
      statement.setString(3, status.acceptedBy());
      statement.setString(4, status.tenant());
      statement.setString(5, status.eventType());
      statement.setInt(6, status.indexedCount());
      statement.setLong(7, System.currentTimeMillis());
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Failed to persist fixture event processing state for " + status.eventId(), exception);
    }
  }
}
