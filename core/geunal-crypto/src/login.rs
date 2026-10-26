//! What a sign-in binds to this device (protocol §13).

use sha2::{Digest, Sha256};

const PROVIDER_NONCE: &[u8] = b"geunal login v1";
const REPLACE: &[u8] = b"geunal replace v1";

/// The nonce the app hands to Kakao or Apple: hex(SHA-256(`"geunal login v1" ‖ nonce ‖ signature_key`)), lowercase.
/// The server computes the same from the request and compares it with the token's `nonce` claim, so a provider token
/// that leaks cannot sign in a device with another key.
pub fn provider_nonce(nonce: &str, signature_key: &[u8]) -> String {
    hex::encode(Sha256::digest([PROVIDER_NONCE, nonce.as_bytes(), signature_key].concat()))
}

/// What the device key signs to turn a replacement ticket into a session (`POST /auth/replace`): a leaked ticket
/// alone gets no tokens.
pub fn replace_message(ticket: &str) -> Vec<u8> {
    [REPLACE, ticket.as_bytes()].concat()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn provider_nonce_is_lowercase_hex_of_the_bound_values() {
        let key = [3u8; 32];
        let value = provider_nonce("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", &key);
        // Cross-checked with Python's hashlib.
        assert_eq!(value, "c035909d19b390ccc56185c0628c3e11573a628c15653c124de0dd85e8966d51");
        assert_ne!(provider_nonce("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", &[4u8; 32]), value);
    }

    #[test]
    fn replace_message_prefixes_the_ticket() {
        assert_eq!(replace_message("t1"), b"geunal replace v1t1");
    }
}
