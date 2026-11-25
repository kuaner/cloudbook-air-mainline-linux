// SPDX-License-Identifier: AGPL-3.0-or-later
// Apple AVC packetization and SPS parsing based on iShareScreen.
use crate::{srtp::Header, wire::Slice, Size};

pub(crate) const START_CODE: &[u8; 4] = b"\0\0\0\x01";
const MAX_PICTURE: usize = 4 * 1024 * 1024;

#[derive(PartialEq, Eq)]
pub(crate) struct Format {
    pub size: Size,
    pub matrix: u8,
    pub sps: Vec<u8>,
    pub pps: Vec<u8>,
}

pub(crate) fn config(packet: &[u8], fallback: Size) -> Option<Format> {
    let at = packet.windows(4).position(|bytes| bytes == b"avcC")?;
    let mut data = Slice(packet.get(at + 9..)?);
    let sps_count = data.u8().ok()? & 31;
    let mut sps = None;
    let mut pps = None;
    for _ in 0..sps_count {
        let unit = data.short().ok()?;
        if unit.first().is_some_and(|byte| byte & 31 == 7) {
            sps.get_or_insert(unit);
        }
    }
    for _ in 0..data.u8().ok()? {
        let unit = data.short().ok()?;
        if unit.first().is_some_and(|byte| byte & 31 == 8) {
            pps.get_or_insert(unit);
        }
    }
    let sps = sps?;
    let pps = pps?;
    let mut bits = Bits::new(&sps[1..]);
    let size = size_and_vui(&mut bits).unwrap_or(fallback);
    let matrix = full_range_matrix(&mut bits).unwrap_or_default();
    Some(Format {
        size,
        matrix,
        sps: [START_CODE.as_slice(), sps].concat(),
        pps: [START_CODE.as_slice(), pps].concat(),
    })
}

#[derive(Default)]
pub(crate) struct Picture {
    timestamp: Option<u32>,
    bytes: Vec<u8>,
    fragment: Option<usize>,
    damaged: bool,
    has_picture: bool,
    intra: bool,
}

pub(crate) enum Assembly {
    Pending,
    Lost,
    Complete { intra: bool, timestamp: u32 },
}

impl Picture {
    pub fn packet(&mut self, header: Header, payload: &[u8]) -> Assembly {
        let mut previous_incomplete = false;
        if self.timestamp != Some(header.timestamp) {
            previous_incomplete = self.timestamp.is_some() && !self.bytes.is_empty();
            self.clear();
            self.timestamp = Some(header.timestamp);
        }
        if self.bytes.len().saturating_add(payload.len()) > MAX_PICTURE - START_CODE.len() - 1 {
            self.bytes.clear();
            self.damaged = true;
        }
        if !self.damaged && !payload.is_empty() && payload[0] != 0x92 {
            match payload[0] & 31 {
                1..=23 if self.fragment.is_none() && payload.len() >= 2 => self.nal(payload),
                24 if self.fragment.is_none() => {
                    if let Some(nals) = payload.get(3..) {
                        let mut nals = Slice(nals);
                        while !nals.0.is_empty() {
                            match nals.short() {
                                Ok(nal) if !nal.is_empty() => self.nal(nal),
                                _ => {
                                    self.damaged = true;
                                    break;
                                }
                            }
                        }
                    }
                }
                kind @ (28 | 29) => {
                    let offset = if kind == 28 { 2 } else { 4 };
                    if let Some(body) = payload.get(offset..).filter(|body| !body.is_empty()) {
                        let flags = payload[1];
                        if flags & 128 != 0 && self.fragment.is_none() {
                            self.bytes.extend_from_slice(START_CODE);
                            self.fragment = Some(self.bytes.len());
                            self.bytes.push((payload[0] & 0xe0) | (flags & 31));
                            self.bytes.extend_from_slice(body);
                        } else if let Some(start) = self.fragment.filter(|_| flags & 128 == 0) {
                            self.bytes.extend_from_slice(body);
                            if flags & 64 != 0 {
                                self.fragment = None;
                                self.track_nal(start);
                            }
                        } else {
                            self.damaged = true;
                        }
                    } else {
                        self.damaged = true;
                    }
                }
                _ => self.damaged = true,
            }
        }
        if header.marker {
            self.timestamp = None;
            if self.damaged || self.fragment.take().is_some() {
                self.clear();
                return Assembly::Lost;
            }
            if self.has_picture {
                return Assembly::Complete {
                    intra: self.intra,
                    timestamp: header.timestamp,
                };
            }
            self.clear();
        }
        if previous_incomplete {
            Assembly::Lost
        } else {
            Assembly::Pending
        }
    }

