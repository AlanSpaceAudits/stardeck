//! Bluetooth LE scope finder: scans for the Seestar service and reads the hotspot credentials
//! with `ble_connect` (GATT layout and messages in PROTOCOL.md, "Bluetooth LE").

use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::{json, Value};
use uuid::Uuid;

pub const SERVICE: Uuid = Uuid::from_u128(0x850e1701_6ecf_49f1_a13f_f5c2d9174f9f);
/// Requests are written here; replies come back as notifications on the same characteristic.
pub const COMMAND: Uuid = Uuid::from_u128(0x850e1702_6ecf_49f1_a13f_f5c2d9174f9f);

pub const CODE_OK: i64 = 0;
/// ble_connect: the user must press RESET on the scope within 60 s.
pub const CODE_PRESS_RESET: i64 = 1003;
/// ble_connect: RESET was not pressed in time.
pub const CODE_RESET_TIMEOUT: i64 = 1008;

pub const SCAN_SECS: u64 = 20;
/// Wait for the ble_connect reply; covers the 60 s RESET window.
const REPLY_WAIT: Duration = Duration::from_secs(75);

/// Model from the advertised manufacturer text.
pub fn model_name(code: &str) -> Option<&'static str> {
    match code {
        "S30" => Some("Seestar S30"),
        "S50" => Some("Seestar S50"),
        "S30P" | "S30P1" => Some("Seestar S30 Pro"),
        "S50P" => Some("Seestar S50 Pro"),
        _ => None,
    }
}

/// The manufacturer field read whole as text, company id bytes included, as the official app does.
pub fn manufacturer_text(company_id: u16, data: &[u8]) -> String {
    let mut bytes = company_id.to_le_bytes().to_vec();
    bytes.extend_from_slice(data);
    String::from_utf8_lossy(&bytes).trim_matches(|c: char| c == '\0' || c.is_whitespace()).to_string()
}

/// First request after notifications are on; the reply carries the hotspot credentials.
pub fn connect_message(client_id: &str, country: &str) -> String {
    json!({"ble_sn": client_id, "ble_method": "ble_connect", "nfc_trig": false, "wifi_country": country}).to_string()
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct BleWifi {
    pub ssid: String,
    pub password: String,
    pub sn: String,
    pub model: String,
    pub submodel: String,
    pub bssid: String,
    /// "2.4G" or "5G"
    pub band: String,
    pub firmware: Option<i64>,
    pub bridge: Option<bool>,
}

pub fn parse_wifi(o: &Value) -> BleWifi {
    let s = |k: &str| o[k].as_str().unwrap_or("").to_string();
    BleWifi {
        ssid: s("ssid"),
        password: s("pwd"),
        sn: s("sn"),
        model: s("model"),
        submodel: s("submodel"),
        bssid: s("bssid"),
        band: s("freq"),
        firmware: o["fw_version"].as_i64(),
        bridge: o["is_bridge"].as_bool(),
    }
}

#[derive(Clone, Debug)]
pub struct BleScope {
    pub name: String,
    pub address: String,
    pub rssi: Option<i16>,
    /// manufacturer text: S30, S50, S30P, S50P, S30P1
    pub model: String,
}

#[derive(Default)]
pub struct BleState {
    pub scanning: bool,
    /// a GATT session is open
    pub connected_to: Option<String>,
    pub scopes: Vec<BleScope>,
    pub status: String,
    pub wifi: Option<BleWifi>,
    pub reset_prompt: bool,
}

pub enum BleCmd {
    Scan,
    StopScan,
    /// read the hotspot credentials from the scope with this address
    Connect(String),
}

/// Starts the Bluetooth thread. `on_change` runs after every state change (for a repaint).
pub fn spawn(client_id: String, state: Arc<Mutex<BleState>>, on_change: impl Fn() + Send + 'static) -> tokio::sync::mpsc::UnboundedSender<BleCmd> {
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    std::thread::spawn(move || {
        let set = |f: &dyn Fn(&mut BleState)| {
            f(&mut state.lock().unwrap());
            on_change();
        };
        let rt = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
            Ok(rt) => rt,
            Err(e) => return set(&|s| s.status = format!("Bluetooth: {e}")),
        };
        if let Err(e) = rt.block_on(run(&client_id, rx, &set)) {
            set(&|s| {
                s.scanning = false;
                s.connected_to = None;
                s.status = format!("Bluetooth: {e}");
            });
        }
    });
    tx
}

