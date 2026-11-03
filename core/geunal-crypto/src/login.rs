//! What a sign-in binds to this device (protocol §13).

use sha2::{Digest, Sha256};

const PROVIDER_NONCE: &[u8] = b"geunal login v1";
const REPLACE: &[u8] = b"geunal replace v1";
/// The device key signs the same words as a guest's recovery key does for a deletion: the two keys never sign for each
/// other, and the server checks each against its own public key.
const DELETE: &[u8] = crate::recovery::DELETE_LABEL;
const NEW_KEY: &[u8] = b"geunal new key v1";

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

/// What the device key signs for `DELETE /me`, over a nonce from `GET /auth/nonce`. The key never leaves the device,
/// so whoever collects a session and a provider proof in the middle still cannot delete the account (threat model
/// §3.7).
pub fn delete_message(nonce: &str) -> Vec<u8> {
    [DELETE, nonce.as_bytes()].concat()
}

/// What the device key signs for "새 키로 시작" (`PUT /me/recovery` with `new_key`), which deletes every backup. Its own
/// words, so a signature made for one of the two irreversible requests never counts for the other.
pub fn new_key_message(nonce: &str) -> Vec<u8> {
    [NEW_KEY, nonce.as_bytes()].concat()
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

    #[test]
    fn delete_and_new_key_messages_prefix_the_nonce_with_their_own_words() {
        assert_eq!(delete_message("n1"), b"geunal delete v1n1");
        assert_eq!(new_key_message("n1"), b"geunal new key v1n1");
    }
}