    fn nal(&mut self, nal: &[u8]) {
        self.bytes.extend_from_slice(START_CODE);
        let start = self.bytes.len();
        self.bytes.extend_from_slice(nal);
        self.track_nal(start);
    }

    fn track_nal(&mut self, start: usize) {
        match self.bytes[start] & 31 {
            5 => {
                self.has_picture = true;
                self.intra = true;
            }
            1 => {
                self.has_picture = true;
                if !self.intra {
                    let end = self.bytes.len().min(start + 33);
                    let mut bits = Bits::new(&self.bytes[start + 1..end]);
                    self.intra = matches!(bits.ue().and_then(|_| bits.ue()), Some(2 | 7));
                }
            }
            _ => {}
        }
    }

    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub fn clear(&mut self) {
        self.timestamp = None;
        self.bytes.clear();
        self.fragment = None;
        self.damaged = false;
        self.has_picture = false;
        self.intra = false;
    }

    pub fn gap(&mut self) {
        self.bytes.clear();
        self.fragment = None;
        self.damaged = self.timestamp.is_some();
        self.has_picture = false;
        self.intra = false;
    }
}

struct Bits<'a> {
    bytes: &'a [u8],
    byte: u8,
    remaining: u8,
    zeros: usize,
}

impl<'a> Bits<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self {
            bytes,
            byte: 0,
            remaining: 0,
            zeros: 0,
        }
    }

    fn get(&mut self, count: usize) -> Option<u32> {
        let mut value = 0;
        for _ in 0..count {
            if self.remaining == 0 {
                let (&byte, tail) = self.bytes.split_first()?;
                self.bytes = tail;
                self.byte = byte;
                if self.zeros == 2 && byte == 3 {
                    let (&byte, tail) = self.bytes.split_first()?;
                    self.bytes = tail;
                    self.byte = byte;
                    self.zeros = 0;
                }
                self.zeros = if self.byte == 0 { self.zeros + 1 } else { 0 };
                self.remaining = 8;
            }
            self.remaining -= 1;
            value = (value << 1) | u32::from((self.byte >> self.remaining) & 1);
        }
        Some(value)
    }
    fn flag(&mut self) -> Option<bool> {
        Some(self.get(1)? != 0)
    }
    fn ue(&mut self) -> Option<u32> {
        let mut zeros = 0;
        while !self.flag()? {
            zeros += 1;
            if zeros > 30 {
                return None;
            }
        }
        Some((1 << zeros) - 1 + self.get(zeros)?)
    }
    fn se(&mut self) -> Option<i32> {
        let unsigned = self.ue()?;
        if unsigned % 2 != 0 {
            Some(unsigned.div_ceil(2) as i32)
        } else {
            Some(-((unsigned / 2) as i32))
        }
    }
}

