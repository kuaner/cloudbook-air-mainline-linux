// SPDX-License-Identifier: AGPL-3.0-or-later
use anyhow::{anyhow, ensure, Result};
use flate2::{read::ZlibDecoder, write::ZlibEncoder, Compression};
use std::io::{Read, Write};

pub(crate) struct Slice<'a>(pub &'a [u8]);

impl<'a> Slice<'a> {
    pub fn take(&mut self, count: usize) -> Result<&'a [u8]> {
        let (head, tail) = self
            .0
            .split_at_checked(count)
            .ok_or_else(|| anyhow!("协议数据不完整"))?;
        self.0 = tail;
        Ok(head)
    }

    pub fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    pub fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_be_bytes(self.take(2)?.try_into()?))
    }
    pub fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into()?))
    }
    pub fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_be_bytes(self.take(8)?.try_into()?))
    }
    pub fn short(&mut self) -> Result<&'a [u8]> {
        let size = self.u16()?;
        self.take(size.into())
    }
    pub fn long(&mut self) -> Result<&'a [u8]> {
        let size = self.u32()?;
        self.take(size as usize)
    }

    pub fn varint(&mut self) -> Result<u64> {
        let mut value = 0;
        for shift in (0..64).step_by(7) {
            let byte = self.u8()?;
            ensure!(shift != 63 || byte <= 1, "无效的变长整数");
            value |= u64::from(byte & 0x7f) << shift;
            if byte < 128 {
                return Ok(value);
            }
        }
        Err(anyhow!("无效的变长整数"))
    }
}

pub(crate) fn read_bytes(io: &mut impl Read, len: usize) -> Result<Vec<u8>> {
    let mut bytes = vec![0; len];
    io.read_exact(&mut bytes)?;
    Ok(bytes)
}

pub(crate) fn read_blob(io: &mut impl Read, min: usize, max: usize) -> Result<Vec<u8>> {
    let mut size = [0; 4];
    io.read_exact(&mut size)?;
    let len = u32::from_be_bytes(size) as usize;
    ensure!((min..=max).contains(&len), "无效的协议消息长度");
    read_bytes(io, len)
}

pub(crate) fn write_blob(io: &mut impl Write, bytes: &[u8]) -> Result<()> {
    io.write_all(&u32::try_from(bytes.len())?.to_be_bytes())?;
    io.write_all(bytes)?;
    Ok(())
}

pub(crate) fn deflate(bytes: &[u8]) -> Result<Vec<u8>> {
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
    encoder.write_all(bytes)?;
    Ok(encoder.finish()?)
}

pub(crate) fn inflate(bytes: &[u8], limit: usize) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    ZlibDecoder::new(bytes)
        .take(limit as u64 + 1)
        .read_to_end(&mut out)?;
    ensure!(out.len() <= limit, "解压后数据超过限制");
    Ok(out)
}
