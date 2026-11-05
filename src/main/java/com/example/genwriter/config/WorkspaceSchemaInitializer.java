package com.example.genwriter.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** A versioned, additive migration for installations with an existing PostgreSQL database. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "genwriter.workspace.migrate-on-startup", matchIfMissing = true)
public class WorkspaceSchemaInitializer implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    @Override
    public void afterPropertiesSet() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            if (!connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                return;
            }
        }
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(726194083)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS genwriter_schema_migration (version VARCHAR(100) PRIMARY KEY, applied_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP)");
            String version = "20261104-workspace-v1";
            if (jdbc.queryForObject("SELECT COUNT(*) FROM genwriter_schema_migration WHERE version = ?", Integer.class, version) == 0) {
                var modes = jdbc.queryForList("SELECT tgenabled::text FROM pg_trigger WHERE tgrelid='document'::regclass AND tgname='update_document_updated_at' AND NOT tgisinternal", String.class);
                String triggerMode = modes.isEmpty() ? null : modes.get(0);
                boolean restoreTimestampTrigger = "O".equals(triggerMode) || "A".equals(triggerMode);
                if (restoreTimestampTrigger) jdbc.execute("ALTER TABLE document DISABLE TRIGGER update_document_updated_at");
                new ResourceDatabasePopulator(new ClassPathResource("db/workspace.sql")).execute(jdbc.getDataSource());
                // Provenance backfill must not replace the original document modification time.
                // A migration failure rolls back both the data and this transactional trigger change.
                if (restoreTimestampTrigger) jdbc.execute("ALTER TABLE document ENABLE " + ("A".equals(triggerMode) ? "ALWAYS " : "") + "TRIGGER update_document_updated_at");
                jdbc.update("INSERT INTO genwriter_schema_migration(version) VALUES (?)", version);
            }
        });
    }
}
