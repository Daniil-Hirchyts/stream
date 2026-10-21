use crate::{
    paths,
    problem::{Problem, Result},
};
use fs2::FileExt;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    fs::{File, OpenOptions},
    io::Write,
    path::PathBuf,
};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub model: String,
    pub address: String,
    pub port: u16,
    pub token: String,
    #[serde(rename = "pairedAt", default)]
    pub paired_at: u64,
    #[serde(flatten)]
    pub extra: BTreeMap<String, Value>,
}
impl Device {
    pub fn public(&self) -> Value {
        let mut value = serde_json::to_value(self).expect("Device serialization");
        value.as_object_mut().unwrap().remove("token");
        value
    }
}
#[derive(Default, Serialize, Deserialize)]
pub struct Store {
    #[serde(default)]
    pub devices: BTreeMap<String, Device>,
    #[serde(default)]
    pub selected: Option<String>,
    #[serde(flatten)]
    extra: BTreeMap<String, Value>,
}
#[derive(Clone)]
pub struct Devices {
    pub path: PathBuf,
}
impl Default for Devices {
    fn default() -> Self {
        Self {
            path: paths::data().join("devices.json"),
        }
    }
}
impl Devices {
    fn lock(&self) -> Result<File> {
        paths::private_dir(
            self.path
                .parent()
                .ok_or_else(|| Problem::helper("Invalid pairing store location"))?,
        )?;
        let path = self.path.with_file_name("devices.lock");
        let file = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(&path)?;
        paths::private_file(&path)?;
        file.lock_exclusive()?;
        Ok(file)
    }
    fn read(&self) -> Result<Store> {
        match std::fs::read(&self.path) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(|_| {
                Problem::helper("The saved TV pairings could not be read")
                    .message("Keep devices.json for recovery; it has not been overwritten.")
            }),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(Store::default()),
            Err(error) => Err(error.into()),
        }
    }
    pub fn all(&self) -> Result<Store> {
        let _lock = self.lock()?;
        self.read()
    }
    pub fn update<T>(&self, change: impl FnOnce(&mut Store) -> Result<T>) -> Result<T> {
        let _lock = self.lock()?;
        let mut store = self.read()?;
        let result = change(&mut store)?;
        let mut temp = tempfile::NamedTempFile::new_in(self.path.parent().unwrap())?;
        serde_json::to_writer_pretty(&mut temp, &store)
            .map_err(|_| Problem::helper("Couldn’t save the TV pairings"))?;
        temp.write_all(b"\n")?;
        temp.as_file().sync_all()?;
        paths::private_file(temp.path())?;
        temp.persist(&self.path)
            .map_err(|_| Problem::helper("Couldn’t save the TV pairings"))?;
        Ok(result)
    }
    pub fn selected(&self) -> Result<Device> {
        let store = self.all()?;
        store
            .selected
            .as_ref()
            .and_then(|id| store.devices.get(id))
            .or_else(|| store.devices.values().next())
            .cloned()
            .ok_or_else(|| {
                Problem::new("no-tv", "No TV yet").message("Pair a TV in the TV tab first.")
            })
    }
    pub fn public(&self) -> Result<Value> {
        let store = self.all()?;
        let chosen = store
            .selected
            .as_ref()
            .filter(|id| store.devices.contains_key(*id))
            .or_else(|| store.devices.keys().next());
        Ok(Value::Array(
            store
                .devices
                .values()
                .map(|device| {
                    let mut value = device.public();
                    value["selected"] = json!(Some(&device.id) == chosen);
                    value
                })
                .collect(),
        ))
    }
    pub fn add(&self, info: &Value, address: String, port: u16, token: String) -> Result<Device> {
        let device = Device {
            id: info["id"]
                .as_str()
                .filter(|id| !id.is_empty())
                .ok_or_else(|| Problem::helper("The TV returned an invalid identity"))?
                .into(),
            name: info["name"]
                .as_str()
                .filter(|name| !name.is_empty())
                .unwrap_or("TV")
                .into(),
            model: info["model"].as_str().unwrap_or_default().into(),
            address,
            port,
            token,
            paired_at: paths::now_ms(),
            extra: BTreeMap::new(),
        };
        self.update(|store| {
            store.selected = Some(device.id.clone());
            store.devices.insert(device.id.clone(), device.clone());
            Ok(device)
        })
    }
    pub fn move_to(&self, id: &str, address: &str, port: u16) -> Result<()> {
        self.update(|store| {
            if let Some(device) = store.devices.get_mut(id) {
                device.address = address.into();
                device.port = port;
            }
            Ok(())
        })
    }
    pub fn remove(&self, id: &str) -> Result<()> {
        self.update(|store| {
            store.devices.remove(id);
            if store.selected.as_deref() == Some(id) {
                store.selected = store.devices.keys().next().cloned();
            }
            Ok(())
        })
    }
    pub fn select(&self, id: &str) -> Result<()> {
        self.update(|store| {
            if !store.devices.contains_key(id) {
                return Err(Problem::new(
                    "unknown-device",
                    "That TV isn’t paired with this computer",
                ));
            }
            store.selected = Some(id.into());
            Ok(())
        })
    }
}
