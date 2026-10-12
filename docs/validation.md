# Validação local — 12/10/2026

- Docker Desktop 4.94.0 / WSL 2.7.13; Docker Engine 29.8.2.
- Build backend Java 21/Spring Boot: aprovado.
- Build React/Vite: aprovado.
- Flyway: V1 e V2 aplicadas em MySQL 8.4.
- Três testes unitários do agente: aprovados.
- Smoke real: ingestão, deduplicação, conflito de UUID, UP/DOWN, histórico, token inválido e snapshot SSE aprovados.
- Interface: conexão Ao vivo, dispositivos simulados, gráfico ICMP e timeline verificados.
- Máquina de estados: agente de smoke sem heartbeat passou para UNKNOWN com AGENT_STALE.
- Serviços deixados em execução, dashboard em http://localhost:3000.

Limites: modo de coleta sintético; ARP/ICMP/DNS da LAN real, carga sustentada, clientes lentos e backup/restore ainda não validados.
