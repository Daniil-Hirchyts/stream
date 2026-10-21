// Real test media for the installed Chrome extension; no mocked Chrome APIs.
import { createServer } from 'node:http';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { resolve, extname, sep } from 'node:path';
const run = promisify(execFile);
const [adb, serial, avd] = process.argv.slice(2);
if (!/^emulator-\d+$/.test(serial || '')) throw Error('An emulator serial is required');
const root = fileURLToPath(new URL('./.cache/', import.meta.url));
const origin = 'http://127.0.0.1:58132';
const tracks = [
  {url:`${origin}/en.vtt`,name:'English · long cue'},
  {url:`${origin}/fr.vtt`,name:'French · long cue'},
  {url:`${origin}/en.srt`,name:'English SRT'},
  {url:`${origin}/en.ass`,name:'English ASS'}
];
const sources = {mp4:['sample.mp4','video/mp4'],hls:['hls/stream.m3u8','application/x-mpegURL'],
  dash:['dash/stream.mpd','application/dash+xml'],av1:['av1.webm','video/webm']};
const escape = s => s.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
function page(format='mp4') {
  const [path, mime] = sources[format];
  const media = `${origin}/media/${path}`;
  const episodes = [1,2,3].map(n=>({episode:String(n),title:`Local test episode ${n}`,
    duration:150,mp4:`${origin}/media/sample.mp4?episode=${n}`,cc:tracks,audio:{names:['English','French'],order:[0,1]}}));
  const config = `const localPlayer = {source: {${format==='av1'?'mp4':format}: "${media}"},
    seasons: ${JSON.stringify([{season:'1',episodes}])}, current: {season: "1", episode: "1"}};`;
  return `<!doctype html><html lang="en"><meta charset="utf-8"><title>Daniil Cast · Local ${format.toUpperCase()} test</title>
  <meta name="viewport" content="width=device-width,initial-scale=1"><style>
  *{box-sizing:border-box}body{background:#0b151c;color:#eef6f7;font:16px/1.6 system-ui;margin:0;padding:32px;max-width:1180px}h1{font-size:32px;margin:0}p{color:#b9c9cf}nav{display:flex;gap:12px;flex-wrap:wrap;margin:24px 0}a,button{color:#16d6b1;background:#172b36;border:1px solid #31505f;border-radius:10px;padding:10px 16px;text-decoration:none;font:inherit;cursor:pointer}a[aria-current]{background:#124b43;color:white}video,img{width:100%;border-radius:12px;background:#050a0d}section{margin-top:24px;background:#13232c;padding:24px;border-radius:16px}code{color:#1bdfb9}li{margin:8px 0}.split{display:grid;grid-template-columns:1fr 1fr;gap:24px}@media(max-width:850px){.split{grid-template-columns:1fr}}
  </style><h1>Daniil Cast · Local test room</h1><p>Real Chrome extension → desktop helper → Android TV emulator. Generated media with two audio tracks and long subtitles.</p>
  <nav>${Object.keys(sources).map(k=>`<a href="/watch/${k}" ${k===format?'aria-current="page"':''}>${k.toUpperCase()}</a>`).join('')}</nav>
  <div class="split"><section><h2>${format.toUpperCase()} playback</h2>
  ${['mp4','av1'].includes(format)?`<video controls preload="metadata" src="${media}"><track kind="subtitles" src="/en.vtt" srclang="en" label="English" default><track kind="subtitles" src="/fr.vtt" srclang="fr" label="French"></video>`:'<p>This page exposes the stream to Daniil Cast. Press Cast in the extension to play it in the emulator.</p>'}
  <ol><li>Open <strong>Daniil Cast</strong> in the Chrome toolbar.</li><li>Use the local emulator in the <strong>TV</strong> tab.</li><li>In <strong>Cast</strong>, choose this source and press Cast.</li><li>Use <strong>Remote</strong> to seek, pause, change audio, subtitles, speed and quality.</li></ol>
  <p>Seek to <strong>1:25</strong> or turn subtitles on there: the long cue must remain visible until <strong>2:10</strong>. English is a low tone; French is a higher tone. DASH provides two video qualities. The MP4 catalogue has three test episodes.</p>
  <p>Source: <a href="${media}">${escape(path)}</a></p></section>
  <section><h2>Live emulator screen</h2><p id="device">${escape(avd)} · ${escape(serial)}</p><img id="screen" style="touch-action:none;cursor:pointer" draggable="false" src="/screen.png" alt="Interactive Android TV emulator screen"><p>Click or tap the screen to use the app. Drag the timeline to seek. The visible emulator window supports the same controls.</p><nav><button data-key="back">Back</button><button data-key="menu">Options</button><button data-key="home">Home</button></nav><p id="input-status" role="status"></p></section></div>
  <script>${config}</script><script>
  const screen=document.getElementById('screen'),status=document.getElementById('input-status');
  const refresh=()=>{screen.src='/screen.png?t='+Date.now()};
  async function input(value){try{const result=await fetch('/input',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value)});if(!result.ok)throw Error(await result.text());status.textContent='';refresh()}catch(error){status.textContent=error.message}}
  let start;
  function point(event){const rect=screen.getBoundingClientRect();return {x:Math.max(0,Math.min(1,(event.clientX-rect.left)/rect.width)),y:Math.max(0,Math.min(1,(event.clientY-rect.top)/rect.height))}}
  screen.onpointerdown=event=>{start={...point(event),at:Date.now(),mouse:event.pointerType==='mouse'};screen.setPointerCapture(event.pointerId);event.preventDefault()};
  screen.onpointerup=event=>{if(start){input({type:'pointer',from:start,to:point(event),duration:Date.now()-start.at,mouse:start.mouse});start=null}};
  screen.onpointercancel=()=>{start=null};
  document.querySelectorAll('[data-key]').forEach(button=>button.onclick=()=>input({type:'key',key:button.dataset.key}));
  setInterval(()=>{if(!document.hidden&&!start)refresh()},2000);
  </script></html>`;
}
async function verify() {
  const {stdout} = await run(adb,['-s',serial,'shell','getprop','ro.kernel.qemu']);
  if(stdout.trim()!=='1') throw Error('Refusing a non-emulator device');
  const {stdout:name} = await run(adb,['-s',serial,'emu','avd','name']);
  if(name.trim().split(/\r?\n/)[0]!==avd) throw Error('Unexpected emulator at this port');
}
await verify();
let capture;
const mime={'.mp4':'video/mp4','.webm':'video/webm','.m3u8':'application/x-mpegURL','.mpd':'application/dash+xml','.ts':'video/mp2t','.m4s':'video/iso.segment','.vtt':'text/vtt'};
const server=createServer(async(req,res)=>{
  try {
    if(req.method==='POST'&&req.url==='/input'){
      // Control is same-origin only, bounded, and always pinned to this verified AVD.
      if(req.headers.origin!==origin||req.headers['content-type']!=='application/json'){res.writeHead(403).end('Same-origin JSON required');return}
      let body='';for await(const chunk of req){body+=chunk;if(body.length>1024){res.writeHead(413).end();return}}
      const input=JSON.parse(body);await verify();
      if(input.type==='key'){
        const key={back:'4',home:'3',menu:'82'}[input.key];
        if(!key){res.writeHead(400).end('Unknown key');return}
        await run(adb,['-s',serial,'shell','input','keyevent',key]);
      }else if(input.type==='pointer'){
        for(const point of [input.from,input.to])if(!point||![point.x,point.y].every(n=>Number.isFinite(n)&&n>=0&&n<=1))throw Error('Invalid coordinates');
        const {stdout}=await run(adb,['-s',serial,'shell','wm','size']);
        const size=[...stdout.matchAll(/(\d+)x(\d+)/g)].at(-1);
        if(!size)throw Error('Cannot read display size');
        const point=p=>[Math.round(p.x*(Number(size[1])-1)),Math.round(p.y*(Number(size[2])-1))].map(String);
        const from=point(input.from),to=point(input.to);
        const swipe=Math.hypot(Number(from[0])-Number(to[0]),Number(from[1])-Number(to[1]))>8;
        const duration=String(Math.max(75,Math.min(1000,Number.isFinite(input.duration)?input.duration:200)));
        await run(adb,['-s',serial,'shell','input',input.mouse?'mouse':'touchscreen',...(swipe?['swipe',...from,...to,duration]:['tap',...to])]);
      }else{res.writeHead(400).end('Unknown input');return}
      res.setHeader('Content-Type','application/json');res.end('{"ok":true}');return;
    }
    if(req.method!=='GET'&&req.method!=='HEAD'){res.writeHead(405).end();return}
    const url=new URL(req.url,origin), path=url.pathname;
    res.setHeader('Cache-Control','no-store');res.setHeader('Access-Control-Allow-Origin','*');
    if(path==='/health'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({app:'Daniil Cast local test room',serial,avd}));return}
    if(path==='/screen.png'){
      capture ??= verify().then(()=>run(adb,['-s',serial,'exec-out','screencap','-p'],{encoding:'buffer',maxBuffer:12*1024*1024})).finally(()=>{capture=null});
      const {stdout}=await capture;res.setHeader('Content-Type','image/png');res.end(stdout);return
    }
    if(/^\/(en|fr)\.(vtt|srt|ass)$/.test(path)){
      const french=path.startsWith('/fr'),text=french?'Sous-titre français : reste visible après un saut.':'English long cue: stays visible after seeking or late selection.';
      if(path.endsWith('.ass')){res.setHeader('Content-Type','text/x-ssa');res.end('[Script Info]\nScriptType: v4.00+\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, Bold, Alignment\nStyle: Default,Arial,28,&H00FFFFFF,0,2\n[Events]\nFormat: Layer, Start, End, Style, Text\nDialogue: 0,0:00:00.00,0:02:10.00,Default,'+text+'\n');return}
      res.setHeader('Content-Type',path.endsWith('.vtt')?'text/vtt; charset=utf-8':'application/x-subrip');
      res.end(path.endsWith('.vtt')?'WEBVTT\n\n00:00.000 --> 02:10.000\n'+text+'\n\n02:11.000 --> 02:29.000\nThe long cue has ended.\n':'1\n00:00:00,000 --> 00:02:10,000\n'+text+'\n');return
    }
    if(path.startsWith('/media/')){
      const file=resolve(root,'.'+path.slice('/media'.length));
      if(!file.startsWith(root.endsWith(sep)?root:root+sep)){res.writeHead(403).end();return}
      const info=await stat(file);let start=0,end=info.size-1;
      res.setHeader('Content-Type',mime[extname(file)]||'application/octet-stream');res.setHeader('Accept-Ranges','bytes');
      if(req.headers.range){const match=/^bytes=(\d+)-(\d*)$/.exec(req.headers.range);if(!match){res.writeHead(416,{'Content-Range':`bytes */${info.size}`}).end();return}
        start=Number(match[1]);end=match[2]?Math.min(Number(match[2]),end):end;
        if(start>end){res.writeHead(416,{'Content-Range':`bytes */${info.size}`}).end();return}
        res.statusCode=206;res.setHeader('Content-Range',`bytes ${start}-${end}/${info.size}`)}
      res.setHeader('Content-Length',end-start+1);if(req.method==='HEAD'){res.end();return}
      createReadStream(file,{start,end}).on('error',()=>res.destroy()).pipe(res);return
    }
    const format=path==='/'?'mp4':path.split('/')[2];
    if(!sources[format]){res.writeHead(404).end();return}
    res.setHeader('Content-Type','text/html; charset=utf-8');res.end(page(format));
  }catch(error){res.writeHead(503,{'Content-Type':'text/plain'}).end(error.message)}
});
server.listen(58132,'127.0.0.1',()=>console.log(`Local test room: ${origin} (${serial})`));
