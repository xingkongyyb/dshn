//! Single-pass streaming `.tar.gz` / `.tar.zst` archive extractor with hardware-friendly
//! SHA-256 verification, parent-directory deduplication, and direct POSIX `chmod`/`symlink`.

use std::collections::HashSet;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufReader, Read, Seek, SeekFrom, Write};
use std::path::{Component, Path, PathBuf};

use flate2::read::GzDecoder;
use ruzstd::StreamingDecoder;
use serde::Serialize;
use sha2::{Digest, Sha256};
use tar::{Archive, EntryType};

const IO_BUF_SIZE: usize = 256 * 1024;
const PROGRESS_STEP_BYTES: u64 = 256 * 1024;

#[derive(Debug, Serialize)]
pub struct ExtractStats {
    pub entries: u32,
    pub files: u32,
    pub symlinks: u32,
    pub hardlinks: u32,
    pub bytes: u64,
    pub sha256: String,
}

struct DeferredHardlink {
    target_path: PathBuf,
    source_path: PathBuf,
    mode: u32,
}

struct CountingDigestReader<R: Read> {
    inner: R,
    hasher: Sha256,
    bytes_read: u64,
}

impl<R: Read> CountingDigestReader<R> {
    fn new(inner: R) -> Self {
        Self {
            inner,
            hasher: Sha256::new(),
            bytes_read: 0,
        }
    }

    fn finish(self) -> (u64, String) {
        let digest = self.hasher.finalize();
        let mut hex = String::with_capacity(64);
        for b in digest {
            use std::fmt::Write as _;
            let _ = write!(&mut hex, "{b:02x}");
        }
        (self.bytes_read, hex)
    }
}

impl<R: Read> Read for CountingDigestReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let n = self.inner.read(buf)?;
        if n > 0 {
            self.hasher.update(&buf[..n]);
            self.bytes_read += n as u64;
        }
        Ok(n)
    }
}

/// Shared handle allowing us to read compressed byte count while the decoder owns the reader.
struct SharedCountReader<'a, R: Read> {
    inner: &'a mut CountingDigestReader<R>,
}

impl<R: Read> Read for SharedCountReader<'_, R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.inner.read(buf)
    }
}

pub fn extract_tar_slice<F>(
    archive_path: &Path,
    offset_bytes: u64,
    length_bytes: u64,
    dest_dir: &Path,
    strip_components: usize,
    expected_sha256: Option<&str>,
    is_zstd: bool,
    mut on_progress: F,
) -> io::Result<ExtractStats>
where
    F: FnMut(u64, u32),
{
    fs::create_dir_all(dest_dir)?;
    let mut file = File::open(archive_path)?;
    if offset_bytes > 0 {
        file.seek(SeekFrom::Start(offset_bytes))?;
    }
    let limited: Box<dyn Read> = if length_bytes > 0 {
        Box::new(file.take(length_bytes))
    } else {
        Box::new(file)
    };
    let buffered = BufReader::with_capacity(IO_BUF_SIZE, limited);
    let mut counted = CountingDigestReader::new(buffered);

    let mut entries: u32 = 0;
    let mut files: u32 = 0;
    let mut symlinks: u32 = 0;
    let mut hardlinks: u32 = 0;
    let mut deferred_hardlinks: Vec<DeferredHardlink> = Vec::new();
    let mut ensured_dirs: HashSet<PathBuf> = HashSet::with_capacity(4096);
    ensured_dirs.insert(dest_dir.to_path_buf());
    let mut copy_buf = vec![0u8; IO_BUF_SIZE];

    {
        let reader_ref = SharedCountReader { inner: &mut counted };
        if is_zstd {
            let dec = StreamingDecoder::new(reader_ref)
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("zstd init failed: {e}")))?;
            extract_tar_stream(
                dec,
                dest_dir,
                strip_components,
                &mut entries,
                &mut files,
                &mut symlinks,
                &mut hardlinks,
                &mut deferred_hardlinks,
                &mut ensured_dirs,
                &mut copy_buf,
                &mut on_progress,
            )?;
        } else {
            let dec = GzDecoder::new(reader_ref);
            extract_tar_stream(
                dec,
                dest_dir,
                strip_components,
                &mut entries,
                &mut files,
                &mut symlinks,
                &mut hardlinks,
                &mut deferred_hardlinks,
                &mut ensured_dirs,
                &mut copy_buf,
                &mut on_progress,
            )?;
        }
    }

    // Drain any remaining bytes on the compressed stream so SHA-256 matches `sha256sum` exactly.
    while counted.read(&mut copy_buf)? > 0 {}

    // Process deferred hardlinks (emulated as copies because Android SELinux blocks link(2)).
    for hl in deferred_hardlinks {
        if hl.source_path.exists() {
            copy_hardlink_target(&hl.source_path, &hl.target_path, hl.mode, &mut ensured_dirs, &mut copy_buf)?;
            entries += 1;
            hardlinks += 1;
        }
    }

    let (bytes_read, actual_sha256) = counted.finish();
    if let Some(expected) = expected_sha256.map(str::trim).filter(|s| !s.is_empty()) {
        if !actual_sha256.eq_ignore_ascii_case(expected) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("SHA-256 mismatch: expected={expected} actual={actual_sha256}"),
            ));
        }
    }

    on_progress(bytes_read, entries);
    Ok(ExtractStats {
        entries,
        files,
        symlinks,
        hardlinks,
        bytes: bytes_read,
        sha256: actual_sha256,
    })
}

