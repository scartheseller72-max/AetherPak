//! Streaming archive writer with enum dispatch over the three codecs.
//!
//! Contract (mirrors the Kotlin `ArchiveWriter`):
//!   put_entry(header); write(chunk)...; close_entry();   // repeat
//!   close();
//!
//! Payloads are GB-scale, so a file's bytes are NEVER fully buffered in RAM.
//!   * ZSTD : tar header is written from `size`; chunks are spilled to a per-entry
//!            temp file on disk (constant RAM), then streamed into the tar->zstd
//!            pipe via `append_data` at close_entry(). The tar stream is wrapped in
//!            a single multi-frame zstd encoder for the whole container.
//!   * ZIP  : chunks stream straight into the zip writer's active entry (constant RAM).
//!   * 7z   : sevenz-rust adds whole files, so each payload is spilled to a temp file
//!            (constant RAM) and added at close_entry().
//!
//! Spilling to disk (rather than RAM) is what keeps memory flat for GB blobs; the
//! ZIP path streams directly because zip 0.6 exposes a `Write` entry sink.

use std::fs::{File, OpenOptions};
use std::io::{BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};

use tar::{Builder as TarBuilder, EntryType, Header as TarHeader};
use zip::write::FileOptions as ZipFileOptions;
use zip::{CompressionMethod, ZipWriter};

use crate::codec::{Codec, CoreError, CoreResult};

/// Metadata for one entry, decoded from the JNI call.
pub struct EntryMeta {
    pub path: String,
    pub size: i64,
    pub mode: i32,
    pub is_dir: bool,
    pub is_symlink: bool,
    pub link_target: Option<String>,
}

/// zstd encoder wrapping the output file; `tar` writes into it.
type ZstdTar = TarBuilder<zstd::stream::write::Encoder<'static, BufWriter<File>>>;

enum WriterBackend {
    Zstd {
        tar: ZstdTar,
        spill_path: Option<PathBuf>,
        spill: Option<BufWriter<File>>,
        pending: Option<EntryMeta>,
    },
    Zip {
        zip: ZipWriter<BufWriter<File>>,
        level: i32,
        /// True while an entry is open for streaming writes.
        entry_open: bool,
    },
    SevenZip {
        writer: sevenz_rust::SevenZWriter<File>,
        spill_path: Option<PathBuf>,
        spill: Option<BufWriter<File>>,
        pending: Option<EntryMeta>,
    },
}

/// Opaque handle returned to JNI as a `jlong`.
pub struct ArchiveWriterHandle {
    backend: WriterBackend,
}

impl ArchiveWriterHandle {
    pub fn open(out_path: &str, codec: Codec, level: i32) -> CoreResult<Self> {
        let backend = match codec {
            Codec::Zstd => {
                let file = File::create(out_path)?;
                let buf = BufWriter::new(file);
                let lvl = codec.effective_level(level);
                let mut enc = zstd::stream::write::Encoder::new(buf, lvl)?;
                // Long-distance matching helps GB blobs; single-thread keeps the
                // frame layout deterministic for the streaming reader.
                enc.long_distance_matching(true).ok();
                let tar = TarBuilder::new(enc);
                WriterBackend::Zstd {
                    tar,
                    spill_path: None,
                    spill: None,
                    pending: None,
                }
            }
            Codec::Zip => {
                let file = File::create(out_path)?;
                let zip = ZipWriter::new(BufWriter::new(file));
                WriterBackend::Zip {
                    zip,
                    level: codec.effective_level(level),
                    entry_open: false,
                }
            }
            Codec::SevenZip => {
                let file = File::create(out_path)?;
                let writer = sevenz_rust::SevenZWriter::new(file)?;
                WriterBackend::SevenZip {
                    writer,
                    spill_path: None,
                    spill: None,
                    pending: None,
                }
            }
        };
        Ok(ArchiveWriterHandle { backend })
    }

