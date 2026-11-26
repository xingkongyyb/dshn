//! Transparent stdio <-> Unix domain socket relay for DSHN Device Bridge MCP.
//! Compiled as a static ARM64 musl ELF (~250 KB, <1 ms startup, <1 MB RAM),
//! replacing spawning a full Node 24 V8 process per ACP session inside bxroot.

use std::env;
use std::io::{self, Read, Write};
use std::net::Shutdown;
use std::os::unix::net::UnixStream;
use std::process::ExitCode;
use std::thread;

const DEFAULT_SOCK: &str = "/run/dshn-bridge/device-mcp.sock";
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

fn main() -> ExitCode {
    let sock_path = env::var("DSHN_DEVICE_MCP_SOCK")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| DEFAULT_SOCK.to_string());

    let stream = match UnixStream::connect(&sock_path) {
        Ok(s) => s,
        Err(err) => {
            let _ = writeln!(io::stderr(), "[dshn-mcp-relay] UDS connect error ({sock_path}): {err}");
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

    // If UDS closed cleanly, exit immediately without blocking on stdin EOF.
    drop(stdin_thread);
    if out_res.is_ok() {
        ExitCode::SUCCESS
    } else {
        ExitCode::from(1)
    }
}
