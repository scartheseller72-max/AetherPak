//! Streaming archive reader with enum dispatch over the three codecs.
//!
//! Contract (mirrors the Kotlin `ArchiveReader`):
//!   while let Some(header) = reader.next_entry() {
//!       loop { let n = reader.read_chunk(buf); if n < 0 { break } ... }
//!   }
//!   reader.close();
//!
//! Payloads are GB-scale, so a file's bytes are NEVER fully buffered in RAM.
//!   * ZSTD : zstd streaming decoder -> tar streaming reader; chunks come straight
//!            off the current tar entry.
//!   * ZIP  : zip 0.6 yields the active entry as a `Read`; chunks stream off it.
//!   * 7z   : sevenz-rust's reader API materializes per-entry, so each entry is
//!            decoded to a temp file on disk (constant RAM) and chunk-read from it.

use std::fs::File;
use std::io::{BufReader, Read};
use std::path::PathBuf;

use tar::{Archive as TarArchive, Entries, EntryType};

use crate::codec::{Codec, CoreResult};

/// Header surfaced to the JNI layer; serialized to JSON for `readNextEntry`.
pub struct EntryHeader {
    pub path: String,
    pub size: i64,
    pub mode: i32,
    pub is_directory: bool,
    pub is_symlink: bool,
    pub link_target: Option<String>,
}

impl EntryHeader {
    /// Hand-rolled JSON so we avoid pulling serde into the native core.
    /// Matches the Kotlin `@Serializable ArchiveEntryHeader` field names.
    pub fn to_json(&self) -> String {
        let link = match &self.link_target {
            Some(t) => format!("\"{}\"", json_escape(t)),
            None => "null".to_string(),
        };
        format!(
            "{{\"path\":\"{}\",\"size\":{},\"mode\":{},\"isDirectory\":{},\"isSymlink\":{},\"linkTarget\":{}}}",
            json_escape(&self.path),
            self.size,
            self.mode,
            self.is_directory,
            self.is_symlink,
            link
        )
    }
}

/// zstd decoder reading the input file.
type ZstdDec = zstd::stream::read::Decoder<'static, BufReader<File>>;

/// Self-referential ZSTD tar state: the `Entries` iterator borrows the boxed
/// `TarArchive`. We keep the archive pinned on the heap and store the iterator
/// (and the current entry) as raw pointers with manually-managed lifetimes.
/// SAFETY: `archive` is never moved or dropped while `entries`/`current` point
/// into it; everything is dropped together in `Drop`.
#[allow(dead_code)]
struct ZstdState {
    archive: *mut TarArchive<ZstdDec>,
    entries: *mut Entries<'static, ZstdDec>,
    current: Option<tar::Entry<'static, ZstdDec>>,
}

impl ZstdState {
    fn new(dec: ZstdDec) -> Self {
        let archive = Box::into_raw(Box::new(TarArchive::new(dec)));
        let it = unsafe { (*archive).entries().expect("tar entries") };
        let entries = Box::into_raw(Box::new(it));
        ZstdState {
            archive,
            entries,
            current: None,
        }
    }
}

impl Drop for ZstdState {
    fn drop(&mut self) {
        self.current = None;
        if !self.entries.is_null() {
            unsafe { drop(Box::from_raw(self.entries)) };
            self.entries = std::ptr::null_mut();
        }
        if !self.archive.is_null() {
            unsafe { drop(Box::from_raw(self.archive)) };
            self.archive = std::ptr::null_mut();
        }
    }
}

/// Self-referential ZIP state: the active `ZipFile` reader borrows the boxed
/// `ZipArchive`. Same controlled-lifetime pattern as `ZstdState` so we can stream
/// the current entry across many `read_chunk` calls without O(n^2) re-seeks.
/// SAFETY: `archive` is pinned on the heap and outlives `current`; both drop
/// together in `Drop` (current first).
#[allow(dead_code)]
struct ZipState {
    archive: *mut zip::ZipArchive<BufReader<File>>,
    index: usize,
    current: Option<zip::read::ZipFile<'static>>,
    remaining: u64,
}

impl ZipState {
    fn new(archive: zip::ZipArchive<BufReader<File>>) -> Self {
        ZipState {
            archive: Box::into_raw(Box::new(archive)),
            index: 0,
            current: None,
            remaining: 0,
        }
    }

    fn arch_mut(&mut self) -> &'static mut zip::ZipArchive<BufReader<File>> {
        unsafe { &mut *self.archive }
    }
}

impl Drop for ZipState {
    fn drop(&mut self) {
        self.current = None;
        if !self.archive.is_null() {
            unsafe { drop(Box::from_raw(self.archive)); }
        }
    }
}

