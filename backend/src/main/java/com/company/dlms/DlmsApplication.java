package com.company.dlms;

import org.springframework.ai.autoconfigure.vectorstore.pgvector.PgVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;

/**
 * PgVectorStoreAutoConfiguration excluded: two named PgVectorStore beans declared manually
 * in PgVectorStoreConfig (dlmsVectorStore + confluenceVectorStore) — Spring AI M6 supports
 * only one auto-configured bean.
 * DataSource auto-configs excluded: DataSource is created manually in PgVectorStoreConfig
 * alongside the JDBC driver (org.postgresql:postgresql runtime) added in T010.
 */
@SpringBootApplication(exclude = {
        PgVectorStoreAutoConfiguration.class,
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class
})
public class DlmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmsApplication.class, args);
    }
}
