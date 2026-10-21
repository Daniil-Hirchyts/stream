// Local UI preview only: Chrome APIs are stubbed; no native helper or TV is contacted.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { resolve, extname, sep } from "node:path";

const root = fileURLToPath(new URL("../../extension/", import.meta.url));
const device = { id: "preview", selected: true, name: "Local simulator", model: "Android TV",
  address: "127.0.0.1", port: 8765 };
const status = { phase: "paused", version: "0.3.1", title: "Subtitle playback test", step: "", stepFormat: "",
  source: { convertedFrom: "AV1" }, videoFormat: { width: 640, height: 360, mime: "video/avc" },
  audioTracks: [{ id: "1", label: "English" }], audioTrack: "1", textTracks: [{ id: "0", label: "English" }, { id: "1", label: "French" }],
  textTrack: "0", qualities: [], quality: "auto", position: 85000, duration: 150000, playing: false,
  playWhenReady: false, speed: 1, episode: null, catalog: null, subtitleStyle: { size: "medium", color: "white", edge: "box", position: "standard" } };

const mock = `const device=${JSON.stringify(device)}, status=${JSON.stringify(status)};
const event = {addListener(){},removeListener(){}};
window.chrome = {runtime:{getManifest:()=>({version:'0.3.1'}),reload:()=>location.reload(),onMessage:event,
  connectNative(){ let receive;return {onMessage:{addListener(fn){receive=fn}},onDisconnect:event,postMessage(m){
    let response={ok:true};
    if(m.action==='hello') Object.assign(response,{version:'0.4.0',conversion:true,devices:[device]});
    if(m.action==='status') Object.assign(response,{online:true,device,status,decoders:{video:['H.264','HEVC','VP9'],audio:['AAC']},conversion:{active:true,sourceCodec:'av1'}});
    if(m.action==='log') response.entries=[{at:Date.now()-20000,level:'info',message:'Local preview started',detail:{format:'WebVTT',position:85000}}];
    if(m.action==='discover') response.tvs=[{...device,paired:true}];
    if(m.action==='pause'||m.action==='play'){status.playing=m.action==='play';status.playWhenReady=status.playing;status.phase=status.playing?'playing':'paused'}
    if(m.action==='seek') status.position=m.position;
    if(m.action==='subtitles')status.textTrack=m.track;
    if(m.action==='speed')status.speed=m.value;
    setTimeout(()=>receive({...response,request:m.request}),20);
  }}},
  sendMessage:async()=>({ok:true,candidates:[],subtitles:[],textTrack:null,audio:null,series:null,drm:false,blob:false,position:0})},
  tabs:{query:async()=>[{id:1,url:'https://example.invalid/local-preview',title:'Local preview'}],onActivated:event,onUpdated:event},windows:{onFocusChanged:event}};`;

const server = createServer(async (request, response) => {
  try {
    if (request.url === "/preview-mock.js") {
      response.setHeader("Content-Type", "text/javascript"); response.end(mock); return;
    }
    const path = resolve(root, "." + (request.url === "/" ? "/sidepanel.html" : new URL(request.url, "http://localhost").pathname));
    if (!path.startsWith(root.endsWith(sep) ? root : root + sep)) { response.writeHead(403).end(); return; }
    let content = await readFile(path);
    if (extname(path) === ".html") content = Buffer.from(content.toString().replace("</head>",
      '<script src="/preview-mock.js"></script><style>body{max-width:400px;margin:0 auto;box-shadow:0 0 0 1px #dce3e8}</style></head>'));
    response.setHeader("Content-Type", ({".html":"text/html", ".js":"text/javascript", ".css":"text/css", ".png":"image/png"})[extname(path)] || "application/octet-stream");
    response.setHeader("Cache-Control", "no-store"); response.end(content);
  } catch { response.writeHead(404).end(); }
});
server.listen(58131, "127.0.0.1", () => console.log("Local UI preview: http://127.0.0.1:58131"));
