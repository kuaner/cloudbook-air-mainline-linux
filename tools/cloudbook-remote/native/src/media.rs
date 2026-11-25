// SPDX-License-Identifier: AGPL-3.0-or-later
use crate::{
    avc::{self, Assembly, Format, Picture},
    offer::Offer,
    srtp::{Header, Heartbeat, Receiver, Reports},
    Size,
};
use anyhow::{ensure, Context, Result};
use socket2::{Domain, Protocol, Socket, Type};
use std::{
    collections::BTreeMap,
    io,
    net::{IpAddr, SocketAddr, UdpSocket},
    sync::atomic::{AtomicBool, AtomicU64, Ordering::Relaxed},
    time::{Duration, Instant},
};

const REPAIR_WINDOW: Duration = Duration::from_millis(25);
const NACK_INTERVAL: Duration = Duration::from_millis(8);
const MAX_REORDER: usize = 96;
const MEDIA_TIMEOUT: Duration = Duration::from_millis(10);

pub(crate) struct Counters {
    pub bytes: AtomicU64,
    pub losses: AtomicU64,
    pub size: AtomicU64,
}

impl Counters {
    pub fn new(size: Size) -> Self {
        Self {
            bytes: AtomicU64::new(0),
            losses: AtomicU64::new(0),
            size: AtomicU64::new(Self::pack(size)),
        }
    }
    pub fn pack((w, h): Size) -> u64 {
        u64::from(w) << 16 | u64::from(h)
    }
    pub fn size(&self) -> Size {
        let size = self.size.load(Relaxed);
        ((size >> 16) as u16, size as u16)
    }
}

pub(crate) fn bind(port: u16) -> Result<UdpSocket> {
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    socket.set_reuse_address(true)?;
    let _ = socket.set_recv_buffer_size(4 * 1024 * 1024);
    socket.bind(&SocketAddr::from(([0, 0, 0, 0], port)).into())?;
    socket.set_read_timeout(Some(MEDIA_TIMEOUT))?;
    Ok(socket.into())
}

fn transient(error: &io::Error) -> bool {
    matches!(
        error.kind(),
        io::ErrorKind::Interrupted | io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
    )
}

