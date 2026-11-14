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
//! {"id":5,"cmd":"start","entry":"app"}     start it now, next to this program (a launcher handing over)
//! {"id":6,"cmd":"shown"}                   this program's window is on screen
//! ```
//!
//! Each program the stub starts gets its own token, so the stub knows who is talking. `start` answers
//! `{"started":true,"pid":n}` when the entry was started, or `{"started":false}` when an update is downloaded and has to be
//! swapped in first: then the entry starts after the running programs have exited, as with `next`.
//!
//! The stub answers each with `{"re":<id>,"ok":true,...}` or `{"re":<id>,"ok":false,"error":"..."}`, and sends events to
//! every connected program as they happen: `{"event":"progress","received":n,"total":m}` and
//! `{"event":"ready","version":"..."}`, `{"event":"shown","entry":"app"}` (a program said `shown`) and
//! `{"event":"exited","entry":"app","code":n,"shown":false}` (a program has exited).

use crate::app::{App, Event, Program};
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

/// Starts listening. Returns the port; each program is given its own token when it is started (`App::register`).
pub fn serve(app: Arc<App>) -> std::io::Result<u16> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))?;
    let port = listener.local_addr()?.port();
    std::thread::spawn(move || {
        for stream in listener.incoming().flatten() {
            let app = app.clone();
            std::thread::spawn(move || {
                if let Err(e) = handle(app, stream) {
                    log!("a connection from the app ended with an error: {e}");
                }
            });
        }
    });
    Ok(port)
}

/// How long a new connection may stay silent before its `hello`.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

/// How long a write to a program may block. A program that does not read must not hold up the stub (or the other
/// programs) for long.
const WRITE_TIMEOUT: Duration = Duration::from_secs(5);

/// How long a `start` waits for the start loop. It answers at once unless it is busy swapping files.
const START_TIMEOUT: Duration = Duration::from_secs(30);

fn handle(app: Arc<App>, stream: TcpStream) -> std::io::Result<()> {
    handle_with(app, stream, HANDSHAKE_TIMEOUT)
}

fn handle_with(app: Arc<App>, stream: TcpStream, handshake_timeout: Duration) -> std::io::Result<()> {
    stream.set_nodelay(true)?;
    // Socket timeouts belong to one handle, not to the socket: after `try_clone` (a duplicated handle on Windows) the
    // two no longer follow each other. So each handle gets its own, and only the reader's matters for reading: the
    // handshake timeout is set on it and lifted on it. Lifting it on the original left the reader on the 10 s limit,
    // which ended the connection whenever a program stayed silent that long (the launcher during a download) and
    // left every later command unread.
    let mut reader = BufReader::new(stream.try_clone()?);
    reader.get_ref().set_read_timeout(Some(handshake_timeout))?;

    let Some(first) = read_message(&mut reader)? else { return Ok(()) };
    let program = match (first["cmd"].as_str(), first["token"].as_str()) {
        (Some("hello"), Some(token)) => app.program(token),
        _ => None,
    };
    let Some(program) = program else {
        return Ok(()); // not a program this stub started (or one that has exited)
    };
    reader.get_ref().set_read_timeout(None)?;
    // The handle the hub writes to is the original one; it is only ever written to.
    stream.set_write_timeout(Some(WRITE_TIMEOUT))?;
    let client = app.hub.add(stream);
    app.on_hello(program.id);
    app.hub.send(client, &reply(&first, app.describe(&program.entry)));

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
            "start" => {
                let app = app.clone();
                let program = program.clone();
                std::thread::spawn(move || {
                    let answer = match message["entry"].as_str() {
                        None => failure(&message, "an entry name is needed"),
                        Some(entry) => match start(&app, &program, entry) {
                            Ok(Some(pid)) => reply(&message, json!({"started": true, "pid": pid})),
                            Ok(None) => reply(&message, json!({"started": false})),
                            Err(e) => failure(&message, &e),
                        },
                    };
                    app.hub.send(client, &answer);
                });
            }
            "shown" => {
                app.on_shown(&program);
                app.hub.send(client, &reply(&message, json!({})));
            }
            "hello" => app.hub.send(client, &reply(&message, app.describe(&program.entry))),
            other => app.hub.send(client, &failure(&message, &format!("unknown command '{other}'"))),
        }
    }
    app.hub.remove(client);
    Ok(())
}

