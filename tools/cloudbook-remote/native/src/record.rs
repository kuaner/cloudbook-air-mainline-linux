// SPDX-License-Identifier: AGPL-3.0-or-later
// Apple encrypted RFB records based on iShareScreen's proxy/protocol/enc1103.py.
use aes::{
    cipher::{BlockDecrypt, BlockEncrypt, KeyInit},
    Aes128,
};
use anyhow::{ensure, Result};
use sha1::{Digest, Sha1};
use std::io::{Read, Write};
use subtle::ConstantTimeEq;

pub(crate) struct RecordCipher {
    cipher: Aes128,
    iv: [u8; 16],
    counter: u32,
}

impl RecordCipher {
    pub fn from_setup(setup: &[u8], master: &[u8; 16]) -> Result<Self> {
        ensure!(setup.len() == 36, "无效的 RFB 加密设置");
        let unwrap = Aes128::new(master.into());
        let mut key: [u8; 16] = setup[4..20].try_into()?;
        let mut iv: [u8; 16] = setup[20..].try_into()?;
        unwrap.decrypt_block((&mut key).into());
        unwrap.decrypt_block((&mut iv).into());
        Ok(Self {
            cipher: Aes128::new((&key).into()),
            iv,
            counter: 0,
        })
    }

    pub fn copy_initial(&self) -> Self {
        Self {
            cipher: self.cipher.clone(),
            iv: self.iv,
            counter: self.counter,
        }
    }

    pub fn wrap_block(&self, mut block: [u8; 16]) -> [u8; 16] {
        self.cipher.encrypt_block((&mut block).into());
        block
    }

    fn tag(counter: u32, body: &[u8]) -> [u8; 20] {
        let mut sha = Sha1::new();
        sha.update(counter.to_be_bytes());
        sha.update(body);
        sha.finalize().into()
    }

    pub fn append(&mut self, out: &mut Vec<u8>, message: &[u8]) -> Result<()> {
        let padded = (message.len() + 22).next_multiple_of(16);
        let offset = out.len();
        out.extend_from_slice(&u16::try_from(padded)?.to_be_bytes());
        out.extend_from_slice(&u16::try_from(message.len())?.to_be_bytes());
        out.extend_from_slice(message);
        out.resize(offset + 2 + padded - 20, 0);
        let tag = Self::tag(self.counter, &out[offset + 2..]);
        out.extend_from_slice(&tag);
        self.counter = self.counter.wrapping_add(1);
        for block in out[offset + 2..].as_chunks_mut::<16>().0 {
            for (byte, iv) in block.iter_mut().zip(self.iv) {
                *byte ^= iv;
            }
            self.cipher.encrypt_block(block.into());
            self.iv.copy_from_slice(block);
        }
        Ok(())
    }

    pub fn send(&mut self, io: &mut impl Write, message: &[u8]) -> Result<()> {
        let mut record = Vec::new();
        self.append(&mut record, message)?;
        io.write_all(&record)?;
        Ok(())
    }

    pub fn read(&mut self, io: &mut impl Read) -> Result<Option<Vec<u8>>> {
        let mut length = [0; 2];
        io.read_exact(&mut length)?;
        let length = u16::from_be_bytes(length) as usize;
        ensure!(
            length >= 16 && length.is_multiple_of(16),
            "无效的加密 RFB 记录长度"
        );
        let mut data = vec![0; length];
        io.read_exact(&mut data)?;
        for block in data.as_chunks_mut::<16>().0 {
            let saved = *block;
            self.cipher.decrypt_block(block.into());
            for (byte, iv) in block.iter_mut().zip(self.iv) {
                *byte ^= iv;
            }
            self.iv = saved;
        }
        let counter = self.counter;
        self.counter = counter.wrapping_add(1);
        // Some macOS versions send a short unauthenticated tickle record.
        // It advances CBC state but must never be dispatched to the UI.
        if length <= 20 {
            return Ok(None);
        }
        let (body, tag) = data.split_at(length - 20);
        for candidate in counter.saturating_sub(1)..counter.saturating_add(6) {
            if bool::from(Self::tag(candidate, body).ct_eq(tag)) {
                self.counter = candidate.wrapping_add(1);
                let size = u16::from_be_bytes(body[..2].try_into()?) as usize;
                ensure!(size <= body.len() - 2, "无效的 RFB 正文长度");
                data.copy_within(2..2 + size, 0);
                data.truncate(size);
                return Ok(Some(data));
            }
        }
        Ok(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_two_consecutive_isharescreen_records() {
        let keys: [u8; 36] = std::array::from_fn(|index| index as u8);
        let mut tx = RecordCipher::from_setup(&keys, &[0; 16]).unwrap();
        let mut rx = tx.copy_initial();
        let mut wire = Vec::new();
        let down = b"\x04\x01\0\0\0\0\0a";
        let up = b"\x04\0\0\0\0\0\0a";
        tx.append(&mut wire, down).unwrap();
        tx.append(&mut wire, up).unwrap();
        assert_eq!(wire, crate::test_hex("00207c99a2b4bdfb1b7bdec14d2085e6d1fe8a8700eb8153a41b13b3acb5f3ea35e800205448cbd9e577b7ccf890889a7dbfa676a74cc8224d262b3905a0ddf6e6f187cf"));
        let mut wire = wire.as_slice();
        assert_eq!(rx.read(&mut wire).unwrap().unwrap(), down);
        assert_eq!(rx.read(&mut wire).unwrap().unwrap(), up);
        assert!(wire.is_empty());
    }
}
