//! The few bytes of an MLSMessage the server reads: version, wire format, group id and epoch (api-server.md §5). Both
//! PublicMessage and PrivateMessage start with `group_id<V>` then `epoch`, so one reader covers commits and messages.
//! This parser is independent of mls-rs on purpose: core/vectors/mls_headers.json pins its output, and the Kotlin
//! parser in the API follows the same twenty lines.

/// `WireFormat` values from RFC 9420 §6.
pub const WIRE_PUBLIC_MESSAGE: u16 = 1;
pub const WIRE_PRIVATE_MESSAGE: u16 = 2;
pub const WIRE_WELCOME: u16 = 3;
pub const WIRE_GROUP_INFO: u16 = 4;
pub const WIRE_KEY_PACKAGE: u16 = 5;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Header {
    pub version: u16,
    pub wire_format: u16,
    pub group_id: Vec<u8>,
    pub epoch: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum HeaderError {
    #[error("the message ends before the header does")]
    Truncated,
    #[error("wire format {0} carries no group id and epoch in its header")]
    NoHeader(u16),
    #[error("the group id length prefix is malformed")]
    BadLength,
}

struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl Reader<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8], HeaderError> {
        let end = self.pos.checked_add(n).ok_or(HeaderError::Truncated)?;
        let out = self.bytes.get(self.pos..end).ok_or(HeaderError::Truncated)?;
        self.pos = end;
        Ok(out)
    }

    fn u16(&mut self) -> Result<u16, HeaderError> {
        let b = self.take(2)?;
        Ok(u16::from_be_bytes([b[0], b[1]]))
    }

    fn u64(&mut self) -> Result<u64, HeaderError> {
        let b = self.take(8)?;
        Ok(u64::from_be_bytes(b.try_into().expect("8 bytes")))
    }

    /// RFC 9420 §2.1.2: the top two bits of the first byte give the prefix length (1, 2 or 4 bytes), the rest the
    /// value. The value 0b11 prefix is invalid.
    fn varint(&mut self) -> Result<usize, HeaderError> {
        let first = self.take(1)?[0];
        let value = match first >> 6 {
            0 => usize::from(first & 0x3f),
            1 => {
                let b = self.take(1)?[0];
                (usize::from(first & 0x3f) << 8) | usize::from(b)
            }
            2 => {
                let b = self.take(3)?;
                (usize::from(first & 0x3f) << 24)
                    | (usize::from(b[0]) << 16)
                    | (usize::from(b[1]) << 8)
                    | usize::from(b[2])
            }
            _ => return Err(HeaderError::BadLength),
        };
        Ok(value)
    }
}

/// Reads the header of a PublicMessage (commit) or PrivateMessage (application message).
pub fn parse(bytes: &[u8]) -> Result<Header, HeaderError> {
    let mut r = Reader { bytes, pos: 0 };
    let version = r.u16()?;
    let wire_format = r.u16()?;
    if wire_format != WIRE_PUBLIC_MESSAGE && wire_format != WIRE_PRIVATE_MESSAGE {
        return Err(HeaderError::NoHeader(wire_format));
    }
    let len = r.varint()?;
    let group_id = r.take(len)?.to_vec();
    let epoch = r.u64()?;
    Ok(Header { version, wire_format, group_id, epoch })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_a_hand_built_private_message_header() {
        let mut bytes = vec![0, 1, 0, 2, 16];
        bytes.extend([0x42; 16]);
        bytes.extend(7u64.to_be_bytes());
        bytes.extend([9, 9, 9]);
        assert_eq!(parse(&bytes).unwrap(), Header { version: 1, wire_format: 2, group_id: vec![0x42; 16], epoch: 7 });
    }

    #[test]
    fn refuses_truncated_and_unrelated_messages() {
        assert_eq!(parse(&[0, 1, 0, 2, 16, 1, 2]), Err(HeaderError::Truncated));
        assert_eq!(parse(&[0, 1, 0, 3, 0]), Err(HeaderError::NoHeader(3)));
        assert_eq!(parse(&[0, 1, 0, 1, 0xc0, 0, 0, 0]), Err(HeaderError::BadLength));
    }

    #[test]
    fn reads_two_byte_length_prefixes() {
        let mut bytes = vec![0, 1, 0, 1, 0x40, 0x10];
        bytes.extend([1; 16]);
        bytes.extend(1u64.to_be_bytes());
        assert_eq!(parse(&bytes).unwrap().group_id, vec![1; 16]);
    }
}
