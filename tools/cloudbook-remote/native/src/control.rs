// SPDX-License-Identifier: AGPL-3.0-or-later
// Apple RFB setup and input formats based on iShareScreen's protocol modules.
use crate::{
    auth,
    offer::{self, Offer},
    record::RecordCipher,
    wire::{inflate, read_bytes, Slice},
    Size,
};
use anyhow::{ensure, Context, Result};
use flate2::{Compress, Compression, FlushCompress};
use std::{
    collections::{HashMap, VecDeque},
    io::{self, Write},
    net::{SocketAddr, TcpStream},
    thread,
    time::{Duration, Instant},
};

const IO_TIMEOUT: Duration = Duration::from_secs(8);
const ENCODINGS: [i32; 13] = [
    1010, 1011, 1002, 6, 16, 1104, 1100, -223, 1101, 1105, 1107, 1109, 1110,
];
pub(crate) const CURSOR_POLL: [u8; 10] = [3, 1, 0, 0, 0, 0, 0, 1, 0, 1];
const CLIPBOARD_FORMAT: &[u8] = b"public.utf8-plain-text";
const CLIPBOARD_LIMIT: usize = 1024 * 1024;

pub(crate) struct Connection {
    pub socket: TcpStream,
    pub send: RecordCipher,
    pub receive: RecordCipher,
    pub offer: Offer,
    pub size: Size,
    pub precise_scroll: bool,
    pub pending: Vec<Vec<u8>>,
}

fn tcp(address: SocketAddr) -> Result<TcpStream> {
    let socket = TcpStream::connect_timeout(&address, IO_TIMEOUT)?;
    socket.set_read_timeout(Some(IO_TIMEOUT))?;
    socket.set_write_timeout(Some(Duration::from_secs(2)))?;
    socket.set_nodelay(true)?;
    Ok(socket)
}

pub(crate) fn warmup(address: SocketAddr) -> Result<()> {
    auth::protocol_version(&mut tcp(address)?)?;
    thread::sleep(Duration::from_millis(1400));
    Ok(())
}

fn set_encodings() -> Vec<u8> {
    let mut message = vec![2, 0, 0, ENCODINGS.len() as u8];
    for encoding in ENCODINGS {
        message.extend_from_slice(&encoding.to_be_bytes());
    }
    message
}

fn viewer_info() -> Vec<u8> {
    let mut message = vec![0x21, 0, 0, 0x3e, 0, 1];
    for field in [2u32, 6, 1, 0, 15, 3, 0] {
        message.extend_from_slice(&field.to_be_bytes());
    }
    let mut mask = [0; 32];
    for (index, value) in [(0, 0xb0), (2, 0x0c), (3, 3), (4, 0x90), (10, 0x40)] {
        mask[index] = value;
    }
    message.extend_from_slice(&mask);
    message.extend_from_slice(&[0x12, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 1]);
    message
}

fn display_configuration((width, height): Size) -> Vec<u8> {
    let modes = [
        (1920, 1080),
        (1440, 900),
        (1920, 1080),
        (1440, 810),
        (1312, 848),
    ];
    let len = 0x9c + 28 * modes.len();
    let mut display = vec![0; 0x9c];
    display[..2].copy_from_slice(&(len as u16).to_be_bytes());
    let name = b"CloudBook Virtual Display";
    display[2..2 + name.len()].copy_from_slice(name);
    for (offset, value) in [
        (0x7a, 1u32),
        (0x7e, 4),
        (0x82, 369.45456f32.to_bits()),
        (0x86, 207.81818f32.to_bits()),
        (0x8a, 3840),
        (0x8e, 2160),
        (0x96, 7),
    ] {
        display[offset..offset + 4].copy_from_slice(&value.to_be_bytes());
    }
    display[0x9a..].copy_from_slice(&(modes.len() as u16).to_be_bytes());
    for (w, h) in modes {
        let w = (w * u32::from(width) + 960) / 1920;
        let h = (h * u32::from(height) + 540) / 1080;
        for value in [w, h, w, h] {
            display.extend_from_slice(&value.to_be_bytes());
        }
        display.extend_from_slice(&60.0f64.to_be_bytes());
        display.extend_from_slice(&[0; 4]);
    }
    let mut message = vec![0x1d, 0];
    message.extend_from_slice(&((8 + len) as u16).to_be_bytes());
    message.extend_from_slice(&[0, 1, 0, 1, 0, 0, 0, 0]);
    message.extend_from_slice(&display);
    message
}

