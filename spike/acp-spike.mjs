import { spawn } from 'node:child_process';
const proc = spawn('/opt/node24/bin/dsh', ['--profile', 'acp'], { env: { ...process.env, PATH: '/opt/node24/bin:' + process.env.PATH }, stdio: ['pipe','pipe','pipe'] });
let buf=''; const pending=new Map(); let nextId=1; const log=(...a)=>console.log(...a);
proc.stdout.on('data',(d)=>{buf+=d.toString();let i;while((i=buf.indexOf('\n'))>=0){const line=buf.slice(0,i).trim();buf=buf.slice(i+1);if(!line)continue;let m;try{m=JSON.parse(line);}catch{continue;}if(m.id!==undefined&&pending.has(m.id)){pending.get(m.id)(m);pending.delete(m.id);}else if(m.method){log('NOTIFY:',m.method,JSON.stringify(m.params).slice(0,150));if(m.id!==undefined)proc.stdin.write(JSON.stringify({jsonrpc:'2.0',id:m.id,result:{}})+'\n');}}});
proc.stderr.on('data',(d)=>{const s=d.toString().trim();if(s&&!s.includes('selftest'))log('STDERR:',s.slice(0,150));});
function rpc(method,params,t=15000){return new Promise((res)=>{const id=nextId++;const tm=setTimeout(()=>{pending.delete(id);res({timeout:true,method});},t);pending.set(id,(m)=>{clearTimeout(tm);res(m);});proc.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');});}
function notify(method,params){proc.stdin.write(JSON.stringify({jsonrpc:'2.0',method,params})+'\n');}
const sleep=(ms)=>new Promise(r=>setTimeout(r,ms));
(async()=>{
  const init=await rpc('initialize',{protocolVersion:1,clientCapabilities:{}});
  log('CAPS:',JSON.stringify(init.result?.agentCapabilities));
  const nw=await rpc('session/new',{cwd:'/root',mcpServers:[]});
  const sid=nw.result?.sessionId;
  log('MODEL_OPTIONS:',JSON.stringify(nw.result?.configOptions?.find(o=>o.id==='model')));
  log('=== set_config_option 正确值 ===');
  const cfg=await rpc('session/set_config_option',{sessionId:sid,configId:'model',value:'["deepseek-official","deepseek-v4-flash"]'});
  log('SETCFG:',JSON.stringify(cfg).slice(0,200));
  log('=== cancel 作为通知 ===');
  notify('session/cancel',{sessionId:sid});
  await sleep(1500);
  log('CANCEL_NOTIFY_SENT (无错误响应即接受)');
  log('=== session/close ===');
  const cl=await rpc('session/close',{sessionId:sid});
  log('CLOSE:',JSON.stringify(cl).slice(0,200));
  await sleep(500); proc.kill(); log('=== DONE ==='); process.exit(0);
})();
