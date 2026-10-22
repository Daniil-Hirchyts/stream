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
function page(format='mp4') {
  const [path] = sources[format];
  const media = `${origin}/media/${path}`;
  const episodes = [1,2,3].map(n=>({episode:String(n),title:`Local test episode ${n}`,
    duration:150,mp4:`${origin}/media/sample.mp4?episode=${n}`,cc:tracks,audio:{names:['English','French'],order:[0,1]}}));
  const config = `const localPlayer = {source: {${format==='av1'?'mp4':format}: "${media}"},
    seasons: ${JSON.stringify([{season:'1',episodes}])}, current: {season: "1", episode: "1"}};`;
  const icon = name => ({
    power: '<path d="M12 2v9"/><path d="M6.2 5.8a8 8 0 1 0 11.6 0"/>',
    up: '<path d="m6 14 6-6 6 6"/>', down: '<path d="m6 10 6 6 6-6"/>',
    left: '<path d="m14 6-6 6 6 6"/>', right: '<path d="m10 6 6 6-6 6"/>',
    back: '<path d="m9 14-4-4 4-4"/><path d="M5 10h9a5 5 0 0 1 0 10h-2"/>',
    home: '<path d="m3 10 9-7 9 7"/><path d="M5 9v11h14V9M9 20v-6h6v6"/>',
    menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
    rewind: '<path d="m11 12 9-7v14l-9-7Z"/><path d="M4 5v14"/><path d="m11 12-9-7v14l9-7Z"/>',
    forward: '<path d="m13 12-9-7v14l9-7Z"/><path d="M20 5v14"/><path d="m13 12 9-7v14l-9-7Z"/>',
    mute: '<path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="m16 9 5 6m0-6-5 6"/>',
    volumeDown: '<path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="M15 9a5 5 0 0 1 0 6"/>',
    volumeUp: '<path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="M15 9a5 5 0 0 1 0 6M18 6a9 9 0 0 1 0 12"/>'
  })[name];
  const svg = name => `<svg viewBox="0 0 24 24" aria-hidden="true">${icon(name)}</svg>`;
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Daniil Cast · Local test room</title>
  <meta name="viewport" content="width=device-width,initial-scale=1"><style>
  *{box-sizing:border-box}html,body{width:100%;min-height:100%;margin:0}body{min-height:100vh;min-height:100svh;overflow:auto;background:radial-gradient(ellipse at 38% 48%,#1a2029 0,#11151b 55%,#0b0d11 100%);font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#fff}
  .room{width:min(100%,1760px);min-height:100vh;min-height:100svh;margin:0 auto;padding:clamp(18px,3vw,44px);display:grid;grid-template-columns:minmax(0,1fr) 232px;gap:clamp(22px,3.5vw,56px);align-items:center}
  .screen-stage{min-width:0;display:grid;place-items:center}.screen-frame{width:100%;aspect-ratio:16/9;overflow:hidden;border:1px solid #353b46;border-radius:18px;background:#050608;box-shadow:0 30px 90px #0009,0 0 0 7px #ffffff06}.screen{display:block;width:100%;height:100%;object-fit:contain;touch-action:none;cursor:crosshair;user-select:none;-webkit-user-drag:none}
  .remote{display:grid;place-items:center}.remote-shell{width:208px;padding:19px 17px 21px;border:1px solid #42454b;border-radius:48px;background:linear-gradient(112deg,#292b30 0%,#181a1e 28%,#24262b 56%,#17191d 100%);box-shadow:inset 2px 0 4px #ffffff17,inset -3px 0 7px #0009,0 26px 60px #0009;display:flex;flex-direction:column;align-items:center;gap:15px}
  button{font:inherit;color:#e8e9eb;cursor:pointer;-webkit-tap-highlight-color:transparent;user-select:none}button:focus-visible{outline:2px solid #74b9ff;outline-offset:3px}.power{width:39px;height:39px;border:1px solid #4d5056;border-radius:50%;background:linear-gradient(#32343a,#222429);display:grid;place-items:center;align-self:flex-end;margin:0 2px 1px 0}.power svg{width:19px;height:19px;color:#f07470}
  svg{width:21px;height:21px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}.dpad{position:relative;width:164px;height:164px;border:1px solid #4b4e54;border-radius:50%;background:radial-gradient(circle at 50% 44%,#303238 0,#24262b 63%,#1d1f24 100%);box-shadow:inset 0 2px 7px #0009,0 1px 2px #ffffff15}.direction{position:absolute;width:54px;height:51px;border:0;background:transparent;display:grid;place-items:center;color:#d8dadd}.direction:hover{color:white;background:#ffffff0d}.direction svg{width:20px;height:20px}.north{left:54px;top:4px;border-radius:28px 28px 10px 10px}.south{left:54px;bottom:4px;border-radius:10px 10px 28px 28px}.west{left:4px;top:56px;border-radius:28px 10px 10px 28px}.east{right:4px;top:56px;border-radius:10px 28px 28px 10px}.select{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:60px;height:60px;border:1px solid #53565c;border-radius:50%;background:linear-gradient(145deg,#3b3e44,#292b30);box-shadow:0 2px 7px #0008,inset 0 1px 2px #ffffff19;color:#f3f4f5;font-size:10px;font-weight:700;letter-spacing:.11em}.select:hover{background:#45484f}
  .function-row{display:flex;align-items:center;justify-content:center;gap:15px}.function{width:44px;height:40px;border:1px solid #484b51;border-radius:24px;background:linear-gradient(#303238,#222429);display:grid;place-items:center;box-shadow:0 2px 4px #0006}.function svg{width:18px;height:18px}.function:hover,.media:hover,.volume-button:hover{background:#42454b;color:#fff}.media-row{width:100%;display:flex;align-items:center;justify-content:space-between;padding:1px 2px}.media{width:43px;height:43px;border:1px solid #474a50;border-radius:50%;background:linear-gradient(#303238,#222429);display:grid;place-items:center;box-shadow:0 2px 4px #0006}.media svg{width:18px;height:18px}.media .play-icon{fill:currentColor;stroke:none;width:17px;height:17px}
  .volume-row{width:100%;display:flex;align-items:center;justify-content:center;gap:7px}.volume-button{width:38px;height:38px;border:1px solid #484b51;border-radius:50%;background:linear-gradient(#303238,#222429);display:grid;place-items:center}.volume-button svg{width:17px;height:17px}.volume-rocker{height:46px;width:78px;border:1px solid #4b4e54;border-radius:24px;overflow:hidden;display:grid;grid-template-rows:1fr 1fr;background:#202227}.volume-rocker button{border:0;background:transparent;display:grid;place-items:center}.volume-rocker button+button{border-top:1px solid #474a50}.volume-rocker svg{width:15px;height:15px}.volume-rocker button:hover{background:#42454b}
  .sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
  @media(max-width:820px){.room{grid-template-columns:minmax(0,1fr);gap:24px;padding:20px}.screen-frame{max-height:calc(100svh - 330px)}.remote-shell{width:190px;padding:16px 14px 18px;gap:12px}.dpad{width:148px;height:148px}.north{left:47px}.south{left:47px}.west{top:48px}.east{top:48px}.function-row{gap:13px}}
  </style></head><body><main class="room" aria-label="Local test room">
  <section class="screen-stage" aria-label="Emulator screen"><div class="screen-frame"><img id="screen" class="screen" draggable="false" src="/screen.png" alt="Live Android TV emulator screen"></div></section>
  <aside class="remote" aria-label="Remote control"><div class="remote-shell">
  <button class="power" data-key="power" aria-label="Power" title="Power">${svg('power')}</button>
  <div class="dpad" role="group" aria-label="Directional pad">
  <button class="direction north" data-key="up" aria-label="Up">${svg('up')}</button><button class="direction east" data-key="right" aria-label="Right">${svg('right')}</button>
  <button class="direction south" data-key="down" aria-label="Down">${svg('down')}</button><button class="direction west" data-key="left" aria-label="Left">${svg('left')}</button>
  <button class="select" data-key="select" aria-label="Select">OK</button></div>
  <div class="function-row"><button class="function" data-key="back" aria-label="Back" title="Back">${svg('back')}</button><button class="function" data-key="home" aria-label="Home" title="Home">${svg('home')}</button><button class="function" data-key="menu" aria-label="Menu" title="Menu">${svg('menu')}</button></div>
  <div class="media-row"><button class="media" data-key="rewind" aria-label="Rewind" title="Rewind">${svg('rewind')}</button><button class="media" data-key="play-pause" aria-label="Play or pause" title="Play or pause"><svg class="play-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 5v14l12-7z"/><path d="M5 5v14" fill="none" stroke="currentColor" stroke-width="2"/></svg></button><button class="media" data-key="fast-forward" aria-label="Fast forward" title="Fast forward">${svg('forward')}</button></div>
  <div class="volume-row"><button class="volume-button" data-key="mute" aria-label="Mute" title="Mute">${svg('mute')}</button><div class="volume-rocker" role="group" aria-label="Volume"><button data-key="volume-up" aria-label="Volume up" title="Volume up">${svg('volumeUp')}</button><button data-key="volume-down" aria-label="Volume down" title="Volume down">${svg('volumeDown')}</button></div></div>
  </div></aside><span id="input-status" class="sr-only" role="status" aria-live="polite"></span></main>
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
  </script></body></html>`;
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
        const key={power:'26',back:'4',home:'3',menu:'82',up:'19',down:'20',left:'21',right:'22',select:'23',
          rewind:'89','play-pause':'85','fast-forward':'90',mute:'164','volume-up':'24','volume-down':'25'}[input.key];
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
