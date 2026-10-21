// Real helper + real receiver + real media. The helper store contains ONLY the emulator.
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const exec=promisify(execFile), sleep=ms=>new Promise(r=>setTimeout(r,ms));
const [adb,serial,avd,outArg]=process.argv.slice(2);
assert.match(serial||'',/^emulator-\d+$/);
assert.equal((await exec(adb,['-s',serial,'shell','getprop','ro.kernel.qemu'])).stdout.trim(),'1');
assert.equal((await exec(adb,['-s',serial,'emu','avd','name'])).stdout.trim().split(/\r?\n/)[0],avd);
const origin='http://127.0.0.1:58132';
const info=await(await fetch('http://127.0.0.1:8765/v1/info')).json();
const data=join(homedir(),'Library/Application Support/DaniilCast');
const store=JSON.parse(await readFile(join(data,'devices.json'),'utf8'));
const device=store.devices[info.id];
assert.equal(device?.address,'127.0.0.1','Pair this emulator first');assert.equal(device.port,8765);
const cache=fileURLToPath(new URL('./.cache/smoke/',import.meta.url));await mkdir(cache,{recursive:true,mode:0o700});
await writeFile(join(cache,'devices.json'),JSON.stringify({devices:{[info.id]:device},selected:info.id}),{mode:0o600});
const registry=JSON.parse(await readFile(join(homedir(),'Library/Application Support/Google/Chrome/NativeMessagingHosts/tv.daniil.cast.json'),'utf8'));
const env={...process.env,DANIILCAST_HOME:cache,DANIILCAST_LOG:join(cache,'helper.log'),DANIILCAST_CACHE:join(cache,'conversion')};
const output=resolve(outArg||join(cache,'evidence'));await mkdir(output,{recursive:true});
async function send(command) {
  const {stdout}=await exec(registry.path,['send',JSON.stringify(command)],{env,maxBuffer:4*1024*1024,timeout:90000});
  const result=JSON.parse(stdout);assert.equal(result.ok,true,JSON.stringify(result));return result;
}
async function ready(check=()=>true) {
  for(let i=0;i<80;i++) {
    const result=await send({action:'status'});
    assert.equal(result.device.id,info.id);assert.equal(result.online,true);
    assert.equal(result.status?.error,null,JSON.stringify(result.status?.error));
    if(result.status?.renderedVideo&&check(result.status))return result.status;
    await sleep(250);
  }
  throw Error('Timed out waiting for rendered playback');
}
async function proof(name) {
  const status=await send({action:'status'});
  await writeFile(join(output,`${name}.json`),JSON.stringify(status,null,2));
  const {stdout}=await exec(adb,['-s',serial,'exec-out','screencap','-p'],{encoding:'buffer',maxBuffer:12*1024*1024});
  await writeFile(join(output,`${name}.png`),stdout);
}
const subtitles=['en.vtt','fr.vtt','en.srt','en.ass'].map((file,i)=>({url:`${origin}/${file}`,label:['English','French','SRT','ASS'][i],language:i===1?'fr':'en',mime:i<2?'text/vtt':i===2?'application/x-subrip':'text/x-ssa'}));
const catalog={title:'Local test series',headers:{},seasons:[{id:'s1',number:'1',episodes:[1,2,3].map(n=>({
  id:`s1e${n}`,number:String(n),title:`Episode ${n}`,duration:150,audioNames:['English','French'],subtitles,
  versions:[{kind:'mp4',url:`${origin}/media/sample.mp4?episode=${n}`,mime:'video/mp4',codec:'H.264'}]
}))}]};
const cases=[['mp4','sample.mp4','video/mp4'],['hls','hls/stream.m3u8','application/x-mpegURL'],['dash','dash/stream.mpd','application/dash+xml']];
for(const [kind,path,mime] of cases) {
  console.log(`Testing ${kind.toUpperCase()} playback, pause, seek, subtitles and speed...`);
  await send({action:'load',url:`${origin}/media/${path}`,mime,kind,title:`Local ${kind.toUpperCase()} test`,subtitles,subtitle:'0',
    ...(kind==='mp4'?{catalog,episode:'s1e1'}:{})});
  let status=await ready(s=>s.playing&&s.position>500);assert.equal(status.source.kind,kind);
  await send({action:'pause'});await ready(s=>!s.playing);
  await send({action:'seek',position:85000});status=await ready(s=>Math.abs(s.position-85000)<2500);
  if(status.audioTracks.length>1){const id=status.audioTracks[1].id;await send({action:'audio',track:id});await ready(s=>s.audioTrack===id)}
  await send({action:'subtitles',track:'off'});await ready(s=>s.textTrack==='off');
  for(let i=0;i<4;i++){
    const id=status.textTracks[i].id;
    await send({action:'subtitles',track:id});await ready(s=>s.textTrack===id);
    if(i===1) await proof(`${kind}-paused-french-85s`);
  }
  await send({action:'subtitles',size:'large',color:'yellow',edge:'shadow',position:'raised'});
  await ready(s=>s.subtitleStyle.color==='yellow'&&s.subtitleStyle.size==='large');
  await send({action:'speed',value:1.5});await ready(s=>s.speed===1.5);
  if(kind==='dash'){
    assert.ok(status.qualities.length>1,'DASH must offer two qualities');
    const id=status.qualities.at(-1).id;await send({action:'quality',id});await ready(s=>s.quality===id);
    await send({action:'quality',id:'auto'});await ready(s=>s.quality==='auto');
  }
  await send({action:'play'});await ready(s=>s.playing&&s.position>85500);await proof(`${kind}-playing`);
  await send({action:'speed',value:1});
  if(kind==='mp4'){
    await send({action:'next'});await ready(s=>s.episode?.id==='s1e2'&&s.playing);
    await send({action:'previous'});await ready(s=>s.episode?.id==='s1e1'&&s.playing);
    await send({action:'episode',id:'s1e3'});await ready(s=>s.episode?.id==='s1e3'&&s.playing);
    await proof('episode-selection');
  }
}
console.log('Testing AV1 conversion on the installed desktop helper...');
await send({action:'load',url:`${origin}/media/av1.webm`,mime:'video/webm',codec:'AV1',convert:true,title:'Local AV1 conversion',subtitles:subtitles.slice(0,2),subtitle:'0'});
await ready(s=>s.playing&&s.source.convertedFrom==='AV1');await proof('av1-converted');
await send({action:'seek',position:15000});await ready(s=>s.playing&&s.position>=15000);await proof('av1-seek');
await send({action:'stop'});
console.log('Testing a cast arriving while the TV is at Home...');
await exec(adb,['-s',serial,'shell','am','start','-a','android.intent.action.MAIN','-c','android.intent.category.HOME']);
await sleep(500);
await send({action:'load',url:`${origin}/media/sample.mp4`,kind:'mp4',mime:'video/mp4',title:'Local background cast',subtitles:subtitles.slice(0,2),subtitle:'0'});
await ready(s=>s.playing);
assert.match((await exec(adb,['-s',serial,'shell','dumpsys','activity','activities'])).stdout,/topResumedActivity=.*tv\.daniil\.cast/);
await proof('cast-from-home');
await send({action:'stop'});
console.log(`PASS: MP4, HLS, DASH, episodes, 4 subtitle tracks, audio switching, seeking, speed, style, DASH quality, AV1 conversion and casting from Home. Evidence: ${output}`);