type Res<T> = crate::proto::Res<T>;

async fn run(client_id: &str, mut rx: tokio::sync::mpsc::UnboundedReceiver<BleCmd>, set: &dyn Fn(&dyn Fn(&mut BleState))) -> Res<()> {
    use btleplug::api::{Central, Manager as _};
    let manager = btleplug::platform::Manager::new().await?;
    let adapter = manager.adapters().await?.into_iter().next().ok_or("no Bluetooth adapter")?;
    let mut next = None;
    loop {
        let cmd = match next.take() {
            Some(c) => c,
            None => match rx.recv().await {
                Some(c) => c,
                None => return Ok(()),
            },
        };
        match cmd {
            BleCmd::StopScan => {}
            BleCmd::Scan => {
                set(&|s| {
                    s.scanning = true;
                    s.scopes.clear();
                    s.status = "scanning…".into();
                });
                // BlueZ refuses discovery with a service filter on some adapters; filter the results here instead
                let scanned = scan(&adapter, &mut rx, set).await;
                let _ = adapter.stop_scan().await;
                set(&|s| s.scanning = false);
                match scanned {
                    Ok(cmd) => {
                        next = cmd;
                        if next.is_none() {
                            set(&|s| s.status = if s.scopes.is_empty() { "no Seestar found".into() } else { String::new() });
                        }
                    }
                    Err(e) => set(&|s| s.status = format!("scan failed: {e}")),
                }
            }
            BleCmd::Connect(address) => {
                set(&|s| {
                    s.connected_to = Some(address.clone());
                    s.wifi = None;
                    s.reset_prompt = false;
                    s.status = format!("connecting to {address}");
                });
                if let Err(e) = read_hotspot(&adapter, &address, client_id, set).await {
                    set(&|s| s.status = format!("{e}"));
                }
                set(&|s| {
                    s.connected_to = None;
                    s.reset_prompt = false;
                });
            }
        }
    }
}

/// Scans for SCAN_SECS; returns early with a command that arrived meanwhile (other than Scan).
async fn scan(
    adapter: &btleplug::platform::Adapter,
    rx: &mut tokio::sync::mpsc::UnboundedReceiver<BleCmd>,
    set: &dyn Fn(&dyn Fn(&mut BleState)),
) -> Res<Option<BleCmd>> {
    use btleplug::api::{Central, Peripheral as _, ScanFilter};
    adapter.start_scan(ScanFilter::default()).await?;
    let until = tokio::time::Instant::now() + Duration::from_secs(SCAN_SECS);
    while tokio::time::Instant::now() < until {
        tokio::time::sleep(Duration::from_millis(500)).await;
        let mut found = Vec::new();
        for p in adapter.peripherals().await? {
            let Ok(Some(props)) = p.properties().await else { continue };
            // nameless results are dropped, as the official app does
            let name = props.local_name.clone().filter(|n| !n.is_empty());
            if let (true, Some(name)) = (props.services.contains(&SERVICE), name) {
                let model = props.manufacturer_data.iter().next().map(|(id, d)| manufacturer_text(*id, d)).unwrap_or_default();
                found.push(BleScope { name, address: props.address.to_string(), rssi: props.rssi, model });
            }
        }
        found.sort_by_key(|s| std::cmp::Reverse(s.rssi.unwrap_or(i16::MIN)));
        set(&|s| s.scopes = found.clone());
        match rx.try_recv() {
            Ok(BleCmd::Scan) | Err(_) => {}
            Ok(BleCmd::StopScan) => return Ok(None),
            Ok(cmd) => return Ok(Some(cmd)),
        }
    }
    Ok(None)
}