/// Asks the start loop to start `entry` now. See [`Event::Start`].
fn start(app: &App, from: &Program, entry: &str) -> Result<Option<u32>, String> {
    let (reply, answer) = std::sync::mpsc::channel();
    app.send(Event::Start { entry: entry.to_string(), from: from.id, reply });
    answer.recv_timeout(START_TIMEOUT).map_err(|_| "the stub did not get to it in time".to_string())?
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
pub fn new_token() -> String {
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::layout::Layout;
    use crate::manifest::Manifest;
    use std::io::BufReader;
    use std::net::TcpListener;

    fn app() -> Arc<App> {
        let root = std::env::temp_dir().join(format!("jpack-ipc-test-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let manifest = Manifest::parse(
            br#"{"format":1,"name":"Demo","version":"1","parts":{},
                 "launch":{"start":"app","entries":{"app":{"classpath":["app/demo.jar"],"mainClass":"demo.Main"}}}}"#,
        )
        .unwrap();
        let app = Arc::new(App::new(Layout::new(root), manifest, true, None));
        app.register("token".into(), Program { id: 1, entry: "app".into() });
        app
    }

    /// Serves every connection to a fresh port the way the stub does, and returns the port.
    fn serve_on_a_port(app: &Arc<App>) -> u16 {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let app = app.clone();
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let app = app.clone();
                std::thread::spawn(move || handle_with(app, stream, Duration::from_secs(5)));
            }
        });
        port
    }

    /// Connects and says hello with `token`; returns the writer, the reader and the answer to the hello.
    fn connect(port: u16, token: &str) -> (TcpStream, BufReader<TcpStream>, Value) {
        let mut client = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
        client.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let mut answers = BufReader::new(client.try_clone().unwrap());
        send(&mut client, json!({"id": 1, "cmd": "hello", "token": token}));
        let hello = receive(&mut answers);
        (client, answers, hello)
    }

    /// Reads lines until one matches.
    fn receive_until(reader: &mut BufReader<TcpStream>, wanted: impl Fn(&Value) -> bool) -> Value {
        loop {
            let message = receive(reader);
            if wanted(&message) {
                return message;
            }
        }
    }

    /// Each program has its own token: the stub knows which entry is talking, and tells it so.
    #[test]
    fn each_program_is_known_by_its_own_token() {
        let app = app();
        app.register("launcher-token".into(), Program { id: 2, entry: "launcher".into() });
        let port = serve_on_a_port(&app);

        let (_a, _ra, hello_app) = connect(port, "token");
        let (_l, _rl, hello_launcher) = connect(port, "launcher-token");

        assert_eq!(hello_app["entry"], "app");
        assert_eq!(hello_launcher["entry"], "launcher");
        assert!(hello_launcher["features"].as_array().unwrap().contains(&json!("start")));
        assert!(app.said_hello(1) && app.said_hello(2));
    }

    /// The token of a program that has exited opens nothing.
    #[test]
    fn the_token_of_a_program_that_has_exited_is_refused() {
        let app = app();
        app.unregister(1);
        let port = serve_on_a_port(&app);
        let mut client = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
        client.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let mut answers = BufReader::new(client.try_clone().unwrap());
        send(&mut client, json!({"id": 1, "cmd": "hello", "token": "token"}));
        let mut line = String::new();
        assert_eq!(answers.read_line(&mut line).unwrap(), 0, "the connection is closed without an answer: {line}");
    }

    /// A program saying `shown` is heard by the others: the launcher that waits to close.
    #[test]
    fn shown_reaches_every_connected_program() {
        let app = app();
        app.register("launcher-token".into(), Program { id: 2, entry: "launcher".into() });
        let port = serve_on_a_port(&app);
        let (_l, mut launcher, _) = connect(port, "launcher-token");
        let (mut client, mut answers, _) = connect(port, "token");

        send(&mut client, json!({"id": 2, "cmd": "shown"}));
        assert_eq!(receive_until(&mut answers, |m| m["re"] == 2)["ok"], true);

        let event = receive_until(&mut launcher, |m| m["event"] == "shown");
        assert_eq!(event["entry"], "app");
        assert!(app.was_shown(1));
        assert!(!app.was_shown(2));
    }

    /// `start` is handed to the start loop with the name of the program that asked, and its answer goes back.
    #[test]
    fn start_asks_the_start_loop_and_passes_its_answer_on() {
        let app = app();
        let events = app.take_events();
        let port = serve_on_a_port(&app);
        let (mut client, mut answers, _) = connect(port, "token");

        let loop_thread = std::thread::spawn(move || {
            let mut asked = Vec::new();
            for answer in [Ok(Some(4242)), Ok(None), Err("no such entry".to_string())] {
                match events.recv_timeout(Duration::from_secs(5)).unwrap() {
                    Event::Start { entry, from, reply } => {
                        asked.push((entry, from));
                        reply.send(answer).unwrap();
                    }
                    Event::Exited { .. } => panic!("no program exited"),
                }
            }
            asked
        });

        send(&mut client, json!({"id": 2, "cmd": "start", "entry": "app2"}));
        let started = receive_until(&mut answers, |m| m["re"] == 2);
        assert_eq!((started["ok"].clone(), started["started"].clone(), started["pid"].clone()), (json!(true), json!(true), json!(4242)));

        send(&mut client, json!({"id": 3, "cmd": "start", "entry": "app2"}));
        let later = receive_until(&mut answers, |m| m["re"] == 3);
        assert_eq!((later["ok"].clone(), later["started"].clone()), (json!(true), json!(false)));

        send(&mut client, json!({"id": 4, "cmd": "start", "entry": "nope"}));
        let refused = receive_until(&mut answers, |m| m["re"] == 4);
        assert_eq!((refused["ok"].clone(), refused["error"].clone()), (json!(false), json!("no such entry")));

        assert_eq!(loop_thread.join().unwrap(), vec![("app2".to_string(), 1), ("app2".to_string(), 1), ("nope".to_string(), 1)]);
    }

    fn send(stream: &mut TcpStream, message: Value) {
        stream.write_all(format!("{message}\n").as_bytes()).unwrap();
    }

    fn receive(reader: &mut BufReader<TcpStream>) -> Value {
        let mut line = String::new();
        reader.read_line(&mut line).unwrap();
        serde_json::from_str(&line).unwrap()
    }

    /// A program that stays silent for longer than the handshake allows (the launcher during a download) must still be
    /// heard afterwards. Windows sockets used to keep the handshake timeout on the reader's handle after it was lifted
    /// on the original one, which ended the connection and left the next command unanswered.
    #[test]
    fn a_silence_longer_than_the_handshake_timeout_does_not_end_the_connection() {
        let handshake = Duration::from_millis(300);
        let app = app();
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();

        let served = app.clone();
        let server = std::thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            handle_with(served, stream, handshake)
        });

        let mut client = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
        client.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let mut answers = BufReader::new(client.try_clone().unwrap());

        send(&mut client, json!({"id": 1, "cmd": "hello", "token": "token"}));
        assert_eq!(receive(&mut answers)["ok"], true);

        std::thread::sleep(handshake * 4);

        send(&mut client, json!({"id": 2, "cmd": "next", "entry": "app"}));
        let answer = receive(&mut answers);
        assert_eq!(answer["re"], 2);
        assert_eq!(answer["ok"], true);
        assert_eq!(app.next.lock().unwrap().as_deref(), Some("app"));

        drop(answers);
        drop(client);
        assert!(server.join().unwrap().is_ok(), "the connection ended with an error");
    }

    /// A connection that never says hello is still dropped once the handshake time is up.
    #[test]
    fn a_connection_that_never_says_hello_is_dropped() {
        let handshake = Duration::from_millis(200);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let app = app();
        let server = std::thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            handle_with(app, stream, handshake)
        });
        let _silent = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
        assert!(server.join().unwrap().is_err());
    }
}