fn share_console(socket: &mut TcpStream) -> Result<()> {
    let mut prefix = [0; 4];
    socket.set_read_timeout(Some(Duration::from_millis(500)))?;
    let count = match socket.peek(&mut prefix) {
        Ok(count) => count,
        Err(error)
            if matches!(
                error.kind(),
                io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
            ) =>
        {
            0
        }
        Err(error) => return Err(error.into()),
    };
    socket.set_read_timeout(Some(IO_TIMEOUT))?;
    if count != 4 || prefix != [0, 0x4a, 0, 1] {
        return Ok(());
    }
    let prompt = read_bytes(socket, 76)?;
    let user = prompt[12..]
        .split(|&byte| byte == 0)
        .next()
        .unwrap_or_default();
    let mut response = [0; 74];
    response[..4].copy_from_slice(&[0, 72, 0, 2]);
    let len = user.len().min(63);
    response[10..10 + len].copy_from_slice(&user[..len]);
    socket.write_all(&response)?;
    // The console owner must explicitly approve this session on the Mac.
    socket.set_read_timeout(Some(Duration::from_secs(60)))?;
    let mut ack = [0; 4096];
    ensure!(
        io::Read::read(socket, &mut ack)? > 0,
        "Mac 未批准共享当前会话"
    );
    socket.set_read_timeout(Some(IO_TIMEOUT))?;
    Ok(())
}

fn read_cipher(socket: &mut TcpStream, master: &[u8; 16]) -> Result<RecordCipher> {
    loop {
        match read_bytes(socket, 1)?[0] {
            0x14 => {
                read_bytes(socket, 7)?;
            }
            0 => {
                let body = read_bytes(socket, 3)?;
                let rects = u16::from_be_bytes(body[1..3].try_into()?);
                ensure!(rects <= 64, "无效的加密设置矩形数量");
                let mut cipher = None;
                for _ in 0..rects {
                    let rect = read_bytes(socket, 12)?;
                    let encoding = i32::from_be_bytes(rect[8..].try_into()?);
                    match encoding {
                        1103 => {
                            cipher =
                                Some(RecordCipher::from_setup(&read_bytes(socket, 36)?, master)?)
                        }
                        1010 | 1011 | 1105 | 1107 | 1109 | 1110 => {
                            let size =
                                u16::from_be_bytes(read_bytes(socket, 2)?.as_slice().try_into()?);
                            read_bytes(socket, size.into())?;
                        }
                        _ => anyhow::bail!("Mac 返回了未知的加密设置矩形 {encoding}"),
                    }
                }
                if let Some(cipher) = cipher {
                    return Ok(cipher);
                }
            }
            command => anyhow::bail!("Mac 返回了未知的加密设置消息 {command:#x}"),
        }
    }
}

