import {localHelper} from './local-helper.mjs';
const send=await localHelper(...process.argv.slice(2));
const origin='http://127.0.0.1:58132';
const subtitles=[['en','English'],['fr','French']].map(([language,label])=>({url:`${origin}/${language}.vtt`,mime:'text/vtt',language,label}));
await send({action:'load',url:`${origin}/media/sample.mp4`,kind:'mp4',mime:'video/mp4',title:'Local simulator · ready to test',subtitles,subtitle:'0',audioNames:['English','French']});
let ready=false;
for(let i=0;i<80;i++) {
  const {status,online}=await send({action:'status'});
  if(status?.error)throw Error(JSON.stringify(status.error));
  if(online&&status?.renderedVideo&&status?.playing&&status.position>500){ready=true;break}
  await new Promise(resolve=>setTimeout(resolve,250));
}
if(!ready)throw Error('The local sample did not render. Run ./casting/simulator.sh logs.');
console.log('Verified: native helper connected, local video rendered and playback advancing.');
