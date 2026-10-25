//! One rustls configuration for HTTPS and the WebSocket: the ring provider and the bundled Mozilla roots
//! (docs/adr/0002-http-tls-stack.md). The apps ship with the core, so a root change ships like any other update, and
//! no platform trust store or JNI initialisation is involved.

use std::sync::Arc;

use rustls::ClientConfig;

pub fn client_config() -> Arc<ClientConfig> {
    let roots = rustls::RootCertStore { roots: webpki_roots::TLS_SERVER_ROOTS.to_vec() };
    let config = ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .expect("ring supports TLS 1.2 and 1.3")
        .with_root_certificates(roots)
        .with_no_client_auth();
    Arc::new(config)
}
