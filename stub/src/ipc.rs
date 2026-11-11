//! The channel between the stub and the Java program it started.
//!
//! The stub listens on a loopback port and gives the program the port and a one-off token in its environment
//! (`JPACK_PORT`, `JPACK_TOKEN`). Both sides send one JSON object per line.
//!
//! The program sends commands, each with an `id`:
//!
//! ```text
//! {"id":1,"cmd":"hello","token":"..."}     must be first
//! {"id":2,"cmd":"check"}                   is there a newer release?
//! {"id":3,"cmd":"download"}                download it (answers when it is ready to be swapped in)
//! {"id":4,"cmd":"next","entry":"app"}      what to start after this program has exited (null: nothing)
//! ```
//!
//! The stub answers each with `{"re":<id>,"ok":true,...}` or `{"re":<id>,"ok":false,"error":"..."}`, and sends events to
//! every connected program as they happen: `{"event":"progress","received":n,"total":m}` and
//! `{"event":"ready","version":"..."}`.

use crate::app::App;
use crate::log;
use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

const MAX_LINE: u64 = 64 * 1024;

/// The programs that are connected right now.
#[derive(Default)]
pub struct Hub {
    clients: Mutex<Vec<(u64, TcpStream)>>,
    ids: AtomicU64,
}

impl Hub {
    fn add(&self, stream: TcpStream) -> u64 {
        let id = self.ids.fetch_add(1, Ordering::SeqCst) + 1;
        self.clients.lock().unwrap().push((id, stream));
        id
    }

    fn remove(&self, id: u64) {
        self.clients.lock().unwrap().retain(|(client, _)| *client != id);
    }

    /// Sends to one program. A program that no longer reads is dropped.
    pub fn send(&self, id: u64, message: &Value) {
        let line = format!("{message}\n");
        let mut clients = self.clients.lock().unwrap();
        let failed = clients.iter_mut().find(|(client, _)| *client == id).is_some_and(|(_, stream)| stream.write_all(line.as_bytes()).is_err());
        if failed {
            clients.retain(|(client, _)| *client != id);
        }
    }

    pub fn broadcast(&self, message: &Value) {
        let line = format!("{message}\n");
        let mut clients = self.clients.lock().unwrap();
        clients.retain_mut(|(_, stream)| stream.write_all(line.as_bytes()).is_ok());
    }
}

/// Starts listening. Returns the port and the token the started program must present.
pub fn serve(app: Arc<App>) -> std::io::Result<(u16, String)> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))?;
    let port = listener.local_addr()?.port();
    let token = new_token();
    let expected = token.clone();
    std::thread::spawn(move || {
        for stream in listener.incoming().flatten() {
            let app = app.clone();
            let expected = expected.clone();
            std::thread::spawn(move || {
                if let Err(e) = handle(app, stream, &expected) {
                    log!("a connection from the app ended with an error: {e}");
                }
            });
        }
    });
    Ok((port, token))
}

fn handle(app: Arc<App>, stream: TcpStream, token: &str) -> std::io::Result<()> {
    stream.set_nodelay(true)?;
    // A program that does not read must not hold up the stub (or the other programs) for long.
    stream.set_write_timeout(Some(Duration::from_secs(5)))?;
    stream.set_read_timeout(Some(Duration::from_secs(10)))?;
    let mut reader = BufReader::new(stream.try_clone()?);

    let Some(first) = read_message(&mut reader)? else { return Ok(()) };
    if first["cmd"] != "hello" || first["token"].as_str() != Some(token) {
        return Ok(()); // not the program this stub started
    }
    stream.set_read_timeout(None)?;
    let client = app.hub.add(stream.try_clone()?);
    app.on_hello();
    app.hub.send(client, &reply(&first, app.describe()));

    while let Some(message) = read_message(&mut reader)? {
        match message["cmd"].as_str().unwrap_or("") {
            "check" => {
                let app = app.clone();
                std::thread::spawn(move || {
                    let answer = match app.check() {
                        Ok(check) => reply(&message, json!({"available": check.available, "version": check.version, "size": check.size, "staged": check.staged})),
                        Err(e) => failure(&message, &e),
                    };
                    app.hub.send(client, &answer);
                });
            }
            "download" => {
                let app = app.clone();
                std::thread::spawn(move || {
                    let answer = match app.download() {
                        Ok(version) => reply(&message, json!({"version": version})),
                        Err(e) => failure(&message, &e),
                    };
                    app.hub.send(client, &answer);
                });
            }
            "next" => {
                let entry = message["entry"].as_str().map(str::to_string);
                log!("after this program: {}", entry.as_deref().unwrap_or("nothing"));
                *app.next.lock().unwrap() = entry;
                app.hub.send(client, &reply(&message, json!({})));
            }
            "hello" => app.hub.send(client, &reply(&message, app.describe())),
            other => app.hub.send(client, &failure(&message, &format!("unknown command '{other}'"))),
        }
    }
    app.hub.remove(client);
    Ok(())
}

/// Reads one line. `None` at the end of the connection, or for a line that is too long or not JSON.
fn read_message(reader: &mut BufReader<TcpStream>) -> std::io::Result<Option<Value>> {
    let mut line = Vec::new();
    let read = reader.by_ref().take(MAX_LINE).read_until(b'\n', &mut line)?;
    if read == 0 || line.last() != Some(&b'\n') {
        return Ok(None);
    }
    Ok(serde_json::from_slice(&line).ok().filter(Value::is_object))
}

fn reply(to: &Value, mut body: Value) -> Value {
    body["re"] = to["id"].clone();
    body["ok"] = Value::Bool(true);
    body
}

fn failure(to: &Value, error: &str) -> Value {
    json!({"re": to["id"], "ok": false, "error": error})
}

/// 256 bits nobody outside this process can guess. The standard library seeds each `RandomState` from the system's
/// random source, which is all a token for a loopback port needs.
fn new_token() -> String {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    let mut token = String::with_capacity(64);
    for round in 0..4u64 {
        let mut hasher = RandomState::new().build_hasher();
        hasher.write_u64(round);
        hasher.write_u32(std::process::id());
        token.push_str(&format!("{:016x}", hasher.finish()));
    }
    token
}
