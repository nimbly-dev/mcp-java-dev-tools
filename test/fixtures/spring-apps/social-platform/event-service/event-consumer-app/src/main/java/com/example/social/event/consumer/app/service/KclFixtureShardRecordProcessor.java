package com.example.social.event.consumer.app.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import software.amazon.kinesis.lifecycle.events.InitializationInput;
import software.amazon.kinesis.lifecycle.events.LeaseLostInput;
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
import software.amazon.kinesis.lifecycle.events.ShardEndedInput;
import software.amazon.kinesis.lifecycle.events.ShutdownRequestedInput;
import software.amazon.kinesis.processor.ShardRecordProcessor;
import software.amazon.kinesis.retrieval.KinesisClientRecord;

@Component
public class KclFixtureShardRecordProcessor implements ShardRecordProcessor {
  private static final String FAILURE_EVENT_ID = "kcl-event-fail";
  private final EventProcessingStore processingStore;

  public KclFixtureShardRecordProcessor(EventProcessingStore processingStore) {
    this.processingStore = processingStore;
  }

  @Override
  public void initialize(InitializationInput initializationInput) {}

  @Override
  public void processRecords(ProcessRecordsInput processRecordsInput) {
    if (processRecordsInput.records().stream().anyMatch(this::isFailureRecord)) {
      throw new IllegalStateException("fixture processRecords failure");
    }
    for (KinesisClientRecord record : processRecordsInput.records()) {
      String eventId = decodeEventId(record.data());
      processingStore.markProcessed(
          eventId,
          record.partitionKey(),
          "KCL",
          "kcl-fixture",
          processRecordsInput.records().size());
    }
  }

  @Override
  public void leaseLost(LeaseLostInput leaseLostInput) {}

  @Override
  public void shardEnded(ShardEndedInput shardEndedInput) {}

  @Override
  public void shutdownRequested(ShutdownRequestedInput shutdownRequestedInput) {}

  private String decodeEventId(ByteBuffer data) {
    ByteBuffer copy = data.asReadOnlyBuffer();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private boolean isFailureRecord(KinesisClientRecord record) {
    return FAILURE_EVENT_ID.equals(decodeEventId(record.data()));
  }
}
