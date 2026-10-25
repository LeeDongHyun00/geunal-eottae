//! One `MlsClient` per device: its signing key and credential, with group state in the store's SQLCipher file. Every
//! operation here is synchronous; mls-rs runs without its async feature.

use std::path::Path;

use mls_rs::client_builder::{
    BaseConfig, WithCryptoProvider, WithGroupStateStorage, WithIdentityProvider, WithKeyPackageRepo, WithMlsRules,
};
use mls_rs::error::MlsError;
use mls_rs::group::{CommitEffect, ReceivedMessage};
use mls_rs::identity::SigningIdentity;
use mls_rs::identity::basic::{BasicCredential, BasicIdentityProvider};
use mls_rs::mls_rules::{CommitOptions, DefaultMlsRules};
use mls_rs::{CipherSuite, CipherSuiteProvider, Client, CryptoProvider, ExtensionList, Group, MlsMessage};
use mls_rs_crypto_rustcrypto::RustCryptoProvider;
use mls_rs_provider_sqlite::SqLiteDataStorageEngine;
use mls_rs_provider_sqlite::connection_strategy::{
    CipheredConnectionStrategy, FileConnectionStrategy, SqlCipherConfig, SqlCipherKey,
};
use mls_rs_provider_sqlite::storage::{SqLiteGroupStateStorage, SqLiteKeyPackageStorage};

use crate::Error;

/// MLS_128_DHKEMX25519_CHACHA20POLY1305_SHA256_Ed25519, 0x0003 (protocol §2). The only suite this core speaks.
pub const SUITE: CipherSuite = CipherSuite::CURVE25519_CHACHA;

type Config = WithMlsRules<
    DefaultMlsRules,
    WithKeyPackageRepo<
        SqLiteKeyPackageStorage,
        WithGroupStateStorage<
            SqLiteGroupStateStorage,
            WithIdentityProvider<BasicIdentityProvider, WithCryptoProvider<RustCryptoProvider, BaseConfig>>,
        >,
    >,
>;

/// Who a leaf belongs to: the Basic credential carries `"{user_id}:{device_id}"` (protocol §2).
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Identity {
    pub user_id: String,
    pub device_id: String,
}

impl Identity {
    pub fn new(user_id: impl Into<String>, device_id: impl Into<String>) -> Self {
        Self { user_id: user_id.into(), device_id: device_id.into() }
    }

    pub fn credential_bytes(&self) -> Vec<u8> {
        format!("{}:{}", self.user_id, self.device_id).into_bytes()
    }

    pub fn from_credential(bytes: &[u8]) -> Result<Self, Error> {
        let text = std::str::from_utf8(bytes).map_err(|_| Error::BadCredential)?;
        let (user_id, device_id) = text.split_once(':').ok_or(Error::BadCredential)?;
        if user_id.is_empty() || device_id.is_empty() || device_id.contains(':') {
            return Err(Error::BadCredential);
        }
        Ok(Self::new(user_id, device_id))
    }

    fn from_signing_identity(identity: &SigningIdentity) -> Result<Self, Error> {
        let basic = identity.credential.as_basic().ok_or(Error::BadCredential)?;
        Self::from_credential(basic.identifier())
    }
}

/// A member of the roster.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemberInfo {
    pub index: u32,
    pub identity: Identity,
    /// Ed25519 public key, what `GET /users/{id}/devices` lists (protocol §2).
    pub signature_key: Vec<u8>,
}

/// What a key package says about the device it belongs to, checked against the server's device list before an Add.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KeyPackageInfo {
    pub identity: Identity,
    pub signature_key: Vec<u8>,
}

/// A commit ready for `POST /groups/{id}/commits`, built but not yet applied: the server may refuse it with 409.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommitBundle {
    pub commit: Vec<u8>,
    /// One Welcome for all added devices, if any were added.
    pub welcome: Option<Vec<u8>>,
    /// GroupInfo of the new epoch, for a member's own device to rejoin by external commit.
    pub group_info: Vec<u8>,
}

/// What processing an incoming log item produced.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Received {
    Application {
        sender: Identity,
        data: Vec<u8>,
    },
    Commit {
        epoch: u64,
        removed_me: bool,
    },
    /// A proposal or other control message that changes nothing by itself.
    Other,
}

pub struct MlsClient {
    client: Client<Config>,
    identity: Identity,
    signature_key: Vec<u8>,
    signer: mls_rs::crypto::SignatureSecretKey,
}