pub(crate) fn connect(
    address: SocketAddr,
    user: &str,
    password: &str,
    size: Size,
    virtual_screen: bool,
    audio: bool,
) -> Result<Connection> {
    let mut socket = tcp(address)?;
    let master = auth::authenticate(&mut socket, user, password).context("Mac 登录验证失败")?;
    socket.write_all(&[0xc1])?;
    let header = read_bytes(&mut socket, 24)?;
    let name_len = u32::from_be_bytes(header[20..].try_into()?) as usize;
    ensure!(name_len <= 65536, "Mac 返回了无效的屏幕名称长度");
    let name = read_bytes(&mut socket, name_len)?;
    let precise_scroll = name.len() >= 22 && name.starts_with(&[0, 0]) && name[6 + 23 / 8] & 1 != 0;
    share_console(&mut socket)?;
    socket.write_all(&viewer_info())?;
    thread::sleep(Duration::from_millis(100));
    if virtual_screen {
        socket.write_all(&display_configuration(size))?;
    }
    let encodings = set_encodings();
    socket.write_all(&encodings)?;
    let mut receive = read_cipher(&mut socket, &master).context("Mac 没有完成 RFB 加密设置")?;
    let mut send = receive.copy_initial();
    socket.write_all(&[0x12, 0, 0, 2, 0, 1, 0, 0])?;
    thread::sleep(Duration::from_millis(200));
    let offer = Offer::new(audio)?;
    send.send(&mut socket, &encodings)?;
    send.send(&mut socket, &offer.message)?;
    send.send(&mut socket, &[3, 0, 0, 0, 0, 0, 0xff, 0xff, 0xff, 0xff])?;
    let deadline = Instant::now() + IO_TIMEOUT;
    let mut pending = Vec::new();
    while Instant::now() < deadline {
        if let Some(message) = receive
            .read(&mut socket)
            .context("等待 Mac 的高性能视频协商响应")?
        {
            if let Some(size) = offer::parse_answer(&message)? {
                socket.set_read_timeout(None)?;
                return Ok(Connection {
                    socket,
                    send,
                    receive,
                    offer,
                    size,
                    precise_scroll,
                    pending,
                });
            }
            if pending.len() < 16 {
                pending.push(message);
            }
        }
    }
    anyhow::bail!("Mac 没有启动高性能视频流")
}

pub(crate) struct Inputs {
    keys: Vec<u32>,
    last_pointer: Option<(u8, u16, u16)>,
    ticks: [f32; 2],
    points: [f32; 2],
    precise_scroll: bool,
}

impl Inputs {
    pub fn new(precise_scroll: bool) -> Self {
        Self {
            keys: Vec::new(),
            last_pointer: None,
            ticks: [0.0; 2],
            points: [0.0; 2],
            precise_scroll,
        }
    }

    fn key(cipher: &mut RecordCipher, out: &mut Vec<u8>, down: bool, key: u32) -> Result<()> {
        let mut message = [4, u8::from(down), 0, 0, 0, 0, 0, 0];
        message[4..].copy_from_slice(&key.to_be_bytes());
        cipher.append(out, &message)
    }

    fn pointer(
        cipher: &mut RecordCipher,
        out: &mut Vec<u8>,
        (buttons, x, y): (u8, u16, u16),
    ) -> Result<()> {
        let mut block = [0; 16];
        block[10] = 0xff;
        block[11] = buttons;
        block[12..14].copy_from_slice(&x.to_be_bytes());
        block[14..16].copy_from_slice(&y.to_be_bytes());
        let mut message = [0; 18];
        message[0] = 0x10;
        message[2..].copy_from_slice(&cipher.wrap_block(block));
        cipher.append(out, &message)
    }

    pub fn release(&mut self, cipher: &mut RecordCipher, out: &mut Vec<u8>) -> Result<()> {
        for key in self.keys.drain(..).rev() {
            Self::key(cipher, out, false, key)?;
        }
        if let Some((_, x, y)) = self.last_pointer.take().filter(|pointer| pointer.0 != 0) {
            Self::pointer(cipher, out, (0, x, y))?;
        }
        self.ticks = [0.0; 2];
        self.points = [0.0; 2];
        Ok(())
    }

