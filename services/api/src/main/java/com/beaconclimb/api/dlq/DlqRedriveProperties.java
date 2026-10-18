package com.beaconclimb.api.dlq;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code --redrive.*} options of the {@code redrive} profile ({@code make dlq-redrive}, {@code make
 * dlq-status}).
 *
 * @param events comma-separated DLQ {@code event_id}s to republish, or {@code ALL}
 * @param dryRun print the audit lines without publishing
 * @param topic the DLQ topic to read; absent means {@code stream-dlq.v1}, and any other configured
 *     DLQ topic name is refused unless it is named here explicitly
 * @param list print the DLQ count and the last records instead of redriving
 */
@ConfigurationProperties(prefix = "redrive")
public record DlqRedriveProperties(
    String events,
    @DefaultValue("false") boolean dryRun,
    String topic,
    @DefaultValue("false") boolean list) {}