    /// Begin a new entry. For directories / symlinks the header is written
    /// immediately and no payload follows.
    pub fn put_entry(&mut self, meta: EntryMeta) -> CoreResult<()> {
        match &mut self.backend {
            WriterBackend::Zstd {
                tar,
                spill_path,
                spill,
                pending,
            } => {
                if pending.is_some() {
                    return Err(CoreError::State("previous entry not closed".into()));
                }
                if meta.is_dir {
                    let mut h = TarHeader::new_gnu();
                    h.set_size(0);
                    h.set_mode(meta.mode as u32);
                    h.set_entry_type(EntryType::Directory);
                    h.set_cksum();
                    tar.append_data(&mut h, sanitize(&meta.path), std::io::empty())?;
                } else if meta.is_symlink {
                    let mut h = TarHeader::new_gnu();
                    h.set_size(0);
                    h.set_mode(meta.mode as u32);
                    h.set_entry_type(EntryType::Symlink);
                    let target = meta.link_target.as_deref().ok_or_else(|| {
                        CoreError::InvalidArgument("symlink without target".into())
                    })?;
                    h.set_link_name(target)?;
                    h.set_cksum();
                    tar.append_data(&mut h, sanitize(&meta.path), std::io::empty())?;
                } else {
                    // Regular file: spill payload to a temp file, add at close_entry().
                    let tmp = temp_spill_path(&meta.path);
                    let f = OpenOptions::new()
                        .create(true)
                        .write(true)
                        .truncate(true)
                        .open(&tmp)?;
                    *spill_path = Some(tmp);
                    *spill = Some(BufWriter::new(f));
                    *pending = Some(meta);
                }
                Ok(())
            }
            WriterBackend::Zip {
                zip,
                level,
                entry_open,
            } => {
                if *entry_open {
                    return Err(CoreError::State("previous zip entry not closed".into()));
                }
                let opts = ZipFileOptions::default()
                    .unix_permissions(meta.mode as u32)
                    .compression_method(CompressionMethod::Deflated)
                    .compression_level(Some(*level as i32))
                    .large_file(meta.size > 0xFFFF_FFFF);
                if meta.is_dir {
                    zip.add_directory(sanitize_string(&meta.path), opts)?;
                } else if meta.is_symlink {
                    let target = meta.link_target.as_deref().ok_or_else(|| {
                        CoreError::InvalidArgument("symlink without target".into())
                    })?;
                    // zip 0.6 has no symlink helper; store target as the body with the
                    // symlink mode bit set so restore can recognize it.
                    let sym_opts = opts.unix_permissions((meta.mode as u32) | 0o120000);
                    zip.start_file(sanitize_string(&meta.path), sym_opts)?;
                    zip.write_all(target.as_bytes())?;
                } else {
                    zip.start_file(sanitize_string(&meta.path), opts)?;
                    *entry_open = true;
                }
                Ok(())
            }
            WriterBackend::SevenZip {
                writer,
                spill_path,
                spill,
                pending,
            } => {
                if pending.is_some() {
                    return Err(CoreError::State("previous 7z entry not closed".into()));
                }
                if meta.is_dir || meta.is_symlink {
                    let body: Vec<u8> = if meta.is_symlink {
                        meta.link_target
                            .as_deref()
                            .ok_or_else(|| {
                                CoreError::InvalidArgument("symlink without target".into())
                            })?
                            .as_bytes()
                            .to_vec()
                    } else {
                        Vec::new()
                    };
                    let entry = make_seven_entry(&sanitize_string(&meta.path), meta.is_dir);
                    writer.push_archive_entry(entry, Some(std::io::Cursor::new(body)))?;
                } else {
                    let tmp = temp_spill_path(&meta.path);
                    let f = OpenOptions::new()
                        .create(true)
                        .write(true)
                        .truncate(true)
                        .open(&tmp)?;
                    *spill_path = Some(tmp);
                    *spill = Some(BufWriter::new(f));
                    *pending = Some(meta);
                }
                Ok(())
            }
        }
    }

    /// Append a chunk of the current entry's payload. `len` must be <= buf.len().
    pub fn write_chunk(&mut self, buf: &[u8], len: usize) -> CoreResult<()> {
        let data = &buf[..len.min(buf.len())];
        match &mut self.backend {
            WriterBackend::Zstd { spill, .. } => {
                let s = spill
                    .as_mut()
                    .ok_or_else(|| CoreError::State("no open zstd entry".into()))?;
                s.write_all(data)?;
                Ok(())
            }
            WriterBackend::Zip {
                zip, entry_open, ..
            } => {
                if !*entry_open {
                    return Err(CoreError::State("no open zip entry".into()));
                }
                zip.write_all(data)?;
                Ok(())
            }
            WriterBackend::SevenZip { spill, .. } => {
                let s = spill
                    .as_mut()
                    .ok_or_else(|| CoreError::State("no open 7z spill".into()))?;
                s.write_all(data)?;
                Ok(())
            }
        }
    }

