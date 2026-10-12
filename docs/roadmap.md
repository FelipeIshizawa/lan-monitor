# Roadmap: cinco sprints

| Sprint | Entrega | Aceite |
|---|---|---|
| 1 | Scapy, ARP, ICMP, DNS, interface/CIDR | Laboratório Linux confirma descoberta e timeout; registrar limites por VLAN |
| 2 | Spring, JDBC batch, migrations, SQLite spool | Desligar backend e recuperar lotes sem duplicação ou escrita parcial |
| 3 | React, SSE, histórico, máquina de estados | Reconectar; UP/DOWN/UNKNOWN; ICMP bloqueado com ARP ativo |
| 4 | Partições, rollups e benchmarks | Quantificar throughput, p95/p99, RAM, disco e custo de agregação; filas por cliente SSE |
| 5 | Compose, CI/CD e operação | Deploy em host Linux limpo; backup/restore; rollback; documentação reproduzível |

Este repositório implementa a base executável dessas entregas. Aceites de rede real, escala, recuperação de backup e deploy dependem de laboratório e ainda precisam ser executados.
