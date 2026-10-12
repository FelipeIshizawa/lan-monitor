import React, {useEffect, useState} from 'react';
import {createRoot} from 'react-dom/client';
import './styles.css';

function App() {
  const [token,setToken]=useState('');
  const [active,setActive]=useState('');
  const [data,setData]=useState({devices:[],events:[],agents:[]});
  const [connection,setConnection]=useState('Desconectado');
  const [selected,setSelected]=useState(null);
  const [latency,setLatency]=useState([]);
  useEffect(()=>{
    if(!active)return;
    const controller=new AbortController();
    let timer;
    async function stream(){
      try {
        setConnection('Conectando');
        const response=await fetch('/api/v1/stream',{headers:{Authorization:`Bearer ${active}`},signal:controller.signal});
        if(!response.ok)throw Error(`HTTP ${response.status}`);
        setConnection('Ao vivo');
        const reader=response.body.getReader(); const decoder=new TextDecoder(); let buffer='';
        while(true){
          const {done,value}=await reader.read(); if(done)break;
          buffer+=decoder.decode(value,{stream:true}).replace(/\r\n/g,'\n');
          let end;
          while((end=buffer.indexOf('\n\n'))>=0){
            const event=buffer.slice(0,end);buffer=buffer.slice(end+2);
            const payload=event.split('\n').filter(x=>x.startsWith('data:')).map(x=>x.slice(5)).join('\n');
            if(payload)setData(JSON.parse(payload));
          }
        }
      }catch(e){if(controller.signal.aborted)return;setConnection(e.message);}
      if(!controller.signal.aborted)timer=setTimeout(stream,3000);
    }
    stream();return()=>{controller.abort();clearTimeout(timer);};
  },[active]);
  useEffect(()=>{
    if(!selected||!active)return;
    const controller=new AbortController();
    fetch(`/api/v1/devices/${selected}/latency`,{headers:{Authorization:`Bearer ${active}`},signal:controller.signal})
      .then(r=>{if(!r.ok)throw Error('Falha ao carregar histórico');return r.json();}).then(setLatency).catch(()=>{});
    return()=>controller.abort();
  },[selected,active,data]);
  const points=latency.filter(x=>x.probe_kind==='ICMP'&&x.latency_us!==null).slice(0,80).reverse();
  const max=Math.max(1000,...points.map(x=>x.latency_us));
  return <main><header><div><small>NETWORK OPERATIONS</small><h1>LAN Monitor<span>●</span></h1><p>Visibilidade da sua rede, em tempo real.</p></div><label className="connection">{connection}</label></header>
    {!active?<form onSubmit={e=>{e.preventDefault();setActive(token);}}><h2>Conectar ao hub</h2><input type="password" required minLength={24} placeholder="Token de acesso" value={token} onChange={e=>setToken(e.target.value)}/><button>Conectar</button><p>Use o AGENT_TOKEN configurado no servidor.</p></form>:<>
    <section className="stats">{[['Dispositivos',data.devices.length],['Online',data.devices.filter(x=>x.status==='UP').length],['Offline',data.devices.filter(x=>x.status==='DOWN').length],['Agentes',data.agents.length]].map(([label,value])=><article key={label}><small>{label}</small><strong>{value}</strong></article>)}</section>
    <section className="panel"><h2>Dispositivos</h2><div className="table"><table><thead><tr>{['Nome','IP','MAC','Segmento','Estado'].map(x=><th key={x}>{x}</th>)}</tr></thead><tbody>{data.devices.map(d=><tr key={d.device_id} onClick={()=>setSelected(d.device_id)} tabIndex={0} onKeyDown={e=>{if(e.key==='Enter')setSelected(d.device_id);}}><td>{d.hostname||'Sem hostname'}</td><td>{d.ip}</td><td>{d.mac}</td><td>{d.segment}</td><td><span className={`badge ${d.status}`}>{d.status}</span></td></tr>)}</tbody></table></div>{!data.devices.length&&<p>Aguardando telemetria do agente.</p>}</section>
    <section className="grid"><article className="panel"><h2>Latência ICMP {selected?`· #${selected}`:''}</h2>{selected?<><svg viewBox="0 0 600 160" role="img" aria-label="Histórico de latência"><polyline fill="none" stroke="#4ae3bd" strokeWidth="3" points={points.map((p,i)=>`${i*600/Math.max(1,points.length-1)},${150-p.latency_us/max*130}`).join(' ')}/></svg><small>Última hora · escala máxima {(max/1000).toFixed(1)} ms · {points.length} respostas</small></>:<p>Selecione um dispositivo para visualizar o histórico.</p>}</article><article className="panel"><h2>Eventos recentes</h2><div className="events">{data.events.map((e,i)=><p key={i}><b>#{e.device_id}</b> {e.previous_status} → {e.new_status}<small>{new Date(typeof e.occurred_at === 'number' ? e.occurred_at : /(?:Z|[+-]\d{2}:\d{2})$/.test(e.occurred_at) ? e.occurred_at : e.occurred_at+'Z').toLocaleString()} · {e.reason}</small></p>)}</div></article></section>
    <footer>{data.agents.map(a=><span key={a.agent_id}>{a.agent_id} · {a.stale?'Sem heartbeat':'Ativo'}</span>)}<button onClick={()=>{setActive('');setToken('');}}>Desconectar</button></footer></>}
  </main>;
}
createRoot(document.getElementById('root')).render(<App/>);
