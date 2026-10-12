package com.example.lanmonitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class HubController {
    public record Sample(String mac, String ip, String hostname, Instant observedAt,
                         String probeKind, String resultCode, Integer latencyUs, Boolean reachable) {}
    public record Batch(String agentId, String segment, UUID batchId, List<Sample> samples) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final String token;
    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
    public HubController(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json,
                         @Value("${monitor.token}") String token) {
        this.db=db; this.tx=tx; this.json=json; this.token=token;
        if (token.length()<24) throw new IllegalArgumentException("AGENT_TOKEN needs 24+ characters");
    }
    private void authorize(HttpServletRequest request) {
        String supplied=Objects.toString(request.getHeader("Authorization"), "");
        if (!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    private void invalid(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    @PostMapping("/telemetry/batches")
    public synchronized Map<String,Object> ingest(@RequestBody Batch batch, HttpServletRequest request) throws Exception {
        authorize(request);
        if (batch.agentId()==null || !batch.agentId().matches("[a-zA-Z0-9_-]{1,100}") ||
            batch.segment()==null || !batch.segment().matches("[a-zA-Z0-9_-]{1,100}") ||
            batch.batchId()==null || batch.samples()==null || batch.samples().size()>1000) invalid("Invalid batch");
        Instant now=Instant.now();
        for (Sample s:batch.samples()) {
            if(s==null || s.mac()==null || !s.mac().matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") ||
                s.ip()==null || !s.ip().matches("[0-9a-fA-F:.]{3,45}") || s.observedAt()==null ||
                s.observedAt().isAfter(now.plusSeconds(60)) || s.observedAt().isBefore(now.minusSeconds(86400)) ||
                !Set.of("ICMP","DNS").contains(Objects.toString(s.probeKind(),"")) ||
                !Set.of("OK","TIMEOUT","ERROR").contains(Objects.toString(s.resultCode(),"")) ||
                (s.resultCode().equals("OK") ? s.latencyUs()==null || s.latencyUs()<0 : s.latencyUs()!=null) ||
                (s.hostname()!=null && s.hostname().length()>253) ||
                ("ICMP".equals(s.probeKind()) && s.reachable()==null)) invalid("Invalid sample");
            if(db.queryForObject("SELECT INET6_ATON(?) IS NOT NULL",Boolean.class,s.ip())!=Boolean.TRUE) invalid("Invalid IP");
        }
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(batch)));
        boolean inserted=Boolean.TRUE.equals(tx.execute(status -> {
            var prior=db.queryForList("SELECT payload_sha256 FROM ingestion_batches WHERE agent_id=? AND batch_id=?",batch.agentId(),batch.batchId().toString());
            if(!prior.isEmpty()) {
                if(!hash.equals(prior.getFirst().get("payload_sha256"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Batch ID reused");
                return false;
            }
            var owner=db.queryForList("SELECT agent_id FROM agents WHERE segment=?",batch.segment());
            if(!owner.isEmpty() && !batch.agentId().equals(owner.getFirst().get("agent_id")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Segment already owned");
            var agent=db.queryForList("SELECT segment FROM agents WHERE agent_id=?",batch.agentId());
            if(!agent.isEmpty() && !batch.segment().equals(agent.getFirst().get("segment")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Agent segment changed");
            db.update("INSERT INTO agents VALUES(?,?,?) ON DUPLICATE KEY UPDATE last_heartbeat_at=VALUES(last_heartbeat_at)",batch.agentId(),batch.segment(),Timestamp.from(now));
            db.update("INSERT INTO ingestion_batches VALUES(?,?,?,?)",batch.agentId(),batch.batchId().toString(),hash,Timestamp.from(now));
            List<Object[]> rows=new ArrayList<>(); int index=0;
            for(Sample s:batch.samples()) {
                Timestamp time=Timestamp.from(s.observedAt()); String mac=s.mac().replace(":","");
                db.update("INSERT IGNORE INTO devices(segment,mac,current_ip,first_seen_at,last_seen_at,observed_at) VALUES(?,UNHEX(?),INET6_ATON(?),?,?,?)",batch.segment(),mac,s.ip(),time,time,time);
                var device=db.queryForMap("SELECT * FROM devices WHERE segment=? AND mac=UNHEX(?) FOR UPDATE",batch.segment(),mac);
                long id=((Number)device.get("device_id")).longValue();
                rows.add(new Object[]{id,time,batch.batchId().toString(),index++,s.ip(),s.probeKind(),s.resultCode(),s.latencyUs()});
                Timestamp previous=db.queryForObject("SELECT observed_at FROM devices WHERE device_id=?",Timestamp.class,id);
                if(s.probeKind().equals("ICMP") && !time.before(previous)) {
                    String old=(String)device.get("status");
                    int failures=Boolean.TRUE.equals(s.reachable()) ? 0 : ((Number)device.get("failures")).intValue()+1;
                    String next=Boolean.TRUE.equals(s.reachable()) ? "UP" : failures>=3 ? "DOWN" : old;
                    long version=((Number)device.get("state_version")).longValue()+(old.equals(next)?0:1);
                    db.update("UPDATE devices SET current_ip=INET6_ATON(?),hostname=COALESCE(?,hostname),status=?,failures=?,observed_at=?,last_seen_at=IF(?, ?,last_seen_at),state_version=? WHERE device_id=?",s.ip(),s.hostname(),next,failures,time,Boolean.TRUE.equals(s.reachable()),time,version,id);
                    if(!old.equals(next)) db.update("INSERT INTO device_events VALUES(?,?,?,?,?,?)",id,time,version,old,next,"PROBE_POLICY");
                }
            }
            db.batchUpdate("INSERT INTO latency_samples VALUES(?,?,?,?,INET6_ATON(?),?,?,?)",rows);
            return true;
        }));
        if(inserted) publish();
        return Map.of("accepted",true,"duplicate",!inserted);
    }
    private Map<String,Object> snapshot() {
        return Map.of("devices",db.queryForList("SELECT device_id,segment,LOWER(CONCAT_WS(':',SUBSTR(HEX(mac),1,2),SUBSTR(HEX(mac),3,2),SUBSTR(HEX(mac),5,2),SUBSTR(HEX(mac),7,2),SUBSTR(HEX(mac),9,2),SUBSTR(HEX(mac),11,2))) mac,INET6_NTOA(current_ip) ip,hostname,status,last_seen_at,state_version FROM devices ORDER BY device_id"),
            "events",db.queryForList("SELECT * FROM device_events ORDER BY occurred_at DESC LIMIT 50"),
            "agents",db.queryForList("SELECT *,last_heartbeat_at < UTC_TIMESTAMP() - INTERVAL 30 SECOND stale FROM agents"));
    }
    @GetMapping("/snapshot")
    public synchronized Map<String,Object> getSnapshot(HttpServletRequest request) { authorize(request); return snapshot(); }
    @GetMapping("/devices/{id}/latency")
    public List<Map<String,Object>> latency(@PathVariable long id,HttpServletRequest request) {
        authorize(request);
        return db.queryForList("SELECT observed_at,probe_kind,result_code,latency_us FROM latency_samples WHERE device_id=? AND observed_at >= UTC_TIMESTAMP() - INTERVAL 1 HOUR ORDER BY observed_at DESC LIMIT 500",id);
    }
    @GetMapping(value="/stream",produces="text/event-stream")
    public synchronized SseEmitter stream(HttpServletRequest request) throws IOException {
        authorize(request);
        if(clients.size()>=100) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        SseEmitter emitter=new SseEmitter(600000L);
        clients.add(emitter);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> {clients.remove(emitter);emitter.complete();});
        emitter.onError(e -> clients.remove(emitter));
        emitter.send(SseEmitter.event().name("snapshot").data(snapshot()));
        return emitter;
    }
    private void publish() {
        if(clients.isEmpty()) return;
        var data=snapshot();
        for(SseEmitter e:clients) try {e.send(SseEmitter.event().name("snapshot").data(data));}
        catch(Exception error) {clients.remove(e);e.complete();}
    }
    @Scheduled(fixedDelay=10000)
    public synchronized void heartbeat() {
        tx.executeWithoutResult(status -> {
            var stale=db.queryForList("SELECT d.* FROM devices d JOIN agents a ON a.segment=d.segment WHERE a.last_heartbeat_at < UTC_TIMESTAMP() - INTERVAL 30 SECOND AND d.status<>'UNKNOWN' FOR UPDATE");
            for(var d:stale) {
                long version=((Number)d.get("state_version")).longValue()+1;
                db.update("UPDATE devices SET status='UNKNOWN',failures=0,state_version=? WHERE device_id=?",version,d.get("device_id"));
                db.update("INSERT INTO device_events VALUES(?,?,?,?,?,?)",d.get("device_id"),Timestamp.from(Instant.now()),version,d.get("status"),"UNKNOWN","AGENT_STALE");
            }
        });
        publish();
    }
}