/// GATT session in the official app's order: connect, notifications on the command characteristic,
/// `ble_connect` after 80 ms, then wait for the reply (and a second one after RESET).
async fn read_hotspot(adapter: &btleplug::platform::Adapter, address: &str, client_id: &str, set: &dyn Fn(&dyn Fn(&mut BleState))) -> Res<()> {
    use btleplug::api::{CharPropFlags, Central, Peripheral as _, WriteType};
    use futures_util::StreamExt;
    let mut peripheral = None;
    for p in adapter.peripherals().await? {
        if p.address().to_string() == address {
            peripheral = Some(p);
        }
    }
    let p = peripheral.ok_or("scope no longer in range: scan again")?;
    tokio::time::timeout(Duration::from_secs(20), p.connect()).await.map_err(|_| "connect timed out")??;
    let result = async {
        p.discover_services().await?;
        let c = p.characteristics().into_iter().find(|c| c.uuid == COMMAND).ok_or("Seestar service not found")?;
        let mut notes = p.notifications().await?;
        p.subscribe(&c).await?;
        set(&|s| s.status = "notifications on".into());
        tokio::time::sleep(Duration::from_millis(80)).await;
        let kind = if c.properties.contains(CharPropFlags::WRITE) { WriteType::WithResponse } else { WriteType::WithoutResponse };
        p.write(&c, connect_message(client_id, "").as_bytes(), kind).await?;
        let until = tokio::time::Instant::now() + REPLY_WAIT;
        let mut buf = Vec::new();
        loop {
            let n = tokio::time::timeout_at(until, notes.next()).await.map_err(|_| "no reply to ble_connect")?.ok_or("disconnected")?;
            if n.uuid != COMMAND {
                continue;
            }
            buf.extend_from_slice(&n.value);
            // one JSON object per reply; a reply longer than one notification arrives in parts
            let Ok(v) = serde_json::from_slice::<Value>(&buf) else { continue };
            buf.clear();
            if v["ble_method"] != "ble_connect" {
                continue;
            }
            match v["code"].as_i64().unwrap_or(-1) {
                CODE_OK => {
                    let wifi = parse_wifi(&v["result"]);
                    set(&|s| {
                        s.reset_prompt = false;
                        s.wifi = Some(wifi.clone());
                        s.status = "hotspot credentials received".into();
                    });
                    return Ok(());
                }
                CODE_PRESS_RESET => set(&|s| {
                    s.reset_prompt = true;
                    s.status = "press RESET on the base of the scope within 60 s".into();
                }),
                CODE_RESET_TIMEOUT => return Err("RESET was not pressed in time".into()),
                code => return Err(format!("ble_connect: {}", v["error"].as_str().map_or(format!("code {code}"), str::to_owned)).into()),
            }
        }
    }
    .await;
    let _ = p.disconnect().await;
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_and_parsing() {
        assert_eq!(SERVICE.to_string(), "850e1701-6ecf-49f1-a13f-f5c2d9174f9f");
        // S50 advertisement as seen live: company id 0x3553 ("S5"), data "0" and NULs
        let text = manufacturer_text(0x3553, &[48, 0, 0, 0, 0]);
        assert_eq!(text, "S50");
        assert_eq!(model_name(&text), Some("Seestar S50"));
        let m: Value = serde_json::from_str(&connect_message("id-1", "")).unwrap();
        assert_eq!(m, json!({"ble_sn": "id-1", "ble_method": "ble_connect", "nfc_trig": false, "wifi_country": ""}));
        let w = parse_wifi(&json!({"ssid": "S50_x", "pwd": "pw", "sn": "1", "model": "S50", "freq": "5G", "fw_version": 2846, "is_bridge": false}));
        assert_eq!((w.ssid.as_str(), w.password.as_str(), w.band.as_str(), w.firmware, w.bridge), ("S50_x", "pw", "5G", Some(2846), Some(false)));
    }

    // Live scan: cargo test -- --ignored ble_scan_finds
    #[test]
    #[ignore]
    fn ble_scan_finds_a_scope() {
        let state = Arc::new(Mutex::new(BleState::default()));
        let tx = spawn("test".into(), state.clone(), || {});
        tx.send(BleCmd::Scan).unwrap();
        std::thread::sleep(Duration::from_secs(SCAN_SECS + 3));
        let s = state.lock().unwrap();
        for scope in &s.scopes {
            println!("{} {} {:?} {}", scope.name, scope.address, scope.rssi, scope.model);
        }
        assert!(!s.scopes.is_empty(), "{}", s.status);
    }
}