fn size_and_vui(bits: &mut Bits) -> Option<Size> {
    let profile = bits.get(8)?;
    bits.get(16)?;
    bits.ue()?;
    let mut chroma = 1;
    if matches!(
        profile,
        100 | 110 | 122 | 244 | 44 | 83 | 86 | 118 | 128 | 138 | 139 | 134 | 135
    ) {
        chroma = bits.ue()?;
        if chroma == 3 && bits.flag()? {
            chroma = 0;
        }
        bits.ue()?;
        bits.ue()?;
        bits.flag()?;
        if bits.flag()? {
            for index in 0..if chroma == 3 { 12 } else { 8 } {
                if !bits.flag()? {
                    continue;
                }
                let mut scale = 8;
                for _ in 0..if index < 6 { 16 } else { 64 } {
                    if scale != 0 {
                        scale = (scale + bits.se()?).rem_euclid(256);
                    }
                }
            }
        }
    }
    bits.ue()?;
    match bits.ue()? {
        0 => {
            bits.ue()?;
        }
        1 => {
            bits.flag()?;
            bits.se()?;
            bits.se()?;
            let count = bits.ue()?;
            if count > 255 {
                return None;
            }
            for _ in 0..count {
                bits.se()?;
            }
        }
        2 => {}
        _ => return None,
    }
    bits.ue()?;
    bits.flag()?;
    let width = (bits.ue()? + 1).checked_mul(16)?;
    let height = (bits.ue()? + 1).checked_mul(16)?;
    let fields = if bits.flag()? {
        1
    } else {
        bits.flag()?;
        2
    };
    bits.flag()?;
    let (crop_x, crop_y) = if bits.flag()? {
        let (sub_x, sub_y) = match chroma {
            0 | 3 => (1, 1),
            1 => (2, 2),
            2 => (2, 1),
            _ => return None,
        };
        (
            (bits.ue()? + bits.ue()?).checked_mul(sub_x)?,
            (bits.ue()? + bits.ue()?).checked_mul(sub_y * fields)?,
        )
    } else {
        (0, 0)
    };
    let width = u16::try_from(width.checked_sub(crop_x)?).ok()?;
    let height = u16::try_from((height * fields).checked_sub(crop_y)?).ok()?;
    (width != 0 && height != 0).then_some((width, height))
}

fn full_range_matrix(bits: &mut Bits) -> Option<u8> {
    if !bits.flag()? {
        return None;
    }
    if bits.flag()? && bits.get(8)? == 255 {
        bits.get(32)?;
    }
    if bits.flag()? {
        bits.flag()?;
    }
    if !bits.flag()? {
        return None;
    }
    bits.get(3)?;
    if !bits.flag()? {
        return None;
    }
    if !bits.flag()? {
        return Some(1);
    }
    bits.get(16)?;
    let matrix = bits.get(8)? as u8;
    Some(if matches!(matrix, 1 | 5 | 6 | 9) {
        matrix
    } else {
        1
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn packet(picture: &mut Picture, marker: bool, data: &[u8]) -> Assembly {
        picture.packet(
            Header {
                ssrc: 1,
                marker,
                timestamp: 42,
                index: 0,
            },
            data,
        )
    }

    #[test]
    fn classifies_non_idr_slices_in_each_packet_form() {
        let mut picture = Picture::default();
        assert!(matches!(
            packet(&mut picture, true, &[0x61, 0xc0]),
            Assembly::Complete { intra: false, .. }
        ));
        picture.clear();
        assert!(matches!(
            packet(&mut picture, true, &[24, 0, 0, 0, 2, 0x61, 0xb0]),
            Assembly::Complete { intra: true, .. }
        ));
        picture.clear();
        assert!(matches!(
            packet(&mut picture, false, &[0x7c, 0x81, 0xb0]),
            Assembly::Pending
        ));
        assert!(matches!(
            packet(&mut picture, true, &[0x7c, 0x41, 0x80]),
            Assembly::Complete { intra: true, .. }
        ));
        assert_eq!(picture.bytes(), &[0, 0, 0, 1, 0x61, 0xb0, 0x80]);
    }

    #[test]
    fn removes_emulation_prevention_bytes_while_reading_bits() {
        let mut bits = Bits::new(&[0, 0, 3, 1, 0x80]);
        assert_eq!(bits.get(24), Some(1));
        assert_eq!(bits.flag(), Some(true));
        assert_eq!(bits.get(7), Some(0));
        assert_eq!(bits.flag(), None);
    }
}
