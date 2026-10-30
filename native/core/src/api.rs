//! The registry of engines behind the app's handles. Engines, torrents and readers are 64-bit handles that are
//! never reused. Every call leases its engine, so closing an engine while a read blocks in another thread can't free
//! it underneath; close waits for the leases.

use crate::engine::{Config, Engine, EngineError, ErrorKind, Result};
use std::collections::{BTreeMap, VecDeque};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

/// Recent diagnostics kept per engine for the Developer view.
const LOG_LINES: usize = 200;

pub(crate) struct Entry {
    pub engine: Engine,
    /// Read by the JNI bridge's diagnostics.
    pub log: Arc<Mutex<VecDeque<String>>>,
}

struct Registry {
    engines: BTreeMap<u64, Arc<Entry>>,
    next: u64,
}

static REGISTRY: Mutex<Registry> = Mutex::new(Registry { engines: BTreeMap::new(), next: 1 });

fn registry() -> MutexGuard<'static, Registry> {
    REGISTRY.lock().unwrap_or_else(|e| e.into_inner())
}

/// Creates an engine; its diagnostics go to its log ring and to `forward`, if any.
pub(crate) fn create_engine(mut config: Config) -> Result<u64> {
    let log: Arc<Mutex<VecDeque<String>>> = Arc::default();
    let ring = Arc::downgrade(&log);
    let forward = config.log.take();
    config.log = Some(Arc::new(move |line: &str| {
        if let Some(ring) = ring.upgrade() {
            let mut ring = ring.lock().unwrap_or_else(|e| e.into_inner());
            ring.push_back(line.to_owned());
            while ring.len() > LOG_LINES {
                ring.pop_front();
            }
        }
        if let Some(forward) = &forward {
            forward(line);
        }
    }));
    let engine = Engine::new(config)?;
    let mut reg = registry();
    let id = reg.next;
    reg.next += 1;
    reg.engines.insert(id, Arc::new(Entry { engine, log }));
    Ok(id)
}


/// The engine for one call.
pub(crate) fn lease(id: u64) -> Result<Arc<Entry>> {
    registry()
        .engines
        .get(&id)
        .cloned()
        .ok_or_else(|| EngineError::new(ErrorKind::InvalidHandle, "The torrent engine is closed"))
}

/// No new leases can start. Wakes blocked calls, waits for the current ones, then shuts the engine down.
pub(crate) fn destroy(id: u64) {
    let Some(entry) = registry().engines.remove(&id) else { return };
    entry.engine.request_stop();
    while Arc::strong_count(&entry) > 1 {
        std::thread::sleep(Duration::from_millis(2));
    }
    drop(entry);
}