    /// Finish the current entry's payload.
    pub fn close_entry(&mut self) -> CoreResult<()> {
        match &mut self.backend {
            WriterBackend::Zstd {
                tar,
                spill_path,
                spill,
                pending,
            } => {
                if let Some(mut s) = spill.take() {
                    s.flush()?;
                    drop(s);
                    let path = spill_path
                        .take()
                        .ok_or_else(|| CoreError::State("missing zstd spill path".into()))?;
                    let meta = pending
                        .take()
                        .ok_or_else(|| CoreError::State("missing zstd entry meta".into()))?;
                    let actual = std::fs::metadata(&path)?.len();
                    let mut h = TarHeader::new_gnu();
                    h.set_size(actual);
                    h.set_mode(meta.mode as u32);
                    h.set_entry_type(EntryType::Regular);
                    h.set_cksum();
                    let f = File::open(&path)?;
                    let reader = BufReader::new(f);
                    tar.append_data(&mut h, sanitize(&meta.path), reader)?;
                    let _ = std::fs::remove_file(&path);
                }
                Ok(())
            }
            WriterBackend::Zip { entry_open, .. } => {
                // zip flushes the active entry when the next starts or on close;
                // mark it closed so the next put_entry is accepted.
                *entry_open = false;
                Ok(())
            }
            WriterBackend::SevenZip {
                writer,
                spill_path,
                spill,
                pending,
            } => {
                if let Some(mut s) = spill.take() {
                    s.flush()?;
                    drop(s);
                    let path = spill_path
                        .take()
                        .ok_or_else(|| CoreError::State("missing 7z spill path".into()))?;
                    let meta = pending
                        .take()
                        .ok_or_else(|| CoreError::State("missing 7z entry meta".into()))?;
                    let f = File::open(&path)?;
                    let entry = make_seven_entry(&sanitize_string(&meta.path), false);
                    writer.push_archive_entry(entry, Some(f))?;
                    let _ = std::fs::remove_file(&path);
                }
                Ok(())
            }
        }
    }

    /// Finalize the container and flush all buffers.
    pub fn close(self) -> CoreResult<()> {
        match self.backend {
            WriterBackend::Zstd { tar, .. } => {
                // Finish tar (writes trailing zero blocks), then finish the zstd frame.
                let enc = tar.into_inner()?;
                let buf = enc.finish()?;
                let mut inner = buf.into_inner().map_err(|e| {
                    CoreError::Io(std::io::Error::new(
                        std::io::ErrorKind::Other,
                        e.to_string(),
                    ))
                })?;
                inner.flush()?;
                Ok(())
            }
            WriterBackend::Zip { mut zip, .. } => {
                let buf = zip.finish()?;
                let mut inner = buf.into_inner().map_err(|e| {
                    CoreError::Io(std::io::Error::new(
                        std::io::ErrorKind::Other,
                        e.to_string(),
                    ))
                })?;
                inner.flush()?;
                Ok(())
            }
            WriterBackend::SevenZip { writer, .. } => {
                writer.finish()?;
                Ok(())
            }
        }
    }
}

/// Construct a 7z entry with the given name. Built field-by-field to stay
/// compatible across sevenz-rust 0.6.x (which exposes a plain default-able struct).
fn make_seven_entry(name: &str, is_dir: bool) -> sevenz_rust::SevenZArchiveEntry {
    let mut e = sevenz_rust::SevenZArchiveEntry::default();
    e.name = name.to_string();
    e.has_stream = !is_dir;
    e.is_directory = is_dir;
    e
}

/// Strip leading slashes / `..` so archive paths stay relative and safe.
fn sanitize(path: &str) -> PathBuf {
    PathBuf::from(sanitize_string(path))
}

fn sanitize_string(path: &str) -> String {
    let trimmed = path.trim_start_matches('/');
    let parts: Vec<&str> = trimmed
        .split('/')
        .filter(|c| !c.is_empty() && *c != "." && *c != "..")
        .collect();
    parts.join("/")
}

/// Build a unique temp spill path for an entry payload.
fn temp_spill_path(path: &str) -> PathBuf {
    let mut dir = std::env::temp_dir();
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let safe: String = path
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .take(48)
        .collect();
    dir.push(format!("aetherpak_spill_{}_{}.tmp", stamp, safe));
    let _ = Path::new(&dir);
    dir
}
