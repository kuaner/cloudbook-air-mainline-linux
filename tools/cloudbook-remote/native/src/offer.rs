// SPDX-License-Identifier: AGPL-3.0-or-later
// Apple media negotiation based on iShareScreen's proxy/protocol/offers.py.
use crate::wire::{deflate, inflate, Slice};
use anyhow::{ensure, Result};
use plist::{Dictionary, Value};
use rand::{rngs::OsRng, RngCore};
use std::{
    io::Cursor,
    time::{SystemTime, UNIX_EPOCH},
};
use uuid::Uuid;

const MEDIA_BLOB: &str = "avcMediaStreamNegotiatorMediaBlob";
pub(crate) type MasterKey = [u8; 46];

pub(crate) struct Offer {
    pub message: Vec<u8>,
    pub audio_rx: MasterKey,
    pub audio_tx: MasterKey,
    pub video_rx: MasterKey,
    pub video_tx: MasterKey,
    pub audio_ssrc: u32,
    pub video_ssrc: u32,
}

fn varint(out: &mut Vec<u8>, mut value: u64) {
    while value > 127 {
        out.push(value as u8 | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

fn integers(out: &mut Vec<u8>, fields: &[(u64, u64)]) {
    for &(field, value) in fields {
        varint(out, field << 3);
        varint(out, value);
    }
}

fn bytes(out: &mut Vec<u8>, field: u64, value: &[u8]) {
    varint(out, (field << 3) | 2);
    varint(out, value.len() as u64);
    out.extend_from_slice(value);
}

fn media_plist(mode: u64, ssrc: u32, audio: bool) -> Result<Vec<u8>> {
    let mut blob = Vec::new();
    let mut description = Vec::new();
    integers(&mut blob, &[(1, 1), (2, 1)]);
    if mode == 7 {
        let mut bank = Vec::new();
        // In Apple's offer mapping, bank 123 selects H.264 from the server.
        integers(&mut bank, &[(1, 123)]);
        for tier in [1, 2, 1, 2] {
            let mut resolution = Vec::new();
            integers(&mut resolution, &[(1, 1), (2, tier), (3, 50115), (4, 0)]);
            bytes(&mut bank, 2, &resolution);
        }
        bytes(
            &mut bank,
            3,
            b"FLS;MS:-1;LF:-1;CABAC;POS:0;EOD:1;HTS:2;RR:3;AR:16/9,5/8;XR:16/9,5/8;",
        );
        integers(&mut bank, &[(4, 1)]);
        integers(&mut description, &[(1, ssrc.into()), (2, 0)]);
        bytes(&mut description, 3, &bank);
        integers(
            &mut description,
            &[(6, 1), (7, 0), (8, 63), (9, 1), (12, 1)],
        );
        bytes(&mut blob, 5, &description);
    } else {
        let bitrate = if audio { 24191 } else { 1000 };
        integers(
            &mut description,
            &[
                (1, ssrc.into()),
                (2, 0),
                (3, 0),
                (4, bitrate),
                (5, 0),
                (6, 0),
            ],
        );
        bytes(&mut blob, 3, &description);
    }
    bytes(&mut blob, 6, b"CloudBook Remote");
    integers(&mut blob, &[(8, 0)]);
    for (kind, rate, cap) in [
        (0, 40_000_000, 12288),
        (0, 6_000_000, 131072),
        (4074, 0, 16384),
        (16, 4100, 0),
        (0, 75_000_000, 524288),
        (0, 20_000_000, 98304),
        (4, 6500, 0),
        (0, 60_000_000, 262144),
        (1, 299, 0),
        (0, 100_000_000, 1048576),
    ] {
        let mut tier = Vec::new();
        integers(&mut tier, &[(1, kind), (2, rate)]);
        if cap != 0 {
            integers(&mut tier, &[(3, cap)]);
        }
        bytes(&mut blob, 9, &tier);
    }
    let timestamp = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos() as u64;
    integers(&mut blob, &[(13, timestamp), (14, 2), (16, 0), (18, 1)]);
    let mut endpoint = vec![8, 0, 16, 1];
    bytes(&mut endpoint, 3, b"Android-aarch64");
    bytes(&mut endpoint, 4, b"1.0.0");
    bytes(&mut endpoint, 5, b"0");
    let mut dict = Dictionary::new();
    dict.insert(
        "avcMediaStreamOptionRemoteEndpointInfo".into(),
        Value::Data(endpoint),
    );
    dict.insert(
        "avcMediaStreamNegotiatorMode".into(),
        Value::Integer(mode.into()),
    );
    dict.insert(MEDIA_BLOB.into(), Value::Data(deflate(&blob)?));
    dict.insert(
        "avcMediaStreamOptionCallID".into(),
        Value::String(Uuid::new_v4().to_string().to_uppercase()),
    );
    let mut encoded = Vec::new();
    Value::Dictionary(dict).to_writer_binary(&mut encoded)?;
    Ok(encoded)
}

impl Offer {
    pub fn new(audio: bool) -> Result<Self> {
        let audio_ssrc = OsRng.next_u32();
        let video_ssrc = OsRng.next_u32();
        let (mut audio_rx, mut audio_tx, mut video_rx, mut video_tx) =
            ([0; 46], [0; 46], [0; 46], [0; 46]);
        for key in [&mut audio_rx, &mut audio_tx, &mut video_rx, &mut video_tx] {
            OsRng.fill_bytes(key);
        }
        let audio = media_plist(8, audio_ssrc, audio)?;
        let video = media_plist(7, video_ssrc, false)?;
        let size = audio.len() + video.len() + 0xd8;
        let mut message = vec![0; 0x24];
        message[0] = 0x1c;
        message[2..4].copy_from_slice(&u16::try_from(size)?.to_be_bytes());
        message[4..6].copy_from_slice(&3u16.to_be_bytes());
        message[6..10].copy_from_slice(&7u32.to_be_bytes());
        message[10..12].copy_from_slice(&u16::try_from(audio.len())?.to_be_bytes());
        message[12..14].copy_from_slice(&u16::try_from(video.len())?.to_be_bytes());
        message[0x14..0x24].copy_from_slice(Uuid::new_v4().as_bytes());
        for part in [
            audio_tx.as_slice(),
            &audio_rx,
            &audio,
            &video_tx,
            &video_rx,
            &video,
        ] {
            message.extend_from_slice(part);
        }
        Ok(Self {
            message,
            audio_rx,
            audio_tx,
            video_rx,
            video_tx,
            audio_ssrc,
            video_ssrc,
        })
    }
}

fn next_field<'a>(data: &mut Slice<'a>) -> Result<(u64, u64, &'a [u8])> {
    let tag = data.varint()?;
    let (value, bytes) = match tag & 7 {
        0 => (data.varint()?, &[][..]),
        1 => (0, data.take(8)?),
        2 => {
            let size = usize::try_from(data.varint()?)?;
            (0, data.take(size)?)
        }
        5 => (0, data.take(4)?),
        _ => anyhow::bail!("无效的媒体协商字段"),
    };
    Ok((tag >> 3, value, bytes))
}

fn canvas(media_blob: &[u8]) -> Result<Option<(u16, u16)>> {
    let raw = inflate(media_blob, 128 * 1024)?;
    let mut fields = Slice(&raw);
    while !fields.0.is_empty() {
        let (tag, _, description) = next_field(&mut fields)?;
        if tag != 5 {
            continue;
        }
        let mut width = 0;
        let mut height = 0;
        let mut tiles = 0;
        let mut description = Slice(description);
        while !description.0.is_empty() {
            let (tag, value, _) = next_field(&mut description)?;
            match tag {
                4 => width = value,
                5 => height = value,
                6 => tiles = value,
                _ => {}
            }
        }
        if width > 0 && height > 0 {
            ensure!(tiles <= 1, "Mac 没有协商单路 H.264");
            return Ok(Some((u16::try_from(width)?, u16::try_from(height)?)));
        }
    }
    Ok(None)
}

pub(crate) fn parse_answer(message: &[u8]) -> Result<Option<(u16, u16)>> {
    if message.first() != Some(&0) {
        return Ok(None);
    }
    for start in message
        .windows(8)
        .enumerate()
        .filter_map(|(at, bytes)| (bytes == b"bplist00").then_some(at))
    {
        let data = &message[start..];
        // A binary plist ends in a 32-byte trailer pointing at its own
        // offset table. Find that trailer before invoking the parser.
        for end in 40..=data.len() {
            let trailer = &data[end - 32..end];
            if trailer[..6] != [0; 6] {
                continue;
            }
            let offset_size = usize::from(trailer[6]);
            if !matches!(offset_size, 1 | 2 | 4 | 8) {
                continue;
            }
            let count = u64::from_be_bytes(trailer[8..16].try_into()?);
            let offset = u64::from_be_bytes(trailer[24..32].try_into()?);
            if offset < 8
                || count == 0
                || count > 1024
                || offset > end as u64
                || offset + count * offset_size as u64 != (end - 32) as u64
            {
                continue;
            }
            if let Ok(Value::Dictionary(dict)) = Value::from_reader(Cursor::new(&data[..end])) {
                if let Some(Value::Data(blob)) = dict.get(MEDIA_BLOB) {
                    if let Some(size) = canvas(blob)? {
                        return Ok(Some(size));
                    }
                }
                break;
            }
        }
    }
    Ok(None)
}
