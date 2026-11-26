//! Transparent stdio <-> Unix domain socket relay for DSHN Device Bridge MCP.
//! Compiled as a static ARM64 musl ELF (~390 KB, <1 ms startup, <1 MB RAM),
//! replacing spawning a full Node 24 V8 process per ACP session inside bxroot.
//!
//! Because static musl binaries do not load LD_PRELOAD shims (bxroot path translation),
//! this relay tries `DSHN_DEVICE_MCP_SOCK` first and transparently falls back to the
//! canonical host Android path `DSHN_DEVICE_MCP_HOST_SOCK` (direct kernel UDS, zero overhead).

use std::env;
use std::io::{self, Read, Write};
use std::net::Shutdown;
use std::os::unix::net::UnixStream;
use std::process::ExitCode;
use std::thread;

const DEFAULT_CONTAINER_SOCK: &str = "/run/dshn-bridge/device-mcp.sock";
const DEFAULT_HOST_SOCK: &str = "/data/data/dev.dshn.app/files/bridge/device-mcp.sock";
const BUF_SIZE: usize = 64 * 1024;

fn pump<R: Read, W: Write>(mut reader: R, mut writer: W) -> io::Result<()> {
    let mut buf = [0u8; BUF_SIZE];
    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 {
            return Ok(());
        }
        writer.write_all(&buf[..n])?;
        writer.flush()?;
    }
}

fn connect_first_available(candidates: &[String]) -> Result<UnixStream, (String, io::Error)> {
    let mut last_err: Option<(String, io::Error)> = None;
    for path in candidates {
        match UnixStream::connect(path) {
            Ok(stream) => return Ok(stream),
            Err(err) => last_err = Some((path.clone(), err)),
        }
    }
    Err(last_err.unwrap_or_else(|| {
        (
            DEFAULT_CONTAINER_SOCK.to_string(),
            io::Error::new(io::ErrorKind::NotFound, "No UDS socket path configured"),
        )
    }))
}

fn main() -> ExitCode {
    let mut candidates: Vec<String> = Vec::with_capacity(3);
    if let Ok(host) = env::var("DSHN_DEVICE_MCP_HOST_SOCK") {
        let trimmed = host.trim();
        if !trimmed.is_empty() {
            candidates.push(trimmed.to_string());
        }
    }
    if let Ok(sock) = env::var("DSHN_DEVICE_MCP_SOCK") {
        let trimmed = sock.trim();
        if !trimmed.is_empty() && !candidates.iter().any(|c| c == trimmed) {
            candidates.push(trimmed.to_string());
        }
    }
    for fallback in [DEFAULT_HOST_SOCK, DEFAULT_CONTAINER_SOCK] {
        if !candidates.iter().any(|c| c == fallback) {
            candidates.push(fallback.to_string());
        }
    }

    let stream = match connect_first_available(&candidates) {
        Ok(s) => s,
        Err((path, err)) => {
            let _ = writeln!(io::stderr(), "[dshn-mcp-relay] UDS connect error ({path}): {err}");
            return ExitCode::from(1);
        }
    };

    let mut writer_stream = match stream.try_clone() {
        Ok(s) => s,
        Err(err) => {
            let _ = writeln!(io::stderr(), "[dshn-mcp-relay] UDS clone error: {err}");
            return ExitCode::from(1);
        }
    };

    // stdin -> UDS
    let stdin_thread = thread::spawn(move || {
        let stdin = io::stdin();
        let res = pump(stdin.lock(), &mut writer_stream);
        let _ = writer_stream.shutdown(Shutdown::Write);
        res
    });

    // UDS -> stdout (main thread)
    let stdout = io::stdout();
    let out_res = pump(stream, stdout.lock());
    if let Err(err) = &out_res {
        let _ = writeln!(io::stderr(), "[dshn-mcp-relay] UDS read error: {err}");
    }

    drop(stdin_thread);
    if out_res.is_ok() {
        ExitCode::SUCCESS
    } else {
        ExitCode::from(1)
    }
}
