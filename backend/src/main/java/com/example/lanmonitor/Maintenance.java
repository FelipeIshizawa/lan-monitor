package com.example.lanmonitor;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Maintenance {
    private final JdbcTemplate db;
    private static final Map<String,String> TABLES=Map.of(
        "latency_samples","observed_at", "device_events","occurred_at", "latency_rollups_1m","bucket_at");
    public Maintenance(JdbcTemplate db) {this.db=db;}
    @Scheduled(initialDelay=30000,fixedDelay=3600000)
    public void partitions() {
        // DDL is deliberately outside ingest transactions. One backend instance only.
        LocalDate month=LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        for(String table:TABLES.keySet()) {
            for(int i=0;i<3;i++) {
                LocalDate start=month.plusMonths(i);
                String name="p"+start.format(DateTimeFormatter.ofPattern("yyyyMM"));
                Integer count=db.queryForObject("SELECT COUNT(*) FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND PARTITION_NAME=?",Integer.class,table,name);
                if(count==0) {
                    // Existing finite upper bound must be earlier than the new boundary.
                    var bounds=db.queryForList("SELECT PARTITION_DESCRIPTION FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND PARTITION_NAME<>'p_future'",table);
                    String end=start.plusMonths(1).toString();
                    boolean safe=bounds.stream().allMatch(b -> b.get("PARTITION_DESCRIPTION").toString().replace("'","").compareTo(end)<0);
                    if(safe) db.execute("ALTER TABLE "+table+" REORGANIZE PARTITION p_future INTO (PARTITION "+name+" VALUES LESS THAN ('"+end+"'), PARTITION p_future VALUES LESS THAN (MAXVALUE))");
                }
            }
            LocalDate cutoff=table.equals("latency_samples") ? LocalDate.now(ZoneOffset.UTC).minusDays(30) : month.minusMonths(12);
            var parts=db.queryForList("SELECT PARTITION_NAME,PARTITION_DESCRIPTION FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND PARTITION_NAME<>'p_future'",table);
            for(var p:parts) {
                String name=p.get("PARTITION_NAME").toString();
                LocalDate end=LocalDate.parse(p.get("PARTITION_DESCRIPTION").toString().replace("'",""));
                if(!end.isAfter(cutoff) && name.matches("p(?:[0-9]{6}|_before_[0-9]{6})"))
                    db.execute("ALTER TABLE "+table+" DROP PARTITION "+name);
            }
        }
        db.update("DELETE FROM ingestion_batches WHERE received_at < UTC_TIMESTAMP() - INTERVAL 2 DAY LIMIT 10000");
    }
    @Scheduled(initialDelay=60000,fixedDelay=60000)
    public void rollups() {
        // Recompute a full replay window; overwrites prevent double counting retries.
        db.update("""
            INSERT INTO latency_rollups_1m
            SELECT device_id,CAST(DATE_FORMAT(observed_at,'%Y-%m-%d %H:%i:00') AS DATETIME),probe_kind,
                   COUNT(*),SUM(result_code='OK'),COALESCE(SUM(latency_us),0),MIN(latency_us),MAX(latency_us)
            FROM latency_samples WHERE observed_at >= UTC_TIMESTAMP() - INTERVAL 25 HOUR
            GROUP BY device_id,CAST(DATE_FORMAT(observed_at,'%Y-%m-%d %H:%i:00') AS DATETIME),probe_kind
            ON DUPLICATE KEY UPDATE sample_count=VALUES(sample_count),success_count=VALUES(success_count),
                   latency_sum_us=VALUES(latency_sum_us),latency_min_us=VALUES(latency_min_us),latency_max_us=VALUES(latency_max_us)
            """);
    }
}