#[allow(clippy::too_many_arguments)]
fn extract_tar_stream<R: Read, F: FnMut(u64, u32)>(
    decoder: R,
    dest_dir: &Path,
    strip_components: usize,
    entries: &mut u32,
    files: &mut u32,
    symlinks: &mut u32,
    hardlinks: &mut u32,
    deferred_hardlinks: &mut Vec<DeferredHardlink>,
    ensured_dirs: &mut HashSet<PathBuf>,
    copy_buf: &mut [u8],
    on_progress: &mut F,
) -> io::Result<()> {
    let mut archive = Archive::new(decoder);
    let mut last_report_entries: u32 = 0;
    let mut approx_uncompressed: u64 = 0;
    let mut last_report_bytes: u64 = 0;

    for item in archive.entries()? {
        let mut entry = item?;
        let entry_type = entry.header().entry_type();
        let raw_mode = entry.header().mode().unwrap_or(0);
        let entry_size = entry.header().size().unwrap_or(0);
        let raw_path = entry.path()?.into_owned();

        let normalized = match normalize_relative_path(&raw_path, strip_components)? {
            Some(p) => p,
            None => continue,
        };
        let target_path = if normalized.as_os_str().is_empty() {
            dest_dir.to_path_buf()
        } else {
            dest_dir.join(&normalized)
        };

        match entry_type {
            EntryType::Directory => {
                if target_path != dest_dir && !is_symlink(&target_path) {
                    fs::create_dir_all(&target_path)?;
                    let dir_mode = (if raw_mode > 0 { raw_mode } else { 0o755 }) | 0o700;
                    set_posix_mode(&target_path, dir_mode);
                    ensured_dirs.insert(target_path);
                }
                *entries += 1;
            }
            EntryType::Regular | EntryType::Continuous => {
                if let Some(parent) = target_path.parent() {
                    ensure_writable_parent(parent, ensured_dirs)?;
                }
                delete_quietly_no_follow(&target_path)?;
                {
                    let mut out = OpenOptions::new()
                        .write(true)
                        .create(true)
                        .truncate(true)
                        .open(&target_path)?;
                    copy_reader(&mut entry, &mut out, copy_buf)?;
                }
                let file_mode = (if raw_mode > 0 { raw_mode } else { 0o644 }) | 0o600;
                set_posix_mode(&target_path, file_mode);
                *entries += 1;
                *files += 1;
                approx_uncompressed += entry_size;
            }
            EntryType::Symlink => {
                if target_path != dest_dir {
                    if let Some(link_name) = entry.link_name()? {
                        if let Some(parent) = target_path.parent() {
                            ensure_writable_parent(parent, ensured_dirs)?;
                        }
                        delete_quietly_no_follow(&target_path)?;
                        create_symlink(&link_name, &target_path)?;
                        *entries += 1;
                        *symlinks += 1;
                    }
                }
            }
            EntryType::Link => {
                if target_path != dest_dir {
                    if let Some(link_name) = entry.link_name()? {
                        if let Some(link_rel) = normalize_relative_path(&link_name, strip_components)? {
                            if !link_rel.as_os_str().is_empty() {
                                let src_path = dest_dir.join(&link_rel);
                                let file_mode = (if raw_mode > 0 { raw_mode } else { 0o644 }) | 0o600;
                                if src_path.exists() && !is_symlink(&src_path) {
                                    copy_hardlink_target(&src_path, &target_path, file_mode, ensured_dirs, copy_buf)?;
                                    *entries += 1;
                                    *hardlinks += 1;
                                } else {
                                    deferred_hardlinks.push(DeferredHardlink {
                                        target_path,
                                        source_path: src_path,
                                        mode: file_mode,
                                    });
                                }
                            }
                        }
                    }
                }
            }
            // Safely skip char/block devices, FIFOs, PAX headers, etc.
            _ => {}
        }

        if approx_uncompressed.saturating_sub(last_report_bytes) >= PROGRESS_STEP_BYTES * 4
            || entries.saturating_sub(last_report_entries) >= 256
        {
            last_report_bytes = approx_uncompressed;
            last_report_entries = *entries;
            // Approximate compressed progress (~1/3 of uncompressed) during stream; exact bytes emitted at finish.
            on_progress(approx_uncompressed / 3, *entries);
        }
    }

    // Drain any trailing zero blocks inside the compressed stream so GZIP CRC32 trailer is verified.
    let mut inner = archive.into_inner();
    while inner.read(copy_buf)? > 0 {}
    Ok(())
}