fn crypto_provider() -> RustCryptoProvider {
    RustCryptoProvider::with_enabled_cipher_suites(vec![SUITE])
}

impl MlsClient {
    /// A fresh Ed25519 key pair for a new device: (secret, public). The secret goes to the app's SecretStore.
    pub fn generate_signing_key() -> Result<(Vec<u8>, Vec<u8>), Error> {
        let suite = crypto_provider().cipher_suite_provider(SUITE).ok_or(Error::WrongCipherSuite(SUITE))?;
        let (secret, public) = suite.signature_key_generate().map_err(|e| Error::Crypto(e.to_string()))?;
        Ok((secret.as_bytes().to_vec(), public.as_bytes().to_vec()))
    }

    /// The Ed25519 public key of a signing secret, for registering the device before any client exists.
    pub fn public_key_of(signing_secret: &[u8]) -> Result<Vec<u8>, Error> {
        let suite = crypto_provider().cipher_suite_provider(SUITE).ok_or(Error::WrongCipherSuite(SUITE))?;
        let signer = mls_rs::crypto::SignatureSecretKey::new_slice(signing_secret);
        let public = suite.signature_key_derive_public(&signer).map_err(|e| Error::Crypto(e.to_string()))?;
        Ok(public.as_bytes().to_vec())
    }

    /// Signs `data` with a signing secret: the registration proof over the server's nonce (api-server.md §4), made
    /// before the device has an identity to open a client with.
    pub fn sign_with(signing_secret: &[u8], data: &[u8]) -> Result<Vec<u8>, Error> {
        let suite = crypto_provider().cipher_suite_provider(SUITE).ok_or(Error::WrongCipherSuite(SUITE))?;
        let signer = mls_rs::crypto::SignatureSecretKey::new_slice(signing_secret);
        suite.sign(&signer, data).map_err(|e| Error::Crypto(e.to_string()))
    }

    /// Verifies an Ed25519 signature made by [`MlsClient::sign`]; what the server does with the registration proof.
    pub fn verify(public_key: &[u8], data: &[u8], signature: &[u8]) -> Result<bool, Error> {
        let suite = crypto_provider().cipher_suite_provider(SUITE).ok_or(Error::WrongCipherSuite(SUITE))?;
        let public = mls_rs::crypto::SignaturePublicKey::new_slice(public_key);
        Ok(suite.verify(&public, signature, data).is_ok())
    }

    /// Opens the client over the store's database file (`db_key` is the SQLCipher raw key) with the device's signing
    /// secret from the SecretStore.
    pub fn open(db_path: &Path, db_key: &[u8; 32], identity: Identity, signing_secret: &[u8]) -> Result<Self, Error> {
        let engine = SqLiteDataStorageEngine::new(CipheredConnectionStrategy::new(
            FileConnectionStrategy::new(db_path),
            SqlCipherConfig::new(SqlCipherKey::RawKey(*db_key)),
        ))?;
        let crypto = crypto_provider();
        let suite = crypto.cipher_suite_provider(SUITE).ok_or(Error::WrongCipherSuite(SUITE))?;
        let signer = mls_rs::crypto::SignatureSecretKey::new_slice(signing_secret);
        let public = suite.signature_key_derive_public(&signer).map_err(|e| Error::Crypto(e.to_string()))?;
        let credential = BasicCredential::new(identity.credential_bytes()).into_credential();
        // Commits stay PublicMessages (the default) so the server can read their header and, later, validate them.
        // allow_external_commit makes every commit carry the next epoch's GroupInfo for self-rejoin (protocol §2).
        let rules = DefaultMlsRules::new().with_commit_options(
            CommitOptions::new().with_ratchet_tree_extension(true).with_allow_external_commit(true),
        );
        let client = Client::builder()
            .crypto_provider(crypto)
            .identity_provider(BasicIdentityProvider)
            .group_state_storage(engine.group_state_storage()?)
            .key_package_repo(engine.key_package_storage()?)
            .mls_rules(rules)
            .signing_identity(SigningIdentity::new(credential, public.clone()), signer.clone(), SUITE)
            .build();
        Ok(Self { client, identity, signature_key: public.as_bytes().to_vec(), signer })
    }

    pub fn identity(&self) -> &Identity {
        &self.identity
    }

    /// The device's Ed25519 public key as registered with the server.
    pub fn signature_key(&self) -> &[u8] {
        &self.signature_key
    }

    /// Signs `data` with the device key: the proof over the server's nonce at registration (api-server.md §4).
    pub fn sign(&self, data: &[u8]) -> Result<Vec<u8>, Error> {
        Self::sign_with(self.signer.as_bytes(), data)
    }