    pub fn dispatch(
        &mut self,
        cipher: &mut RecordCipher,
        out: &mut Vec<u8>,
        event: &[u8],
        (width, height): Size,
    ) -> Result<u64> {
        let mut event = Slice(event);
        let kind = event.u8()?;
        let x = (event.u32()? as i32).clamp(0, i32::from(width - 1)) as u16;
        let y = (event.u32()? as i32).clamp(0, i32::from(height - 1)) as u16;
        let buttons = event.u32()? as u8;
        let key = event.u32()?;
        let down = event.u8()? != 0;
        let dx = f32::from_bits(event.u32()?).clamp(-16.0, 16.0);
        let dy = f32::from_bits(event.u32()?).clamp(-16.0, 16.0);
        let queued = event.u64()?;
        let pointer = (buttons, x, y);
        match kind {
            1 if self.last_pointer == Some(pointer) => {}
            1 | 2 => {
                Self::pointer(cipher, out, pointer)?;
                self.last_pointer = Some(pointer);
            }
            3 => {
                let mut ticks = [0; 2];
                let mut points = [0; 2];
                for (axis, delta) in [dx, dy].into_iter().enumerate() {
                    if !delta.is_finite() {
                        continue;
                    }
                    self.ticks[axis] += delta;
                    ticks[axis] = self.ticks[axis] as i16;
                    self.ticks[axis] -= f32::from(ticks[axis]);
                    self.points[axis] += 10.0 * delta;
                    points[axis] = self.points[axis] as i32;
                    self.points[axis] -= points[axis] as f32;
                }
                if self.precise_scroll {
                    if ticks != [0; 2] || points != [0; 2] {
                        let mut message = Vec::with_capacity(58);
                        message.extend_from_slice(&[23, 0, 0, 54, 0, 1, 0, 11]);
                        for value in [ticks[0], ticks[1], 0] {
                            message.extend_from_slice(&value.to_be_bytes());
                        }
                        for value in [
                            points[0] * 65536 / 10,
                            points[1] * 65536 / 10,
                            0,
                            points[0],
                            points[1],
                            0,
                            0,
                            0,
                            1,
                            2,
                        ] {
                            message.extend_from_slice(&value.to_be_bytes());
                        }
                        message.extend_from_slice(&x.to_be_bytes());
                        message.extend_from_slice(&y.to_be_bytes());
                        cipher.append(out, &message)?;
                    }
                } else if ticks != [0; 2] {
                    for (amount, positive, negative) in [(ticks[0], 32, 64), (ticks[1], 8, 16)] {
                        let flag = if amount > 0 { positive } else { negative };
                        for _ in 0..amount.unsigned_abs() {
                            Self::pointer(cipher, out, (buttons | flag, x, y))?;
                            Self::pointer(cipher, out, pointer)?;
                        }
                    }
                    self.last_pointer = Some(pointer);
                }
            }
            4 if key != 0 => {
                if down && !self.keys.contains(&key) {
                    self.keys.push(key);
                }
                if !down {
                    self.keys.retain(|&held| held != key);
                }
                Self::key(cipher, out, down, key)?;
            }
            5 => self.release(cipher, out)?,
            6 if !event.0.is_empty() => {
                let message = clipboard_offer(event.0)?;
                for part in message.chunks(60 * 1024) {
                    cipher.append(out, part)?;
                }
            }
            7 => cipher.append(out, &[0x0b, 0, 0, 0, 0, 0, 0, 0])?,
            _ => {}
        }
        Ok(queued)
    }
}

fn clipboard_offer(text: &[u8]) -> Result<Vec<u8>> {
    ensure!(text.len() <= CLIPBOARD_LIMIT, "剪贴板文本过长");
    let mut inner = Vec::new();
    inner.extend_from_slice(&1u32.to_be_bytes());
    inner.extend_from_slice(&(CLIPBOARD_FORMAT.len() as u32).to_be_bytes());
    inner.extend_from_slice(CLIPBOARD_FORMAT);
    inner.extend_from_slice(&[0; 8]);
    inner.extend_from_slice(&(text.len() as u32).to_be_bytes());
    inner.extend_from_slice(text);
    // The Mac uses Z_SYNC_FLUSH for its clipboard stream.
    let mut compressed = Vec::with_capacity(inner.len() + inner.len() / 32 + 128);
    Compress::new(Compression::default(), true).compress_vec(
        &inner,
        &mut compressed,
        FlushCompress::Sync,
    )?;
    let mut message = vec![0x1f, 0, 0, 0, 0, 0, 0, 0];
    message.extend_from_slice(&(inner.len() as u32).to_be_bytes());
    message.extend_from_slice(&(compressed.len() as u32).to_be_bytes());
    message.extend_from_slice(&compressed);
    Ok(message)
}

