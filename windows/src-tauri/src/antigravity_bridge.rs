use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use serde_json::{json, Value};
use tauri::{AppHandle, Emitter};
use tokio::time::sleep;

use crate::chat::ChatReply;
use crate::island::WINDOW_LABEL;
use crate::log;

const BRIDGE_URL: &str = "http://127.0.0.1:9335";
static BRIDGE_ACTIVE: AtomicBool = AtomicBool::new(false);

/// Sends chat message to Antigravity IDE via the local CDP bridge
pub async fn send_chat(text: String) -> Result<ChatReply, String> {
    ensure_bridge_running();
    let client = reqwest::Client::new();
    let res = client
        .post(format!("{BRIDGE_URL}/chat"))
        .json(&json!({ "text": text }))
        .timeout(Duration::from_secs(60))
        .send()
        .await
        .map_err(|e| format!("Could not reach Antigravity bridge: {e}"))?;

    if !res.status().is_success() {
        let err_body: Value = res.json().await.unwrap_or_default();
        let msg = err_body
            .get("error")
            .and_then(Value::as_str)
            .unwrap_or("Failed to send message to Antigravity IDE");
        return Err(msg.to_string());
    }

    let body: Value = res.json().await.unwrap_or_default();
    let reply_text = body.get("text")
        .or_else(|| body.get("message"))
        .and_then(Value::as_str)
        .unwrap_or("Pesan terkirim ke Antigravity IDE.")
        .to_string();

    Ok(ChatReply {
        text: reply_text,
    })
}

/// Forwards Allow/Deny decision to the local CDP bridge
pub fn decide(request_id: &str, decision: &str) {
    let id = request_id.to_string();
    let dec = if decision == "allow" || decision == "always" { "allow" } else { "deny" };
    log::line(format!("antigravity_bridge: decision for {id} -> {dec}"));

    tokio::spawn(async move {
        let client = reqwest::Client::new();
        let _ = client
            .post(format!("{BRIDGE_URL}/decide"))
            .json(&json!({ "id": id, "decision": dec }))
            .timeout(Duration::from_secs(5))
            .send()
            .await;
    });
}

/// Spawns the background poller that checks for pending approvals in Antigravity IDE
pub fn start(app: AppHandle) {
    if BRIDGE_ACTIVE.swap(true, Ordering::SeqCst) {
        return; // Already started
    }

    tauri::async_runtime::spawn(async move {
        ensure_bridge_running();
        let client = reqwest::Client::new();
        let mut last_id = String::new();

        loop {
            sleep(Duration::from_millis(1500)).await;

            let Ok(res) = client
                .get(format!("{BRIDGE_URL}/pending"))
                .timeout(Duration::from_millis(1200))
                .send()
                .await
            else {
                continue;
            };

            let Ok(val) = res.json::<Value>().await else { continue };
            let pending = val.get("pending");

            if let Some(item) = pending.filter(|v| !v.is_null()) {
                let id = item.get("id").and_then(Value::as_str).unwrap_or("");
                let cmd = item.get("command").and_then(Value::as_str).unwrap_or("Action requested");

                if !id.is_empty() && id != last_id {
                    last_id = id.to_string();
                    log::line(format!("antigravity_bridge: new approval {id} -> {cmd}"));

                    // Emit to Coucou Island frontend
                    let hook_payload = json!({
                        "hook_event_name": "PermissionRequest",
                        "session_id": "antigravity-ide",
                        "coucou_agent": "antigravity",
                        "tool_name": "Antigravity Tool",
                        "tool_input": {
                            "command": cmd
                        },
                        "request_id": id
                    });

                    let _ = app.emit_to(WINDOW_LABEL, "hook", hook_payload);
                }
            } else if !last_id.is_empty() {
                // Approval was handled or cleared
                last_id.clear();
            }
        }
    });
}

/// Ensures the Node bridge server is spawned if not already running
fn ensure_bridge_running() {
    let addr = "127.0.0.1:9335".parse().unwrap();
    if std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(300)).is_ok() {
        return; // Port is already responding
    }

    // Check candidate locations for bridge/server.js
    let mut candidate_dirs = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            candidate_dirs.push(parent.join("bridge"));
        }
    }
    if let Ok(local_app_data) = std::env::var("LOCALAPPDATA") {
        candidate_dirs.push(std::path::PathBuf::from(local_app_data).join("Coucou").join("bridge"));
    }
    if let Ok(user_profile) = std::env::var("USERPROFILE") {
        candidate_dirs.push(std::path::PathBuf::from(user_profile).join("Documents").join("coucou").join("windows").join("bridge"));
    }
    if let Ok(cwd) = std::env::current_dir() {
        candidate_dirs.push(cwd.join("bridge"));
        candidate_dirs.push(cwd.join("windows").join("bridge"));
    }

    for bridge_dir in candidate_dirs {
        let server_script = bridge_dir.join("server.js");
        if server_script.exists() {
            log::line(format!("antigravity_bridge: launching node server.js from {}", bridge_dir.display()));
            #[cfg(windows)]
            {
                use std::os::windows::process::CommandExt;
                const CREATE_NO_WINDOW: u32 = 0x08000000;
                let _ = std::process::Command::new("node")
                    .arg("server.js")
                    .current_dir(&bridge_dir)
                    .creation_flags(CREATE_NO_WINDOW)
                    .spawn();
            }
            #[cfg(not(windows))]
            {
                let _ = std::process::Command::new("node")
                    .arg("server.js")
                    .current_dir(&bridge_dir)
                    .spawn();
            }
            return;
        }
    }
}
