// SPDX-License-Identifier: AGPL-3.0-or-later
mod auth;
mod avc;
mod control;
mod media;
mod offer;
mod record;
mod srtp;
mod wire;

type Size = (u16, u16);

use crate::{
    avc::Format,
    control::{Controls, Effect, Inputs},
    media::{Counters, VideoOutput},
};
use anyhow::{anyhow, ensure, Context, Result};
use jni::{
    objects::{JByteArray, JObject, JString, JValue},
    sys::{jboolean, jint},
    JNIEnv,
};
use std::{
    io::Write,
    net::{Shutdown, SocketAddr, ToSocketAddrs},
    sync::{
        atomic::{AtomicBool, Ordering::Relaxed},
        Mutex,
    },
    thread,
    time::{Duration, Instant},
};
use zeroize::Zeroizing;

const LOG_TAG: &str = "CloudBookRemote";

struct Android<'env, 'local, 'object> {
    env: &'env mut JNIEnv<'local>,
    session: &'object JObject<'object>,
}

impl Android<'_, '_, '_> {
    fn signal(&mut self, name: &str, sig: &str, args: &[JValue<'_, '_>]) -> Result<()> {
        self.env.call_method(self.session, name, sig, args)?;
        Ok(())
    }

    fn cancelled(&mut self) -> Result<bool> {
        Ok(self
            .env
            .call_method(self.session, "isCancelled", "()Z", &[])?
            .z()?)
    }

    fn connected(&mut self, (width, height): Size) -> Result<()> {
        self.signal(
            "onConnected",
            "(II)V",
            &[JValue::Int(width.into()), JValue::Int(height.into())],
        )
    }

    fn input(&mut self) -> Result<Option<Vec<u8>>> {
        self.env.with_local_frame(4, |env| {
            let event = env
                .call_method(self.session, "pollInput", "(I)[B", &[JValue::Int(16)])?
                .l()?;
            if event.is_null() {
                return Ok(None);
            }
            Ok(Some(env.convert_byte_array(JByteArray::from(event))?))
        })
    }

    fn audio(&mut self, data: &[u8]) -> Result<()> {
        self.env.with_local_frame(4, |env| {
            let data = env.byte_array_from_slice(data)?;
            env.call_method(
                self.session,
                "onAudioUnit",
                "([B)V",
                &[JValue::Object(data.as_ref())],
            )?;
            Ok(())
        })
    }

    fn effect(&mut self, effect: Effect<'_>) -> Result<()> {
        self.env.with_local_frame(4, |env| {
            match effect {
                Effect::Clipboard(text) => {
                    let text = env.new_string(text)?;
                    env.call_method(
                        self.session,
                        "onClipboardText",
                        "(Ljava/lang/String;)V",
                        &[JValue::Object(text.as_ref())],
                    )?;
                }
                Effect::Cursor(cursor) => {
                    let (w, h, x, y, data) = if let Some(cursor) = cursor {
                        (
                            cursor.size.0,
                            cursor.size.1,
                            cursor.hotspot.0,
                            cursor.hotspot.1,
                            env.byte_array_from_slice(&cursor.rgba)?,
                        )
                    } else {
                        (0, 0, 0, 0, JByteArray::default())
                    };
                    env.call_method(
                        self.session,
                        "onCursor",
                        "(IIII[B)V",
                        &[
                            JValue::Int(w.into()),
                            JValue::Int(h.into()),
                            JValue::Int(x.into()),
                            JValue::Int(y.into()),
                            JValue::Object(data.as_ref()),
                        ],
                    )?;
                }
            }
            Ok(())
        })
    }
}

impl VideoOutput for Android<'_, '_, '_> {
    fn configure(&mut self, format: &Format) -> Result<()> {
        self.env.with_local_frame(4, |env| {
            let sps = env.byte_array_from_slice(&format.sps)?;
            let pps = env.byte_array_from_slice(&format.pps)?;
            env.call_method(
                self.session,
                "onVideoFormat",
                "(III[B[B)V",
                &[
                    JValue::Int(format.size.0.into()),
                    JValue::Int(format.size.1.into()),
                    JValue::Int(format.matrix.into()),
                    JValue::Object(sps.as_ref()),
                    JValue::Object(pps.as_ref()),
                ],
            )?;
            Ok(())
        })
    }

    fn offer(&mut self, timestamp: u32, picture: &[u8], intra: bool) -> Result<bool> {
        self.env.with_local_frame(4, |env| {
            // onVideoUnit reads the view synchronously into owned or codec memory.
            let data =
                unsafe { env.new_direct_byte_buffer(picture.as_ptr().cast_mut(), picture.len())? };
            Ok(env
                .call_method(
                    self.session,
                    "onVideoUnit",
                    "(JLjava/nio/ByteBuffer;Z)Z",
                    &[
                        JValue::Long(timestamp.into()),
                        JValue::Object(data.as_ref()),
                        JValue::Bool(u8::from(intra)),
                    ],
                )?
                .z()?)
        })
    }

    fn needs_keyframe(&mut self) -> Result<bool> {
        Ok(self
            .env
            .call_method(self.session, "needsKeyframe", "()Z", &[])?
            .z()?)
    }
}

