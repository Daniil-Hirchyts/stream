// All automation uses an isolated store containing ONLY the verified emulator.
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {homedir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
export const exec=promisify(execFile);
export async function verifyEmulator(adb,serial,avd) {
  assert.match(serial||'',/^emulator-\d+$/);
  assert.equal((await exec(adb,['-s',serial,'shell','getprop','ro.kernel.qemu'])).stdout.trim(),'1');
  assert.equal((await exec(adb,['-s',serial,'emu','avd','name'])).stdout.trim().split(/\r?\n/)[0],avd);
}
export async function installedHelper() {
  const registry=join(homedir(),'Library/Application Support/Google/Chrome/NativeMessagingHosts/tv.daniil.cast.json');
  return JSON.parse(await readFile(registry,'utf8')).path;
}
export async function localHelper(adb,serial,avd,name='demo') {
  await verifyEmulator(adb,serial,avd);
  const info=await(await fetch('http://127.0.0.1:8765/v1/info')).json();
  const store=JSON.parse(await readFile(join(homedir(),'Library/Application Support/DaniilCast/devices.json'),'utf8'));
  const device=store.devices[info.id];
  assert.equal(device?.address,'127.0.0.1','Pair this emulator first');
  assert.equal(device.port,8765);
  const cache=fileURLToPath(new URL(`./.cache/${name}/`,import.meta.url));
  await mkdir(cache,{recursive:true,mode:0o700});
  await writeFile(join(cache,'devices.json'),JSON.stringify({devices:{[info.id]:device},selected:info.id}),{mode:0o600});
  const helper=await installedHelper();
  const env={...process.env,DANIILCAST_HOME:cache,DANIILCAST_LOG:join(cache,'helper.log'),DANIILCAST_CACHE:join(cache,'conversion')};
  return async command=>{
    const {stdout}=await exec(helper,['send',JSON.stringify(command)],{env,maxBuffer:4*1024*1024,timeout:90000});
    const result=JSON.parse(stdout);
    assert.equal(result.ok,true,JSON.stringify(result.error||result));
    if(result.device)assert.equal(result.device.id,info.id);
    return result;
  };
}
