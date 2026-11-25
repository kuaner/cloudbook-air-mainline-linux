// SPDX-License-Identifier: AGPL-3.0-or-later
// Apple SRTP key derivation and packet formats from RFC 3711 and iShareScreen.
use crate::offer::MasterKey;
use aes::{
    cipher::{BlockEncrypt, KeyInit},
    Aes256,
};
use hmac::{Hmac, KeyInit as HmacKeyInit, Mac};
use sha1::Sha1;
use std::collections::VecDeque;
use subtle::ConstantTimeEq;

const TAG_SIZE: usize = 10;

fn xor_ctr(cipher: &Aes256, iv: u128, payload: &mut [u8]) {
    // Use blocks in parallel so ARMv8 crypto extensions can pipeline AES.
    let mut counter = iv;
    for chunk in payload.chunks_mut(128) {
        let mut blocks = [aes::Block::default(); 8];
        let count = chunk.len().div_ceil(16);
        for block in &mut blocks[..count] {
            block.copy_from_slice(&counter.to_be_bytes());
            counter = counter.wrapping_add(1);
        }
        cipher.encrypt_blocks(&mut blocks[..count]);
        for (bytes, block) in chunk.chunks_mut(16).zip(blocks) {
            for (byte, key) in bytes.iter_mut().zip(block) {
                *byte ^= key;
            }
        }
    }
}

struct Keys {
    cipher: Aes256,
    auth: Hmac<Sha1>,
    salt: u128,
}

impl Keys {
    fn new(master: &MasterKey, label: u8) -> Self {
        let cipher = Aes256::new(master[..32].into());
        let mut salt = [0; 16];
        salt[..14].copy_from_slice(&master[32..]);
        let salt = u128::from_be_bytes(salt);
        let derive = |offset: u8, bytes: &mut [u8]| {
            xor_ctr(&cipher, salt ^ (u128::from(label + offset) << 64), bytes);
        };
        let (mut key, mut auth, mut session_salt) = ([0; 32], [0; 20], [0; 16]);
        derive(0, &mut key);
        derive(1, &mut auth);
        derive(2, &mut session_salt[..14]);
        Self {
            cipher: Aes256::new((&key).into()),
            auth: <Hmac<Sha1> as HmacKeyInit>::new_from_slice(&auth)
                .expect("HMAC accepts any key length"),
            salt: u128::from_be_bytes(session_salt),
        }
    }

    fn crypt(&self, ssrc: u32, index: u64, data: &mut [u8]) {
        xor_ctr(
            &self.cipher,
            self.salt ^ (u128::from(ssrc) << 64) ^ (u128::from(index) << 16),
            data,
        );
    }

    fn tag(&self, parts: &[&[u8]]) -> [u8; TAG_SIZE] {
        let mut mac = self.auth.clone();
        for part in parts {
            mac.update(part);
        }
        let full = mac.finalize().into_bytes();
        let mut tag = [0; TAG_SIZE];
        tag.copy_from_slice(&full[..TAG_SIZE]);
        tag
    }
}

#[derive(Clone, Copy)]
pub(crate) struct Header {
    pub ssrc: u32,
    pub marker: bool,
    pub timestamp: u32,
    pub index: u64,
}

// A 128-packet replay window permits NACK retransmits and out-of-order delivery.
struct Source {
    ssrc: u32,
    latest: u64,
    received: u128,
}

pub(crate) struct Receiver {
    keys: Keys,
    sources: VecDeque<Source>,
}

impl Receiver {
    pub fn new(master: &MasterKey) -> Self {
        Self {
            keys: Keys::new(master, 0),
            sources: VecDeque::new(),
        }
    }

    pub fn open<'a>(
        &mut self,
        packet: &'a mut [u8],
        payload_type: u8,
    ) -> Option<(Header, &'a [u8])> {
        if packet.len() < 12 + TAG_SIZE
            || packet[0] >> 6 != 2
            || if payload_type == 0 {
                packet[1] & 0x7f < 96
            } else {
                packet[1] & 0x7f != payload_type
            }
        {
            return None;
        }
        let end = packet.len() - TAG_SIZE;
        let mut offset = 12 + 4 * usize::from(packet[0] & 15);
        if packet[0] & 16 != 0 {
            let words = u16::from_be_bytes(packet.get(offset + 2..offset + 4)?.try_into().ok()?);
            offset += 4 + 4 * usize::from(words);
        }
        if offset > end {
            return None;
        }
        let ssrc = u32::from_be_bytes(packet[8..12].try_into().ok()?);
        let sequence = u16::from_be_bytes(packet[2..4].try_into().ok()?);
        let marker = packet[1] & 128 != 0;
        let padded = packet[0] & 32 != 0;
        let timestamp = u32::from_be_bytes(packet[4..8].try_into().ok()?);
        let position = self.sources.iter().position(|source| source.ssrc == ssrc);
        let prior = position.map(|at| &self.sources[at]);
        let index = match prior {
            Some(src) => src
                .latest
                .checked_add_signed(i64::from(sequence.wrapping_sub(src.latest as u16) as i16))?,
            None => u64::from(sequence),
        };
        let roc = (index >> 16) as u32;
        if !bool::from(
            self.keys
                .tag(&[&packet[..end], &roc.to_be_bytes()])
                .ct_eq(&packet[end..]),
        ) {
            return None;
        }
        let mut source = match position {
            Some(at) => self.sources.remove(at)?,
            None => Source {
                ssrc,
                latest: index,
                received: 0,
            },
        };
        if index > source.latest {
            let shift = index - source.latest;
            source.received = if shift < 128 {
                source.received << shift
            } else {
                0
            };
            source.latest = index;
        }
        let age = source.latest - index;
        let fresh = age < 128 && source.received & (1 << age) == 0;
        if fresh {
            source.received |= 1 << age;
        }
        self.sources.push_back(source);
        if self.sources.len() > 16 {
            self.sources.pop_front();
        }
        if !fresh {
            return None;
        }
        let payload = &mut packet[offset..end];
        self.keys.crypt(ssrc, index, payload);
        let payload = if padded {
            payload.get(..payload.len().checked_sub(usize::from(*payload.last()?))?)?
        } else {
            payload
        };
        Some((
            Header {
                ssrc,
                marker,
                timestamp,
                index,
            },
            payload,
        ))
    }
}

