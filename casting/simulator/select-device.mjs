// Select/pair only the verified local ADB bridge. Never probe or select a LAN TV.
import {spawnSync} from 'node:child_process';
import {verifyEmulator,installedHelper,exec} from './local-helper.mjs';
await verifyEmulator(...process.argv.slice(2));
const helper=await installedHelper();
const info=await(await fetch('http://127.0.0.1:8765/v1/info')).json();
const {stdout}=await exec(helper,['devices']);
const {devices}=JSON.parse(stdout);
const device=devices.find(d=>d.id===info.id&&d.address==='127.0.0.1'&&d.port===8765);
if(!device) {
  if(!process.stdin.isTTY)throw Error('First use: run this command in Terminal and enter the pairing code shown in the emulator.');
  console.log('First use: enter the six-digit code shown in the emulator.');
  const paired=spawnSync(helper,['pair','127.0.0.1'],{stdio:'inherit'});
  if(paired.status!==0)throw Error('Emulator pairing failed');
} else {
  await exec(helper,['send',JSON.stringify({action:'select',id:device.id})]);
}
console.log('Selected local emulator. Reopen Daniil Cast in Chrome if the side panel was already open.');
