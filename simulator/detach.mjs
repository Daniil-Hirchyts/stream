// Survive the launcher/terminal closing, including shells managed by an IDE.
import { spawn } from 'node:child_process';
import { openSync, closeSync } from 'node:fs';
const [log,program,...args]=process.argv.slice(2);
const fd=openSync(log,'a',0o600);
const child=spawn(program,args,{detached:true,stdio:['ignore',fd,fd]});
child.on('error',error=>{console.error(error.message);process.exitCode=1});
child.on('spawn',()=>{console.log(`Started ${program} (PID ${child.pid}).`);child.unref();closeSync(fd)});