enum ReaderBackend {
    Zstd(ZstdState),
    Zip(ZipState),
    SevenZip {
        /// Pre-extracted (header, temp-file-path) per entry; payload spilled to disk.
        entries: Vec<SevenEntry>,
        index: usize,
        current_file: Option<BufReader<File>>,
    },
}

struct SevenEntry {
    header: EntryHeader,
    /// Temp file holding the decoded payload (None for dir/symlink/empty).
    temp: Option<PathBuf>,
}

/// Opaque handle returned to JNI as a `jlong`.
pub struct ArchiveReaderHandle {
    backend: ReaderBackend,
}

impl ArchiveReaderHandle {
    pub fn open(in_path: &str, codec: Codec) -> CoreResult<Self> {
        let spill_dir = std::path::Path::new(in_path)
            .parent()
            .unwrap_or_else(|| std::path::Path::new("."));
        let backend = match codec {
            Codec::Zstd => {
                let file = File::open(in_path)?;
                let dec = zstd::stream::read::Decoder::new(file)?;
                ReaderBackend::Zstd(ZstdState::new(dec))
            }
            Codec::Zip => {
                let file = File::open(in_path)?;
                let zip = zip::ZipArchive::new(BufReader::new(file))?;
                ReaderBackend::Zip(ZipState::new(zip))
            }
            Codec::SevenZip => {
                let entries = extract_seven(in_path, spill_dir)?;
                ReaderBackend::SevenZip {
                    entries,
                    index: 0,
                    current_file: None,
                }
            }
        };
        Ok(ArchiveReaderHandle { backend })
    }

    /// Advance to the next entry; returns its header or `None` at end of archive.
    pub fn next_entry(&mut self) -> CoreResult<Option<EntryHeader>> {
        match &mut self.backend {
            ReaderBackend::Zstd(state) => {
                // Drop any partially-read current entry.
                state.current = None;
                let it: &mut Entries<'static, ZstdDec> = unsafe { &mut *state.entries };
                match it.next() {
                    None => Ok(None),
                    Some(res) => {
                        let entry = res?;
                        let et = entry.header().entry_type();
                        let is_dir = et == EntryType::Directory;
                        let is_symlink = et == EntryType::Symlink || et == EntryType::Link;
                        let mode = entry.header().mode().unwrap_or(0o644) as i32;
                        let size = entry.header().size().unwrap_or(0) as i64;
                        let path = entry
                            .path()
                            .map(|p| p.to_string_lossy().into_owned())
                            .unwrap_or_default();
                        let link_target = entry
                            .link_name()
                            .ok()
                            .flatten()
                            .map(|p| p.to_string_lossy().into_owned());
                        let header = EntryHeader {
                            path,
                            size,
                            mode,
                            is_directory: is_dir,
                            is_symlink,
                            link_target,
                        };
                        // Keep the entry alive for subsequent read_chunk calls.
                        state.current = Some(entry);
                        Ok(Some(header))
                    }
                }
            }
            ReaderBackend::Zip(state) => {
                // Drop the previous entry borrow before advancing.
                state.current = None;
                if state.index >= unsafe { (*state.archive).len() } {
                    return Ok(None);
                }
                let i = state.index;
                state.index += 1;
                let arch = state.arch_mut();
                let mut file = arch.by_index(i)?;
                let name = file.name().to_string();
                let mode = file.unix_mode().unwrap_or(0o644);
                let is_dir = file.is_dir() || name.ends_with('/');
                let is_symlink = (mode & 0o170000) == 0o120000;
                let size = file.size();
                let link_target = if is_symlink {
                    // Symlink target was stored as the entry body by the writer.
                    let mut s = String::new();
                    file.read_to_string(&mut s).ok();
                    Some(s)
                } else {
                    None
                };
                let header = EntryHeader {
                    path: name,
                    size: size as i64,
                    mode: (mode & 0o7777) as i32,
                    is_directory: is_dir,
                    is_symlink,
                    link_target,
                };
                if is_dir || is_symlink {
                    state.remaining = 0;
                    state.current = None;
                } else {
                    state.remaining = size;
                    // Extend the borrow to 'static; the archive outlives `current`.
                    let f: zip::read::ZipFile<'static> = unsafe { std::mem::transmute(file) };
                    state.current = Some(f);
                }
                Ok(Some(header))
            }
            ReaderBackend::SevenZip {
                entries,
                index,
                current_file,
                ..
            } => {
                *current_file = None;
                if *index >= entries.len() {
                    return Ok(None);
                }
                let se = &entries[*index];
                *index += 1;
                if let Some(tmp) = &se.temp {
                    let f = File::open(tmp)?;
                    *current_file = Some(BufReader::new(f));
                }
                Ok(Some(EntryHeader {
                    path: se.header.path.clone(),
                    size: se.header.size,
                    mode: se.header.mode,
                    is_directory: se.header.is_directory,
                    is_symlink: se.header.is_symlink,
                    link_target: se.header.link_target.clone(),
                }))
            }
        }
    }

