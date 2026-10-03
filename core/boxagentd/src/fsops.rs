//! File operations in the shell-uid context.

use anyhow::{Context, Result};
use boxagent_proto::FileEntry;
use std::os::unix::fs::MetadataExt;

/// Largest payload whose base64 still fits one proto frame (16 MiB) with
/// room for the JSON envelope — bigger reads would kill the connection.
pub const MAX_READ: u64 = 12 * 1024 * 1024 - 64 * 1024;

pub fn read(path: &str) -> Result<Vec<u8>> {
    use std::io::Read;
    let meta = std::fs::metadata(path).with_context(|| format!("stat {path}"))?;
    if meta.is_dir() {
        anyhow::bail!("{path} is a directory");
    }
    if meta.len() > MAX_READ {
        anyhow::bail!("file too large: {} bytes", meta.len());
    }
    // st_size lies for /proc, /sys and char devices (0, or endless like
    // /dev/zero) — bound the actual read, not just the stat.
    let mut buf = Vec::new();
    std::fs::File::open(path)
        .with_context(|| format!("open {path}"))?
        .take(MAX_READ + 1)
        .read_to_end(&mut buf)
        .with_context(|| format!("read {path}"))?;
    if buf.len() as u64 > MAX_READ {
        anyhow::bail!("file too large: over {MAX_READ} bytes");
    }
    Ok(buf)
}

pub fn write(path: &str, data: &[u8]) -> Result<()> {
    if let Some(parent) = std::path::Path::new(path).parent() {
        if !parent.as_os_str().is_empty() && !parent.exists() {
            std::fs::create_dir_all(parent)
                .with_context(|| format!("mkdir {}", parent.display()))?;
        }
    }
    std::fs::write(path, data).with_context(|| format!("write {path}"))
}

pub fn list(path: &str) -> Result<Vec<FileEntry>> {
    let mut out = Vec::new();
    for ent in std::fs::read_dir(path).with_context(|| format!("readdir {path}"))? {
        let ent = ent?;
        let meta = ent.metadata()?;
        out.push(FileEntry {
            name: ent.file_name().to_string_lossy().into_owned(),
            is_dir: meta.is_dir(),
            size: meta.len(),
            mode: meta.mode(),
            mtime: meta.mtime() as u64,
        });
    }
    out.sort_by(|a, b| b.is_dir.cmp(&a.is_dir).then(a.name.cmp(&b.name)));
    Ok(out)
}