    /// A single-use key package for `POST /me/key-packages`.
    pub fn key_package(&self) -> Result<Vec<u8>, Error> {
        let message =
            self.client.generate_key_package_message(ExtensionList::default(), ExtensionList::default(), None)?;
        Ok(message.to_bytes()?)
    }

    /// Reads a key package before adding its device: who it belongs to and its signature key, refusing any suite but
    /// 0x0003.
    pub fn inspect_key_package(bytes: &[u8]) -> Result<KeyPackageInfo, Error> {
        let message = MlsMessage::from_bytes(bytes)?;
        let package = message.as_key_package().ok_or(Error::WrongMessageType("key package"))?;
        if package.cipher_suite() != SUITE {
            return Err(Error::WrongCipherSuite(package.cipher_suite()));
        }
        let identity = package.signing_identity();
        Ok(KeyPackageInfo {
            identity: Identity::from_signing_identity(identity)?,
            signature_key: identity.signature_key.as_bytes().to_vec(),
        })
    }

    /// Creates the group with the server's 16-byte group UUID as MLS group id and returns it with the first GroupInfo
    /// for `POST /groups`.
    pub fn create_group(&self, group_id: &[u8]) -> Result<(MlsGroup, Vec<u8>), Error> {
        if group_id.len() != 16 {
            return Err(Error::BadGroupId);
        }
        let mut group = self.client.create_group_with_id(
            group_id.to_vec(),
            ExtensionList::default(),
            ExtensionList::default(),
            None,
        )?;
        let info = group.group_info_message_allowing_ext_commit(true)?.to_bytes()?;
        group.write_to_storage()?;
        Ok((MlsGroup { inner: group }, info))
    }

    /// The stored state of a group, if this device has one.
    pub fn load_group(&self, group_id: &[u8]) -> Result<Option<MlsGroup>, Error> {
        match self.client.load_group(group_id) {
            Ok(group) => Ok(Some(MlsGroup { inner: group })),
            Err(MlsError::GroupNotFound) => Ok(None),
            Err(e) => Err(e.into()),
        }
    }

    /// Joins from a Welcome addressed to this device.
    pub fn join_welcome(&self, welcome: &[u8]) -> Result<MlsGroup, Error> {
        let message = MlsMessage::from_bytes(welcome)?;
        let (mut group, _) = self.client.join_group(None, &message, None)?;
        if group.cipher_suite() != SUITE {
            return Err(Error::WrongCipherSuite(group.cipher_suite()));
        }
        group.write_to_storage()?;
        Ok(MlsGroup { inner: group })
    }

    /// Rejoins by external commit from the server's GroupInfo, removing this device's previous leaf when it still has
    /// one (protocol §2). The commit must still be accepted by the server before the new group is used.
    pub fn external_join(&self, group_info: &[u8], previous_leaf: Option<u32>) -> Result<(MlsGroup, Vec<u8>), Error> {
        let message = MlsMessage::from_bytes(group_info)?;
        let info = message.as_group_info().ok_or(Error::WrongMessageType("group info"))?;
        if info.group_context().cipher_suite != SUITE {
            return Err(Error::WrongCipherSuite(info.group_context().cipher_suite));
        }
        let mut builder = self.client.external_commit_builder()?;
        if let Some(index) = previous_leaf {
            builder = builder.with_removal(index);
        }
        let (group, commit) = builder.build(message)?;
        Ok((MlsGroup { inner: group }, commit.to_bytes()?))
    }

    /// Forgets a group's MLS state: after `cursor_expired` the device rejoins with a fresh leaf (protocol §6).
    pub fn forget_group(&self, group_id: &[u8]) -> Result<(), Error> {
        Ok(self.client.group_state_storage().delete_group(group_id)?)
    }
}

pub struct MlsGroup {
    inner: Group<Config>,
}

impl MlsGroup {
    pub fn group_id(&self) -> &[u8] {
        self.inner.group_id()
    }

    pub fn epoch(&self) -> u64 {
        self.inner.current_epoch()
    }

    pub fn my_index(&self) -> u32 {
        self.inner.current_member_index()
    }

    pub fn roster(&self) -> Result<Vec<MemberInfo>, Error> {
        self.inner
            .roster()
            .members_iter()
            .map(|m| {
                Ok(MemberInfo {
                    index: m.index,
                    identity: Identity::from_signing_identity(&m.signing_identity)?,
                    signature_key: m.signing_identity.signature_key.as_bytes().to_vec(),
                })
            })
            .collect()
    }