struct Destination<'a> {
    host: &'a str,
    port: u16,
    user: &'a str,
    password: &'a str,
    size: Size,
    virtual_screen: bool,
    audio: bool,
}

fn monotonic_ns() -> Option<u64> {
    let mut time = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    // Android System.nanoTime uses CLOCK_MONOTONIC as well.
    let result = unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut time) };
    (result == 0).then(|| time.tv_sec as u64 * 1_000_000_000 + time.tv_nsec as u64)
}

fn run(frontend: &mut Android<'_, '_, '_>, destination: Destination<'_>) -> Result<()> {
    let peer = (destination.host, destination.port)
        .to_socket_addrs()?
        .find(SocketAddr::is_ipv4)
        .context("Mac 地址没有 IPv4 记录")?;
    let mut video_peer = peer;
    video_peer.set_port(destination.port.checked_add(1).context("无效的 Mac 端口")?);
    let audio = media::bind(destination.port).context("无法打开音频 UDP 端口")?;
    let video = media::bind(video_peer.port()).context("无法打开视频 UDP 端口")?;
    if frontend.cancelled()? {
        return Ok(());
    }
    control::warmup(peer).context("无法连接 Mac 的屏幕共享服务")?;
    if frontend.cancelled()? {
        return Ok(());
    }
    let bootstrap = AtomicBool::new(false);
    // Keep both LAN UDP openings alive while the Mac starts its video encoder.
    let mut connection = thread::scope(|scope| {
        let punch = scope.spawn(|| {
            while !bootstrap.load(Relaxed) {
                let _ = audio.send_to(&[0], peer);
                let _ = video.send_to(&[0], video_peer);
                thread::park_timeout(Duration::from_millis(100));
            }
        });
        let result = control::connect(
            peer,
            destination.user,
            destination.password,
            destination.size,
            destination.virtual_screen,
            destination.audio,
        );
        bootstrap.store(true, Relaxed);
        punch.thread().unpark();
        result
    })?;
    if frontend.cancelled()? {
        return Ok(());
    }
    log::info!(
        "Rust High Performance session: {}x{}",
        connection.size.0,
        connection.size.1
    );
    frontend.connected(connection.size)?;
    let stats = Counters::new(connection.size);
    let mut receive_socket = connection.socket.try_clone()?;
    let jvm = frontend.env.get_java_vm()?;
    let session = frontend.env.new_global_ref(frontend.session)?;
    let stop = AtomicBool::new(false);
    let error: Mutex<Option<anyhow::Error>> = Mutex::new(None);
    let record_error = |result: Result<()>| {
        if let Err(failure) = result {
            if !stop.swap(true, Relaxed) {
                *error.lock().expect("session error lock") = Some(failure);
            }
        }
    };
    thread::scope(|scope| {
        scope.spawn(|| {
            record_error((|| {
                let mut env = jvm.attach_current_thread_as_daemon()?;
                let output = Android {
                    env: &mut env,
                    session: session.as_obj(),
                };
                media::video(
                    &video,
                    &audio,
                    peer,
                    &connection.offer,
                    &stats,
                    &stop,
                    output,
                )
            })());
        });
        scope.spawn(|| {
            record_error((|| {
                let mut env = jvm.attach_current_thread_as_daemon()?;
                let mut output = Android {
                    env: &mut env,
                    session: session.as_obj(),
                };
                media::audio(
                    &audio,
                    peer.ip(),
                    &connection.offer,
                    destination.audio,
                    &stop,
                    |data| output.audio(data),
                )
            })());
        });
        scope.spawn(|| {
            record_error((|| {
                let mut env = jvm.attach_current_thread_as_daemon()?;
                let mut output = Android {
                    env: &mut env,
                    session: session.as_obj(),
                };
                let mut controls = Controls::default();
                let mut apply = |message: &[u8]| {
                    if let Err(failure) = controls.read(message, |effect| {
                        if let Err(failure) = output.effect(effect) {
                            log::warn!("RFB callback: {failure:#}");
                        }
                    }) {
                        log::debug!("RFB message skipped: {failure:#}");
                    }
                };
                for message in connection.pending.drain(..) {
                    apply(&message);
                }
                while !stop.load(Relaxed) {
                    if let Some(message) = connection.receive.read(&mut receive_socket)? {
                        apply(&message);
                    }
                }
                Ok(())
            })());
        });
        let mut inputs = Inputs::new(connection.precise_scroll);
        let mut records = Vec::with_capacity(256);
        let result = (|| -> Result<()> {
            let mut next_cursor = Instant::now();
            let mut start_report = Instant::now();
            let (mut previous_frames, mut previous_bytes, mut previous_losses) = (0, 0, 0);
            let mut send_delays = Vec::new();
            while !stop.load(Relaxed) && !frontend.cancelled()? {
                if let Some(event) = frontend.input()? {
                    records.clear();
                    let queued = inputs.dispatch(
                        &mut connection.send,
                        &mut records,
                        &event,
                        stats.size(),
                    )?;
                    if !records.is_empty() {
                        connection.socket.write_all(&records)?;
                        if let Some(now) = monotonic_ns() {
                            send_delays.push((now.saturating_sub(queued)) as f64 / 1e6);
                        }
                    }
                }
                let now = Instant::now();
                if now >= next_cursor {
                    connection
                        .send
                        .send(&mut connection.socket, &control::CURSOR_POLL)?;
                    next_cursor = now + Duration::from_millis(500);
                }
                let elapsed = now - start_report;
                if elapsed >= Duration::from_secs(5) {
                    let frames = frontend
                        .env
                        .call_method(frontend.session, "outputFrames", "()J", &[])?
                        .j()?;
                    let losses = stats.losses.load(Relaxed);
                    let bytes = stats.bytes.load(Relaxed);
                    frontend.signal(
                        "onStatistics",
                        "(JJ)V",
                        &[JValue::Long(losses as i64), JValue::Long(bytes as i64)],
                    )?;
                    send_delays.sort_unstable_by(f64::total_cmp);
                    let p95 = send_delays
                        .get(95 * send_delays.len().saturating_sub(1) / 100)
                        .copied()
                        .unwrap_or(0.0);
                    log::info!("Rust video: {:.1} decoded fps, {:.1} Mb/s, {} lost packets; input dispatch p95 {:.2} ms ({} events)",
                        (frames - previous_frames) as f64 / elapsed.as_secs_f64(),
                        (bytes - previous_bytes) as f64 * 8e-6 / elapsed.as_secs_f64(),
                        losses - previous_losses, p95, send_delays.len());
                    start_report = now;
                    previous_frames = frames;
                    previous_losses = losses;
                    previous_bytes = bytes;
                    send_delays.clear();
                }
            }
            Ok(())
        })();
        records.clear();
        if inputs.release(&mut connection.send, &mut records).is_ok() && !records.is_empty() {
            let _ = connection.socket.write_all(&records);
        }
        stop.store(true, Relaxed);
        let _ = connection.socket.shutdown(Shutdown::Both);
        result
    })?;
    if let Some(error) = error.into_inner().expect("session error lock") {
        return Err(error);
    }
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_io_github_kuaner_cloudbook_remote_RemoteSession_runNative(
    mut env: JNIEnv<'_>,
    session: JObject<'_>,
    host: JString<'_>,
    port: jint,
    user: JString<'_>,
    password: JString<'_>,
    width: jint,
    height: jint,
    virtual_screen: jboolean,
    audio: jboolean,
) {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag(LOG_TAG)
            .with_max_level(log::LevelFilter::Info),
    );
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<()> {
        let host: String = env.get_string(&host)?.into();
        let user: String = env.get_string(&user)?.into();
        let password = Zeroizing::new(String::from(env.get_string(&password)?));
        let size = (u16::try_from(width)?, u16::try_from(height)?);
        ensure!(size.0 > 0 && size.1 > 0, "无效的远程画面尺寸");
        let virtual_screen = virtual_screen != 0;
        let audio = audio != 0;
        let mut frontend = Android {
            env: &mut env,
            session: &session,
        };
        run(
            &mut frontend,
            Destination {
                host: &host,
                user: &user,
                password: &password,
                port: u16::try_from(port)?,
                size,
                virtual_screen,
                audio,
            },
        )
    }))
    .unwrap_or_else(|_| Err(anyhow!("远程会话内部错误")));
    if let Err(error) = result {
        log::warn!(target: LOG_TAG, "Session ended: {error:#}");
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
        if let Ok(message) = env.new_string(format!("{error:#}")) {
            let _ = env.call_method(
                &session,
                "onFailure",
                "(Ljava/lang/String;)V",
                &[JValue::Object(message.as_ref())],
            );
        }
    }
}

#[cfg(test)]
fn test_hex(text: &str) -> Vec<u8> {
    (0..text.len())
        .step_by(2)
        .map(|at| u8::from_str_radix(&text[at..at + 2], 16).unwrap())
        .collect()
}