pub(crate) fn audio(
    socket: &UdpSocket,
    peer: IpAddr,
    offer: &Offer,
    enabled: bool,
    stop: &AtomicBool,
    mut output: impl FnMut(&[u8]) -> Result<()>,
) -> Result<()> {
    let mut receiver = Receiver::new(&offer.audio_rx);
    let mut packet = [0; 65536];
    let mut latest = None;
    while !stop.load(Relaxed) {
        match socket.recv_from(&mut packet) {
            Ok((len, source)) if enabled && source.ip() == peer => {
                if let Some((header, data)) = receiver.open(&mut packet[..len], 101) {
                    if latest
                        .is_none_or(|(ssrc, index)| ssrc != header.ssrc || header.index > index)
                    {
                        latest = Some((header.ssrc, header.index));
                        output(data)?;
                    }
                }
            }
            Ok(_) => {}
            Err(error) if transient(&error) => {}
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}

pub(crate) trait VideoOutput {
    fn configure(&mut self, format: &Format) -> Result<()>;
    fn offer(&mut self, timestamp: u32, picture: &[u8], intra: bool) -> Result<bool>;
    fn needs_keyframe(&mut self) -> Result<bool>;
}

struct VideoStream {
    picture: Picture,
    format: Option<Format>,
    source: Option<u32>,
    next_index: u64,
    max_index: u64,
    queued: BTreeMap<u64, (Header, Box<[u8]>)>,
    gap_since: Option<Instant>,
    need_intra: bool,
    latest: Instant,
    offered: bool,
}

impl VideoStream {
    fn new(start: Instant) -> Self {
        Self {
            picture: Picture::default(),
            format: None,
            source: None,
            next_index: 0,
            max_index: 0,
            queued: BTreeMap::new(),
            gap_since: None,
            need_intra: true,
            latest: start,
            offered: false,
        }
    }

    fn unit(
        &mut self,
        header: Header,
        payload: &[u8],
        stats: &Counters,
        output: &mut impl VideoOutput,
    ) -> Result<()> {
        self.next_index = header.index + 1;
        if payload.first() == Some(&0x92) {
            if let Some(format) = avc::config(payload, stats.size()) {
                if self.format.as_ref() != Some(&format) {
                    log::info!(
                        "AVC {}x{}, full-range matrix {}",
                        format.size.0,
                        format.size.1,
                        format.matrix
                    );
                    output.configure(&format)?;
                    stats.size.store(Counters::pack(format.size), Relaxed);
                    self.format = Some(format);
                    self.need_intra = true;
                    self.picture.clear();
                }
            }
        }
        match self.picture.packet(header, payload) {
            Assembly::Complete { intra, timestamp } => {
                if self.format.is_some() && (intra || !self.need_intra) {
                    let accepted = output.offer(timestamp, self.picture.bytes(), intra)?;
                    self.offered |= accepted;
                    self.need_intra = !accepted;
                }
                self.picture.clear();
            }
            Assembly::Lost => self.need_intra = true,
            Assembly::Pending => {}
        }
        Ok(())
    }

    fn drain(&mut self, stats: &Counters, output: &mut impl VideoOutput) -> Result<()> {
        while let Some((header, bytes)) = self.queued.remove(&self.next_index) {
            self.unit(header, &bytes, stats, output)?;
        }
        if self.queued.is_empty() {
            self.gap_since = None;
        }
        Ok(())
    }

    fn skip_gap(&mut self, stats: &Counters, output: &mut impl VideoOutput) -> Result<()> {
        if let Some((&first, _)) = self.queued.first_key_value() {
            stats
                .losses
                .fetch_add(first.saturating_sub(self.next_index), Relaxed);
            self.next_index = first;
            self.gap_since = Some(Instant::now());
            self.need_intra = true;
            self.picture.gap();
            self.drain(stats, output)?;
        }
        Ok(())
    }

    fn refresh(&self, our_ssrc: u32, sequence: u8) -> Vec<u8> {
        let Some(source) = self.source else {
            return Vec::new();
        };
        let mut packets = Vec::with_capacity(40);
        packets.extend_from_slice(&[0x84, 206, 0, 4]);
        packets.extend_from_slice(&our_ssrc.to_be_bytes());
        packets.extend_from_slice(&[0; 4]);
        packets.extend_from_slice(&source.to_be_bytes());
        packets.extend_from_slice(&[sequence, 0, 0, 0]);
        packets.extend_from_slice(&[0x81, 206, 0, 2]);
        packets.extend_from_slice(&our_ssrc.to_be_bytes());
        packets.extend_from_slice(&source.to_be_bytes());
        packets.extend_from_slice(&[0x80, 192, 0, 1]);
        packets.extend_from_slice(&source.to_be_bytes());
        packets
    }

    fn nack(&self, our_ssrc: u32) -> Vec<u8> {
        let mut packet = vec![0x81, 205, 0, 3];
        packet.extend_from_slice(&our_ssrc.to_be_bytes());
        packet.extend_from_slice(&self.source.unwrap_or_default().to_be_bytes());
        packet.extend_from_slice(&(self.next_index as u16).to_be_bytes());
        let missing = (1..=16).fold(0u16, |mask, delta| {
            let index = self.next_index + delta;
            if index < self.max_index && !self.queued.contains_key(&index) {
                mask | 1 << (delta - 1)
            } else {
                mask
            }
        });
        packet.extend_from_slice(&missing.to_be_bytes());
        packet
    }

    fn report_source(&self) -> Option<(u32, u64)> {
        self.source.map(|source| (source, self.max_index))
    }
}

pub(crate) fn video(
    socket: &UdpSocket,
    control: &UdpSocket,
    peer: SocketAddr,
    offer: &Offer,
    stats: &Counters,
    stop: &AtomicBool,
    mut output: impl VideoOutput,
) -> Result<()> {
    let mut receiver = Receiver::new(&offer.video_rx);
    let mut reports = Reports::new(&offer.video_tx, offer.video_ssrc);
    let mut heartbeat = Heartbeat::new(&offer.audio_tx, offer.audio_ssrc);
    let mut packet = [0; 65536];
    let start = Instant::now();
    let mut stream = VideoStream::new(start);
    let mut next_report = start;
    let mut next_refresh = start;
    let mut next_nack = start;
    let mut fir_sequence = 0u8;
    while !stop.load(Relaxed) {
        match socket.recv_from(&mut packet) {
            Ok((len, source)) if source.ip() == peer.ip() => {
                if let Some((header, data)) = receiver.open(&mut packet[..len], 0) {
                    // Only switch encoder generations when the old source stops
                    // delivering and the new one supplies its own configuration.
                    if stream.source != Some(header.ssrc)
                        && (stream.source.is_none()
                            || stream.latest.elapsed() > Duration::from_millis(350))
                        && data.first() == Some(&0x92)
                        && avc::config(data, stats.size()).is_some()
                    {
                        stream.source = Some(header.ssrc);
                        stream.queued.clear();
                        stream.gap_since = None;
                        stream.next_index = header.index;
                        stream.max_index = header.index;
                        stream.picture.clear();
                        stream.need_intra = true;
                        log::info!("AVC source {:#010x}", header.ssrc);
                    }
                    if stream.source == Some(header.ssrc) {
                        stream.latest = Instant::now();
                        stats.bytes.fetch_add(len as u64, Relaxed);
                        stream.max_index = stream.max_index.max(header.index);
                        if header.index == stream.next_index {
                            stream.unit(header, data, stats, &mut output)?;
                            stream.drain(stats, &mut output)?;
                        } else if header.index > stream.next_index {
                            stream
                                .queued
                                .insert(header.index, (header, Box::from(data)));
                            if stream.gap_since.is_none() {
                                stream.gap_since = Some(stream.latest);
                                next_nack = stream.latest;
                            }
                        }
                    }
                }
            }
            Ok(_) => {}
            Err(error) if transient(&error) => {}
            Err(error) => return Err(error).context("接收 Mac 视频失败"),
        }
        let now = Instant::now();
        if stream.queued.len() >= MAX_REORDER
            || stream
                .gap_since
                .is_some_and(|gap| now - gap >= REPAIR_WINDOW)
        {
            stream.skip_gap(stats, &mut output)?;
        } else if stream.gap_since.is_some() && now >= next_nack {
            // A lost feedback packet shouldn't cost a whole GOP. Retry inside the same repair window.
            let nack = reports.packet(stream.report_source(), &stream.nack(offer.video_ssrc));
            let _ = control.send_to(&nack, peer);
            next_nack = now + NACK_INTERVAL;
        }
        if now >= next_report {
            let _ = control.send_to(&heartbeat.packet(), peer);
            let _ = control.send_to(&reports.packet(stream.report_source(), &[]), peer);
            next_report = now + Duration::from_millis(500);
            if output.needs_keyframe()? {
                stream.need_intra = true;
            }
        }
        if stream.need_intra && stream.source.is_some() && now >= next_refresh {
            let feedback = stream.refresh(offer.video_ssrc, fir_sequence);
            let _ = control.send_to(&reports.packet(stream.report_source(), &feedback), peer);
            fir_sequence = fir_sequence.wrapping_add(1);
            next_refresh = now + Duration::from_millis(350);
        }
        ensure!(
            stream.offered || start.elapsed() < Duration::from_secs(15),
            "Mac 没有发送可解码的高性能画面"
        );
    }
    Ok(())
}