    /// Read up to `buf.len()` bytes of the current entry's payload.
    /// Returns the number of bytes read, or -1 when the entry is exhausted.
    pub fn read_chunk(&mut self, buf: &mut [u8]) -> CoreResult<i32> {
        match &mut self.backend {
            ReaderBackend::Zstd(state) => {
                let entry = match state.current.as_mut() {
                    Some(e) => e,
                    None => return Ok(-1),
                };
                let n = entry.read(buf)?;
                if n == 0 {
                    Ok(-1)
                } else {
                    Ok(n as i32)
                }
            }
            ReaderBackend::Zip(state) => {
                let f = match state.current.as_mut() {
                    Some(f) => f,
                    None => return Ok(-1),
                };
                let n = f.read(buf)?;
                if n == 0 {
                    state.current = None;
                    Ok(-1)
                } else {
                    state.remaining = state.remaining.saturating_sub(n as u64);
                    Ok(n as i32)
                }
            }
            ReaderBackend::SevenZip { current_file, .. } => {
                let f = match current_file.as_mut() {
                    Some(f) => f,
                    None => return Ok(-1),
                };
                let n = f.read(buf)?;
                if n == 0 {
                    Ok(-1)
                } else {
                    Ok(n as i32)
                }
            }
        }
    }

    /// Release resources and delete any 7z temp spills.
    pub fn close(self) -> CoreResult<()> {
        if let ReaderBackend::SevenZip { entries, .. } = &self.backend {
            for e in entries {
                if let Some(tmp) = &e.temp {
                    let _ = std::fs::remove_file(tmp);
                }
            }
        }
        Ok(())
    }
}

/// Decode every 7z entry to a temp file once, recording headers in order.
fn extract_seven(in_path: &str, spill_dir: &std::path::Path) -> CoreResult<Vec<SevenEntry>> {
    use std::cell::RefCell;
    use std::io::Write as _;

    let out: RefCell<Vec<SevenEntry>> = RefCell::new(Vec::new());
    let mut sz = sevenz_rust::SevenZReader::open(in_path, sevenz_rust::Password::empty())?;
    sz.for_each_entries(|entry, reader| {
        let name = entry.name().to_string();
        let is_dir = entry.is_directory();
        let mode = 0o644i32;
        // Symlinks were stored as regular bodies by the writer; treat all 7z
        // payloads as regular files on read (restore reapplies modes via manifest).
        if is_dir {
            out.borrow_mut().push(SevenEntry {
                header: EntryHeader {
                    path: name,
                    size: 0,
                    mode,
                    is_directory: true,
                    is_symlink: false,
                    link_target: None,
                },
                temp: None,
            });
            return Ok(true);
        }
        let tmp = seven_temp_path(spill_dir, &name);
        let mut f = std::io::BufWriter::new(
            File::create(&tmp).map_err(|e| sevenz_rust::Error::other(e.to_string()))?,
        );
        let mut total: u64 = 0;
        let mut buf = [0u8; 64 * 1024];
        loop {
            let n = reader
                .read(&mut buf)
                .map_err(|e| sevenz_rust::Error::other(e.to_string()))?;
            if n == 0 {
                break;
            }
            f.write_all(&buf[..n])
                .map_err(|e| sevenz_rust::Error::other(e.to_string()))?;
            total += n as u64;
        }
        f.flush()
            .map_err(|e| sevenz_rust::Error::other(e.to_string()))?;
        out.borrow_mut().push(SevenEntry {
            header: EntryHeader {
                path: name,
                size: total as i64,
                mode,
                is_directory: false,
                is_symlink: false,
                link_target: None,
            },
            temp: Some(tmp),
        });
        Ok(true)
    })?;
    Ok(out.into_inner())
}

fn seven_temp_path(spill_dir: &std::path::Path, name: &str) -> PathBuf {
    let mut dir = spill_dir.to_path_buf();
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let safe: String = name
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .take(48)
        .collect();
    dir.push(format!("aetherpak_read_{}_{}.tmp", stamp, safe));
    dir
}

fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}
