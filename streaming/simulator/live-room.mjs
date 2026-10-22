// The only input target is the verified emulator supplied by the launcher.
export function inputCommand(input, display) {
  if (input.type === 'key') {
    const keys = { back: '4', home: '3', menu: '82', up: '19', down: '20', left: '21', right: '22', select: '23', play: '85' };
    if (!Object.hasOwn(keys, input.key)) throw Error('Unknown key');
    const key = keys[input.key];
    return ['keyevent', key];
  }
  if (input.type !== 'pointer') throw Error('Unknown input');
  for (const point of [input.from, input.to])
    if (!point || ![point.x, point.y].every(n => Number.isFinite(n) && n >= 0 && n <= 1)) throw Error('Invalid coordinates');
  const size = [...display.matchAll(/(\d+)x(\d+)/g)].at(-1);
  if (!size) throw Error('Cannot read display size');
  const point = p => [Math.round(p.x * (Number(size[1]) - 1)), Math.round(p.y * (Number(size[2]) - 1))].map(String);
  const from = point(input.from), to = point(input.to);
  const swipe = Math.hypot(from[0] - to[0], from[1] - to[1]) > 8;
  const duration = String(Math.max(75, Math.min(1000, Number.isFinite(input.duration) ? input.duration : 200)));
  return [input.mouse ? 'mouse' : 'touchscreen', ...(swipe ? ['swipe', ...from, ...to, duration] : ['tap', ...to])];
}

export function liveRoom(serial, avd) {
  const escape = value => value.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  return `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Daniil TV · Local test room</title>
  <style>body{font:16px/1.5 system-ui;background:#0b141b;color:#e7eff3;max-width:1200px;padding:28px;margin:auto}h1{margin-bottom:4px}p{color:#aabdc6}img{width:100%;background:#030608;border-radius:12px;touch-action:none}button{font:inherit;background:#1e3743;color:#e7eff3;padding:10px 18px;border:1px solid #506875;border-radius:8px;cursor:pointer}nav{display:flex;gap:8px;flex-wrap:wrap;margin:20px 0}button:focus{outline:3px solid #67dcb7}</style>
  <h1>Daniil TV</h1><p>Local test room · ${escape(avd)} · ${escape(serial)}</p>
  <img id="screen" src="/screen.png" alt="Live Android TV emulator; click, tap or drag to interact" draggable="false">
  <nav>${['back', 'home', 'menu', 'up', 'down', 'left', 'right', 'select', 'play'].map(key => `<button data-key="${key}">${key}</button>`).join('')}</nav>
  <p id="status" role="status">The image is the real emulator. Open Daniil TV from its launcher to begin.</p>
  <script>
  const screen=document.querySelector('#screen'),status=document.querySelector('#status');let start;
  async function input(body){try{const r=await fetch('/input',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});if(!r.ok)throw Error(await r.text());status.textContent='Input sent to the local emulator.'}catch(e){status.textContent=e.message}}
  const point=e=>{const r=screen.getBoundingClientRect();return{x:Math.max(0,Math.min(1,(e.clientX-r.left)/r.width)),y:Math.max(0,Math.min(1,(e.clientY-r.top)/r.height))}};
  screen.onpointerdown=e=>{start={...point(e),at:Date.now(),mouse:e.pointerType==='mouse'};screen.setPointerCapture(e.pointerId);e.preventDefault()};
  screen.onpointerup=e=>{if(start){input({type:'pointer',from:start,to:point(e),duration:Date.now()-start.at,mouse:start.mouse});start=null}};
  screen.onpointercancel=()=>start=null;document.querySelectorAll('[data-key]').forEach(b=>b.onclick=()=>input({type:'key',key:b.dataset.key}));
  screen.onload=()=>setTimeout(()=>screen.src='/screen.png?t='+Date.now(),750);screen.onerror=()=>setTimeout(()=>screen.src='/screen.png?t='+Date.now(),2000);
  </script></html>`;
}