pub(crate) struct Cursor {
    pub size: Size,
    pub hotspot: Size,
    pub rgba: Vec<u8>,
}

#[derive(Default)]
pub(crate) struct Controls {
    cursors: HashMap<u32, Cursor>,
    order: VecDeque<u32>,
    clipboard: Vec<u8>,
    clipboard_expected: usize,
}

pub(crate) enum Effect<'a> {
    Cursor(Option<&'a Cursor>),
    Clipboard(String),
}

impl Controls {
    pub fn read(&mut self, message: &[u8], mut effect: impl FnMut(Effect<'_>)) -> Result<()> {
        if self.clipboard_expected > 0 || (message.first() == Some(&0x1f) && message.len() >= 16) {
            if self.clipboard_expected == 0 {
                let size = u32::from_be_bytes(message[12..16].try_into()?) as usize;
                ensure!(size <= CLIPBOARD_LIMIT, "远程剪贴板数据过长");
                self.clipboard_expected = size + 16;
            }
            let max = (self.clipboard_expected - self.clipboard.len()).min(message.len());
            self.clipboard.extend_from_slice(&message[..max]);
            if self.clipboard.len() == self.clipboard_expected {
                self.clipboard_expected = 0;
                let complete = std::mem::take(&mut self.clipboard);
                if complete[2] == 0 {
                    let raw = inflate(&complete[16..], CLIPBOARD_LIMIT)?;
                    let mut data = Slice(&raw);
                    let count = data.u32()?;
                    for _ in 0..count {
                        let name = data.long()?;
                        data.u32()?;
                        for _ in 0..data.u32()? {
                            data.long()?;
                            data.long()?;
                        }
                        let text = data.long()?;
                        if name == CLIPBOARD_FORMAT {
                            effect(Effect::Clipboard(String::from_utf8(text.to_vec())?));
                            break;
                        }
                    }
                }
            }
            return Ok(());
        }
        if message.first() != Some(&0) || message.len() < 4 {
            return Ok(());
        }
        let mut data = Slice(&message[2..]);
        for _ in 0..data.u16()? {
            let x = data.u16()?;
            let y = data.u16()?;
            let width = data.u16()?;
            let height = data.u16()?;
            match data.u32()? as i32 {
                1104 => {
                    let id = data.u32()?;
                    let compressed = data.long()?;
                    let pixels = usize::from(width) * usize::from(height);
                    if !compressed.is_empty() && (1..=256 * 256).contains(&pixels) {
                        let raw = inflate(compressed, 5 * pixels)?;
                        if raw.len() == 5 * pixels {
                            let mut rgba = Vec::with_capacity(4 * pixels);
                            for (bgra, alpha) in raw[..4 * pixels]
                                .as_chunks::<4>()
                                .0
                                .iter()
                                .zip(&raw[4 * pixels..])
                            {
                                rgba.extend_from_slice(&[bgra[2], bgra[1], bgra[0], *alpha]);
                            }
                            if self
                                .cursors
                                .insert(
                                    id,
                                    Cursor {
                                        size: (width, height),
                                        hotspot: (x, y),
                                        rgba,
                                    },
                                )
                                .is_none()
                            {
                                self.order.push_back(id);
                            }
                            if self.order.len() > 64 {
                                if let Some(expired) = self.order.pop_front() {
                                    self.cursors.remove(&expired);
                                }
                            }
                        }
                    }
                    effect(Effect::Cursor(self.cursors.get(&id)));
                }
                1010 | 1011 | 1002 | 1105 | 1107 | 1109 | 1110 => {
                    data.short()?;
                }
                -223 => {}
                _ => break,
            }
        }
        Ok(())
    }
}
