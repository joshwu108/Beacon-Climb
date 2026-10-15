package com.beaconclimb.api.catalog.importer;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code --import.*} options of the {@code import} profile.
 *
 * @param file the catalog v1 JSON Lines file (required)
 * @param acknowledgeAudit operator acknowledgement required for {@code synthetic=false} files
 * @param reportDir where rejected-record reports are written
 */
@ConfigurationProperties(prefix = "import")
public record ImportProperties(
    Path file,
    @DefaultValue("false") boolean acknowledgeAudit,
    @DefaultValue("reports/import") Path reportDir) {}