    /// Persists the state. Called after every change that must survive the process.
    pub fn save(&mut self) -> Result<(), Error> {
        Ok(self.inner.write_to_storage()?)
    }

    /// An Add commit for the given key packages, with one Welcome for all of them. Each package is inspected first;
    /// the caller has already matched identities against the server's device list.
    pub fn add_members(&mut self, key_packages: &[Vec<u8>]) -> Result<CommitBundle, Error> {
        let mut builder = self.inner.commit_builder();
        for bytes in key_packages {
            MlsClient::inspect_key_package(bytes)?;
            builder = builder.add_member(MlsMessage::from_bytes(bytes)?)?;
        }
        Self::bundle(builder.build()?)
    }

    pub fn remove_members(&mut self, indexes: &[u32]) -> Result<CommitBundle, Error> {
        let mut builder = self.inner.commit_builder();
        for index in indexes {
            builder = builder.remove_member(*index)?;
        }
        Self::bundle(builder.build()?)
    }

    /// An empty commit that refreshes this device's leaf (protocol §2: every seven days).
    pub fn self_update(&mut self) -> Result<CommitBundle, Error> {
        Self::bundle(self.inner.commit(Vec::new())?)
    }

    fn bundle(output: mls_rs::group::CommitOutput) -> Result<CommitBundle, Error> {
        let welcome = output.welcome_messages.first().map(MlsMessage::to_bytes).transpose()?;
        let group_info =
            output.external_commit_group_info.as_ref().ok_or(Error::WrongMessageType("group info"))?.to_bytes()?;
        Ok(CommitBundle { commit: output.commit_message.to_bytes()?, welcome, group_info })
    }

    pub fn has_pending_commit(&self) -> bool {
        self.inner.has_pending_commit()
    }

    /// The server accepted the pending commit: move to the new epoch.
    pub fn apply_pending_commit(&mut self) -> Result<u64, Error> {
        self.inner.apply_pending_commit()?;
        self.inner.write_to_storage()?;
        Ok(self.inner.current_epoch())
    }

    /// The server refused the pending commit (409 epoch_conflict): forget it and process what won instead.
    pub fn clear_pending_commit(&mut self) {
        self.inner.clear_pending_commit();
    }

    /// Encrypts an application message (a PrivateMessage) in the current epoch.
    pub fn encrypt(&mut self, data: &[u8]) -> Result<Vec<u8>, Error> {
        let message = self.inner.encrypt_application_message(data, Vec::new())?;
        self.inner.write_to_storage()?;
        Ok(message.to_bytes()?)
    }

    /// Processes one log item in order. Decrypting consumes the message key, so the same bytes cannot be processed
    /// twice (rust-core §4).
    pub fn process(&mut self, bytes: &[u8]) -> Result<Received, Error> {
        let message = MlsMessage::from_bytes(bytes)?;
        let received = self.inner.process_incoming_message(message)?;
        let out = match received {
            ReceivedMessage::ApplicationMessage(app) => {
                let member = self.inner.roster().member_with_index(app.sender_index)?;
                Received::Application {
                    sender: Identity::from_signing_identity(&member.signing_identity)?,
                    data: app.data().to_vec(),
                }
            }
            // A removed member's own state stays in the old epoch, so the epoch comes from the commit itself.
            ReceivedMessage::Commit(commit) => match commit.effect {
                CommitEffect::NewEpoch(e) => Received::Commit { epoch: e.epoch, removed_me: false },
                CommitEffect::Removed { new_epoch, .. } => {
                    Received::Commit { epoch: new_epoch.epoch, removed_me: true }
                }
                CommitEffect::ReInit(_) => Received::Other,
            },
            _ => Received::Other,
        };
        self.inner.write_to_storage()?;
        Ok(out)
    }

    /// The current epoch's GroupInfo for external commits, as uploaded after an external join of our own.
    pub fn group_info(&mut self) -> Result<Vec<u8>, Error> {
        Ok(self.inner.group_info_message_allowing_ext_commit(true)?.to_bytes()?)
    }

    /// The per-epoch state key, `MLS-Exporter("geunal state v1", "", 32)` (protocol §5).
    pub fn state_key(&self) -> Result<[u8; 32], Error> {
        let secret = self.inner.export_secret(crate::state::EXPORTER_LABEL, b"", 32)?;
        Ok(secret.as_bytes().try_into().expect("exporter returns the requested 32 bytes"))
    }
}
