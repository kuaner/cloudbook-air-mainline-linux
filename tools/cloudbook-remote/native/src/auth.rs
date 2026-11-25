// SPDX-License-Identifier: AGPL-3.0-or-later
// Apple SRP exchange based on iShareScreen's proxy/protocol/auth.py.
use crate::wire::{read_blob, read_bytes, write_blob, Slice};
use anyhow::{ensure, Result};
use pbkdf2::pbkdf2_hmac;
use rand::{rngs::OsRng, RngCore};
use rsa::{
    pkcs1::DecodeRsaPublicKey, pkcs8::DecodePublicKey, traits::PublicKeyParts, BigUint,
    Pkcs1v15Encrypt, RsaPublicKey,
};
use sha2::{Digest, Sha256, Sha512};
use std::io::{Read, Write};
use subtle::ConstantTimeEq;
use zeroize::Zeroizing;

const GROUP_HASH: [u8; 32] = [
    0x4e, 0xe9, 0x51, 0x87, 0x68, 0x2b, 0xcb, 0x23, 0x0a, 0xd2, 0x6a, 0x95, 0x20, 0x5f, 0x69, 0x20,
    0xe8, 0x47, 0x08, 0xf6, 0x25, 0x1b, 0x38, 0x94, 0x32, 0x9b, 0x09, 0xec, 0x23, 0x91, 0x9e, 0x33,
];
const MODULUS_LEN: usize = 512;

pub(crate) fn protocol_version(io: &mut (impl Read + Write)) -> Result<Vec<u8>> {
    let version = read_bytes(io, 12)?;
    ensure!(
        version.starts_with(b"RFB "),
        "目标没有提供 RFB 屏幕共享服务"
    );
    io.write_all(b"RFB 003.889\n")?;
    let count = read_bytes(io, 1)?[0];
    read_bytes(io, count.into())
}

fn rsa_envelope(body: &[u8], len: usize) -> Result<Vec<u8>> {
    let mut out = b"\x01\x00RSA1\x00\x02".to_vec();
    ensure!(out.len() + 2 + body.len() <= len, "RSA1 消息过长");
    out.extend_from_slice(&u16::try_from(body.len())?.to_be_bytes());
    out.extend_from_slice(body);
    out.resize(len, 0);
    Ok(out)
}

fn hash(parts: &[&[u8]]) -> [u8; 64] {
    let mut sha = Sha512::new();
    for part in parts {
        sha.update(part);
    }
    sha.finalize().into()
}

fn pad(integer: &BigUint) -> [u8; MODULUS_LEN] {
    let value = integer.to_bytes_be();
    let mut padded = [0; MODULUS_LEN];
    padded[MODULUS_LEN - value.len()..].copy_from_slice(&value);
    padded
}

pub(crate) fn authenticate(
    io: &mut (impl Read + Write),
    user: &str,
    password: &str,
) -> Result<[u8; 16]> {
    ensure!(
        protocol_version(io)?.contains(&0x21),
        "Mac 未提供 Apple SRP 登录"
    );
    io.write_all(b"\x21\x00\x00\x00\x0a\x01\x00RSA1\x00\x00\x00\x00")?;
    let rsa = read_blob(io, 6, 4096)?;
    let mut rsa = Slice(&rsa[2..]);
    let der = rsa.long()?;
    let public_key =
        RsaPublicKey::from_public_key_der(der).or_else(|_| RsaPublicKey::from_pkcs1_der(der))?;
    ensure!(public_key.n().bits() == 2048, "无效的 Mac RSA 公钥");
    let mut identity = Vec::new();
    identity.extend_from_slice(&u32::try_from(user.len() + 7)?.to_be_bytes());
    identity.extend_from_slice(&u32::try_from(user.len())?.to_be_bytes());
    identity.extend_from_slice(user.as_bytes());
    identity.extend_from_slice(&[0; 3]);
    let encrypted = public_key.encrypt(&mut OsRng, Pkcs1v15Encrypt, &identity)?;
    write_blob(io, &rsa_envelope(&encrypted, 650)?)?;

    let challenge = read_blob(io, 1000, 4096)?;
    let mut challenge = Slice(&challenge[12..]);
    ensure!(challenge.u8()? == 0, "无效的 SRP 群参数");
    let n_bytes = challenge.take(MODULUS_LEN)?;
    ensure!(
        Sha256::digest(n_bytes)[..] == GROUP_HASH,
        "Mac 提供了未知的 SRP 群"
    );
    ensure!(challenge.short()? == [5], "无效的 SRP 生成元");
    let salt_len = challenge.u8()?;
    let salt = challenge.take(salt_len.into())?;
    let b = BigUint::from_bytes_be(challenge.short()?);
    let n = BigUint::from_bytes_be(n_bytes);
    ensure!(b != BigUint::from(0u8) && b < n, "无效的 SRP 公共值");
    let iterations = challenge.u64()?;
    ensure!((1..=1_000_000).contains(&iterations), "无效的 SRP 迭代次数");
    let capabilities = challenge.short()?;
    let mut random = Zeroizing::new([0; 64]);
    OsRng.fill_bytes(&mut random[..]);
    let secret = BigUint::from_bytes_be(&random[..]) + 1u8;
    let g = BigUint::from(5u8);
    let g_bytes = pad(&g);
    let a_bytes = pad(&g.modpow(&secret, &n));
    let b_bytes = pad(&b);
    let k = BigUint::from_bytes_be(&hash(&[n_bytes, &g_bytes]));
    let u = BigUint::from_bytes_be(&hash(&[&a_bytes, &b_bytes]));
    let mut derived = Zeroizing::new([0; 128]);
    pbkdf2_hmac::<Sha512>(
        password.as_bytes(),
        salt,
        iterations as u32,
        &mut derived[..],
    );
    let x = BigUint::from_bytes_be(&hash(&[salt, &hash(&[b":", &derived[..]])]));
    let base = (&b + &n - (k * g.modpow(&x, &n)) % &n) % &n;
    let key = Zeroizing::new(hash(&[&pad(&base.modpow(&(secret + u * x), &n))]));
    let mut prefix = hash(&[n_bytes]);
    for (target, source) in prefix.iter_mut().zip(hash(&[&g_bytes])) {
        *target ^= source;
    }
    let proof = hash(&[&prefix, &hash(&[b""]), salt, &a_bytes, &b_bytes, &key[..]]);

    let mut answer = Vec::new();
    answer.extend_from_slice(&(MODULUS_LEN as u16).to_be_bytes());
    answer.extend_from_slice(&a_bytes);
    answer.push(64);
    answer.extend_from_slice(&proof);
    answer.extend_from_slice(&u16::try_from(capabilities.len())?.to_be_bytes());
    answer.extend_from_slice(capabilities);
    answer.push(16);
    let mut client_iv = [0; 16];
    OsRng.fill_bytes(&mut client_iv);
    answer.extend_from_slice(&client_iv);
    let mut body = Vec::new();
    write_blob(&mut body, &answer)?;
    write_blob(io, &rsa_envelope(&body, 1076)?)?;

    let result = read_blob(io, 75, 4096)?;
    let mut result = Slice(&result[4..]);
    let mut section = Slice(result.short()?);
    let mut inner = Slice(section.long()?);
    ensure!(
        inner.u8()? == 64
            && bool::from(inner.take(64)?.ct_eq(&hash(&[&a_bytes, &proof, &key[..]]))),
        "Mac 的 SRP 服务端证明校验失败"
    );
    ensure!(read_bytes(io, 4)? == [0; 4], "Mac 拒绝登录");
    Ok(Sha256::digest(&key[..])[..16].try_into()?)
}
