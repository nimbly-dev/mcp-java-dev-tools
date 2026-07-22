package com.example.social.event.consumer.app.service;

import com.example.social.event.consumer.app.model.KclFixtureBatchRequest;
import com.example.social.event.consumer.app.model.KclFixtureRecordRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
import software.amazon.kinesis.retrieval.KinesisClientRecord;

@Service
public class KclInProcessFixtureService {
  private final KclFixtureShardRecordProcessor processor;
  private final EventProcessingStore processingStore;

  public KclInProcessFixtureService(
      KclFixtureShardRecordProcessor processor, EventProcessingStore processingStore) {
    this.processor = processor;
    this.processingStore = processingStore;
  }

  public void publish(KclFixtureBatchRequest request) {
    List<KinesisClientRecord> records = new ArrayList<>(request.records().size());
    for (int index = 0; index < request.records().size(); index++) {
      KclFixtureRecordRequest fixtureRecord = request.records().get(index);
      records.add(
          KinesisClientRecord.builder()
              .sequenceNumber("fixture-sequence-" + index)
              .partitionKey(fixtureRecord.partitionKey())
              .data(
                  ByteBuffer.wrap(
                      fixtureRecord.eventId().getBytes(StandardCharsets.UTF_8)))
              .build());
    }
    try {
      processor.processRecords(ProcessRecordsInput.builder().records(records).build());
    } catch (RuntimeException exception) {
      processingStore.markProcessed(
          "kcl-after-failure", "fixture", "KCL", "kcl-fixture", records.size());
      throw exception;
    }
  }
}