pub(crate) struct Heartbeat {
    keys: Keys,
    ssrc: u32,
    index: u64,
}

impl Heartbeat {
    pub fn new(master: &MasterKey, ssrc: u32) -> Self {
        Self {
            keys: Keys::new(master, 0),
            ssrc,
            index: 0,
        }
    }
    pub fn packet(&mut self) -> [u8; 26] {
        let mut packet = [0; 26];
        packet[..2].copy_from_slice(&[0x80, 101]);
        packet[2..4].copy_from_slice(&(self.index as u16).to_be_bytes());
        packet[4..8].copy_from_slice(&(self.index as u32).wrapping_mul(480).to_be_bytes());
        packet[8..12].copy_from_slice(&self.ssrc.to_be_bytes());
        packet[12..16].copy_from_slice(b"\0\x68\x34\0");
        self.keys.crypt(self.ssrc, self.index, &mut packet[12..16]);
        let tag = self
            .keys
            .tag(&[&packet[..16], &((self.index >> 16) as u32).to_be_bytes()]);
        packet[16..].copy_from_slice(&tag);
        self.index += 1;
        packet
    }
}

pub(crate) struct Reports {
    keys: Keys,
    ssrc: u32,
    index: u32,
}

impl Reports {
    pub fn new(master: &MasterKey, ssrc: u32) -> Self {
        Self {
            keys: Keys::new(master, 3),
            ssrc,
            index: 0,
        }
    }

    pub fn packet(&mut self, source: Option<(u32, u64)>, feedback: &[u8]) -> Vec<u8> {
        let mut data = Vec::with_capacity(64);
        if let Some((ssrc, index)) = source {
            data.extend_from_slice(&[0x81, 201, 0, 7]);
            data.extend_from_slice(&self.ssrc.to_be_bytes());
            data.extend_from_slice(&ssrc.to_be_bytes());
            data.extend_from_slice(&[0; 4]);
            data.extend_from_slice(&(index as u32).to_be_bytes());
            data.extend_from_slice(&[0; 12]);
        } else {
            data.extend_from_slice(&[0x80, 201, 0, 1]);
            data.extend_from_slice(&self.ssrc.to_be_bytes());
        }
        data.extend_from_slice(feedback);
        self.keys
            .crypt(self.ssrc, self.index.into(), &mut data[8..]);
        data.extend_from_slice(&(0x8000_0000 | self.index).to_be_bytes());
        let tag = self.keys.tag(&[&data]);
        data.extend_from_slice(&tag);
        self.index = (self.index + 1) & 0x7fff_ffff;
        data
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Produced with iShareScreen's SRTPEncryptor and SRTCPEncryptor.
    #[test]
    fn authenticates_reordered_packets_across_rollover_and_rejects_replays() {
        let key = std::array::from_fn(|index| index as u8);
        let mut receiver = Receiver::new(&key);
        let mut before =
            crate::test_hex("80fbfffe0000000012345678bf04cfb7a36e88e0f000be3619c74968");
        let mut last = crate::test_hex("80fbffff000001e0123456786466be3b87033559c0de8383e18d");
        let mut after = crate::test_hex("80fb0000000003c012345678fba3438ae46e11530404eca4e1d97c");
        let mut replay = after.clone();
        assert_eq!(receiver.open(&mut before, 123).unwrap().1, b"before");
        let (header, payload) = receiver.open(&mut after, 123).unwrap();
        assert_eq!((header.index, payload), (65536, b"after".as_slice()));
        assert_eq!(receiver.open(&mut last, 123).unwrap().1, b"last");
        assert!(receiver.open(&mut replay, 123).is_none());
    }

    #[test]
    fn feedback_and_heartbeat_match_wire_format() {
        let key = std::array::from_fn(|index| index as u8);
        let heartbeat = Heartbeat::new(&key, 0x1234_5678).packet();
        assert_eq!(
            heartbeat.as_slice(),
            crate::test_hex("80650000000000001234567805d147881a80a2a7d658a9e8eb3c")
        );
        let report = Reports::new(&key, 0x1234_5678).packet(Some((0x8765_4321, 171)), &[]);
        assert_eq!(report, crate::test_hex("81c9000712345678ebd79cd959adef05ae1277f3760085f1c6f005bb145e602e80000000660ed5dac7a52d0dfde1"));
    }
}