fn copy_reader<R: Read, W: Write>(reader: &mut R, writer: &mut W, buf: &mut [u8]) -> io::Result<()> {
    loop {
        let n = reader.read(buf)?;
        if n == 0 {
            return Ok(());
        }
        writer.write_all(&buf[..n])?;
    }
}

fn copy_hardlink_target(
    src: &Path,
    dst: &Path,
    mode: u32,
    ensured_dirs: &mut HashSet<PathBuf>,
    buf: &mut [u8],
) -> io::Result<()> {
    if let Some(parent) = dst.parent() {
        ensure_writable_parent(parent, ensured_dirs)?;
    }
    delete_quietly_no_follow(dst)?;
    let mut input = File::open(src)?;
    let mut out = OpenOptions::new().write(true).create(true).truncate(true).open(dst)?;
    copy_reader(&mut input, &mut out, buf)?;
    set_posix_mode(dst, mode);
    Ok(())
}

fn ensure_writable_parent(dir: &Path, ensured_dirs: &mut HashSet<PathBuf>) -> io::Result<()> {
    if ensured_dirs.contains(dir) {
        return Ok(());
    }
    if !dir.exists() {
        fs::create_dir_all(dir)?;
    }
    if !is_symlink(dir) && dir.is_dir() {
        set_posix_mode(dir, 0o755);
        ensured_dirs.insert(dir.to_path_buf());
    }
    Ok(())
}

fn is_symlink(path: &Path) -> bool {
    fs::symlink_metadata(path)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
}

fn delete_quietly_no_follow(path: &Path) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(meta) => {
            if meta.file_type().is_dir() && !meta.file_type().is_symlink() {
                fs::remove_dir_all(path)
            } else {
                fs::remove_file(path)
            }
        }
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e),
    }
}

fn normalize_relative_path(raw: &Path, strip_components: usize) -> io::Result<Option<PathBuf>> {
    let raw_str = raw.to_string_lossy();
    if raw_str.contains('\0') {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Invalid NUL byte in tar entry path",
        ));
    }
    let mut parts: Vec<&str> = Vec::new();
    for comp in raw.components() {
        match comp {
            Component::CurDir | Component::RootDir | Component::Prefix(_) => {}
            Component::ParentDir => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("Path traversal blocked in tar entry: {raw_str}"),
                ));
            }
            Component::Normal(os) => {
                let s = os.to_string_lossy();
                if s == ".." {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        format!("Path traversal blocked in tar entry: {raw_str}"),
                    ));
                }
                if !s.is_empty() && s != "." {
                    // Use the raw slice from Component::Normal safely via PathBuf accumulation below.
                    parts.push(os.to_str().unwrap_or(""));
                }
            }
        }
    }
    if parts.len() < strip_components {
        return Ok(None);
    }
    let kept = &parts[strip_components..];
    if kept.is_empty() {
        return Ok(if strip_components == 0 {
            Some(PathBuf::new())
        } else {
            None
        });
    }
    let mut out = PathBuf::new();
    for seg in kept {
        out.push(seg);
    }
    Ok(Some(out))
}

#[cfg(unix)]
fn set_posix_mode(path: &Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    let clean = mode & 0o777;
    let _ = fs::set_permissions(path, fs::Permissions::from_mode(clean));
}

#[cfg(not(unix))]
fn set_posix_mode(_path: &Path, _mode: u32) {}

#[cfg(unix)]
fn create_symlink(target: &Path, link: &Path) -> io::Result<()> {
    std::os::unix::fs::symlink(target, link)
}

#[cfg(not(unix))]
fn create_symlink(target: &Path, link: &Path) -> io::Result<()> {
    fs::write(link, target.to_string_lossy().as_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_parent_traversal() {
        let err = normalize_relative_path(Path::new("a/../../etc/passwd"), 0).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn strips_components_cleanly() {
        let p = normalize_relative_path(Path::new("node-v24/bin/node"), 1).unwrap().unwrap();
        assert_eq!(p, PathBuf::from("bin/node"));
        assert!(normalize_relative_path(Path::new("node-v24"), 1).unwrap().is_none());
    }
}
