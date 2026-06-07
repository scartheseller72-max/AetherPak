//! Codec selection + error types shared across the writer and reader.

use thiserror::Error;

/// Container codec. The numeric ids MUST match the Kotlin `CodecType` -> jint
/// mapping in `core:compress` (0 = ZSTD, 1 = ZIP, 2 = SEVEN_ZIP).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Codec {
    Zstd,
    Zip,
    SevenZip,
}

impl Codec {
    /// Map the JNI `jint` codec id to a [`Codec`]. Returns `None` for unknown ids.
    pub fn from_jint(v: i32) -> Option<Codec> {
        match v {
            0 => Some(Codec::Zstd),
            1 => Some(Codec::Zip),
            2 => Some(Codec::SevenZip),
            _ => None,
        }
    }

    /// Resolve the effective compression level. `level == 0` means "default".
    /// zstd default = 9, deflate default = 6. 7z ignores numeric levels here
    /// (sevenz-rust uses its built-in LZMA2 defaults).
    pub fn effective_level(self, level: i32) -> i32 {
        match self {
            Codec::Zstd => {
                if level == 0 {
                    9
                } else {
                    level
                }
            }
            Codec::Zip => {
                if level == 0 {
                    6
                } else {
                    level
                }
            }
            Codec::SevenZip => level,
        }
    }
}

/// Unified error surface for the native core. Mapped to a Java `RuntimeException`
/// (or a sentinel value) at the JNI boundary.
#[derive(Debug, Error)]
pub enum CoreError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),

    #[error("zip error: {0}")]
    Zip(#[from] zip::result::ZipError),

    #[error("7z error: {0}")]
    SevenZip(String),

    #[error("invalid codec id: {0}")]
    InvalidCodec(i32),

    #[error("invalid or null handle")]
    InvalidHandle,

    #[error("invalid argument: {0}")]
    InvalidArgument(String),

    #[error("invalid utf-8 in path")]
    Utf8,

    #[error("operation not valid in current state: {0}")]
    State(String),
}

impl From<sevenz_rust::Error> for CoreError {
    fn from(e: sevenz_rust::Error) -> Self {
        CoreError::SevenZip(e.to_string())
    }
}

pub type CoreResult<T> = Result<T, CoreError>;
