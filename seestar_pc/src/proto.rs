//! Seestar JSON-RPC control channel (TCP 4700).

use std::io::{BufRead, BufReader, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

use base64::Engine;
use rsa::pkcs1v15::SigningKey;
use rsa::pkcs8::DecodePrivateKey;
use sha1::Sha1;
use rsa::signature::{SignatureEncoding, Signer};
use rsa::RsaPrivateKey;
use serde_json::{json, Value};

pub type Res<T> = Result<T, Box<dyn std::error::Error + Send + Sync>>;

pub const CONTROL_PORT: u16 = 4700;

/// RSA-SHA1 PKCS#1 v1.5 signature of the scope's challenge string, base64.
pub fn sign(challenge: &str) -> Res<String> {
    let key = RsaPrivateKey::from_pkcs8_pem(include_str!("../../seestar_key.pem"))?;
    let sig = SigningKey::<Sha1>::new(key).sign(challenge.as_bytes());
    Ok(base64::engine::general_purpose::STANDARD.encode(sig.to_bytes()))
}

pub fn resolve(host: &str, port: u16) -> Res<std::net::SocketAddr> {
    if let Some(a) = (host, port).to_socket_addrs().ok().and_then(|mut it| it.find(|a| a.is_ipv4())) {
        return Ok(a);
    }
    // System resolvers often return only an IPv6 link-local address for .local names,
    // or do not resolve them at all; the scope listens on IPv4 only.
    let ip = mdns_ipv4(host).ok_or_else(|| format!("no IPv4 address for {host}"))?;
    Ok(std::net::SocketAddr::new(ip.into(), port))
}

/// One-shot mDNS A query (RFC 6762) on every local IPv4 interface.
/// Sent from port 5353 as a group member, because host firewalls drop unicast replies to other ports.
fn mdns_ipv4(host: &str) -> Option<std::net::Ipv4Addr> {
    use std::net::{IpAddr, Ipv4Addr, SocketAddrV4};
    const GROUP: Ipv4Addr = Ipv4Addr::new(224, 0, 0, 251);

    let mut query = vec![0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0];
    for label in host.trim_end_matches('.').split('.') {
        query.push(u8::try_from(label.len()).ok()?);
        query.extend_from_slice(label.as_bytes());
    }
    query.extend_from_slice(&[0, 0, 1, 0, 1]); // type A, class IN

    let sock = socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::DGRAM, Some(socket2::Protocol::UDP)).ok()?;
    sock.set_reuse_address(true).ok()?;
    #[cfg(unix)]
    sock.set_reuse_port(true).ok()?;
    sock.bind(&SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 5353).into()).ok()?;
    for iface in if_addrs::get_if_addrs().ok()? {
        if let IpAddr::V4(ip) = iface.ip() {
            if !ip.is_loopback() && sock.join_multicast_v4(&GROUP, &ip).is_ok() && sock.set_multicast_if_v4(&ip).is_ok() {
                let _ = sock.send_to(&query, &SocketAddrV4::new(GROUP, 5353).into());
            }
        }
    }
    let sock: std::net::UdpSocket = sock.into();
    sock.set_read_timeout(Some(Duration::from_millis(200))).ok()?;
    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    let mut buf = [0u8; 1500];
    let wanted = host.trim_end_matches('.').to_ascii_lowercase();
    while std::time::Instant::now() < deadline {
        if let Ok((n, _)) = sock.recv_from(&mut buf) {
            // other hosts answer on the same group; keep only replies that carry our name
            let text = String::from_utf8_lossy(&buf[..n]).to_ascii_lowercase();
            let first_label = wanted.split('.').next().unwrap_or("");
            if text.contains(first_label) {
                if let Some(ip) = first_a_record(&buf[..n]) {
                    return Some(ip);
                }
            }
        }
    }
    None
}

/// First A record in the answer section of a DNS message.
fn first_a_record(msg: &[u8]) -> Option<std::net::Ipv4Addr> {
    fn skip_name(msg: &[u8], mut i: usize) -> Option<usize> {
        loop {
            let len = *msg.get(i)? as usize;
            if len & 0xC0 == 0xC0 {
                return Some(i + 2);
            }
            i += 1 + len;
            if len == 0 {
                return Some(i);
            }
        }
    }
    let count = |o: usize| Some(u16::from_be_bytes([*msg.get(o)?, *msg.get(o + 1)?]) as usize);
    let (questions, answers) = (count(4)?, count(6)?);
    let mut i = 12;
    for _ in 0..questions {
        i = skip_name(msg, i)? + 4;
    }
    for _ in 0..answers {
        i = skip_name(msg, i)?;
        let (rtype, rdlen) = (count(i)?, count(i + 8)?);
        i += 10;
        if rtype == 1 && rdlen == 4 {
            let d = msg.get(i..i + 4)?;
            return Some(std::net::Ipv4Addr::new(d[0], d[1], d[2], d[3]));
        }
        i += rdlen;
    }
    None
}

pub struct Scope {
    r: BufReader<TcpStream>,
    w: TcpStream,
    id: u64,
    /// Unsolicited messages seen while waiting for replies.
    pub events: Vec<Value>,
}

impl Scope {
    pub fn connect(host: &str) -> Res<Self> {
        let s = TcpStream::connect_timeout(&resolve(host, CONTROL_PORT)?, Duration::from_secs(5))?;
        s.set_read_timeout(Some(Duration::from_secs(10)))?;
        s.set_nodelay(true)?;
        let mut scope = Scope { r: BufReader::new(s.try_clone()?), w: s, id: 0, events: Vec::new() };
        scope.verify()?;
        Ok(scope)
    }

    pub fn call(&mut self, method: &str, params: Option<Value>) -> Res<Value> {
        self.id += 1;
        let mut msg = json!({"id": self.id, "method": method});
        if let Some(p) = params {
            msg["params"] = p;
        }
        self.w.write_all(format!("{msg}\r\n").as_bytes())?;
        loop {
            let mut line = String::new();
            if self.r.read_line(&mut line)? == 0 {
                return Err("scope closed the connection".into());
            }
            let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
            if v["id"].as_u64() == Some(self.id) {
                return Ok(v);
            }
            self.events.push(v);
        }
    }

    fn verify(&mut self) -> Res<()> {
        let r = self.call("get_verify_str", None)?;
        if r["code"] == 103 {
            return Ok(()); // old firmware, no handshake
        }
        let challenge = r["result"]["str"].as_str().ok_or("no challenge in get_verify_str")?.to_owned();
        let r = self.call("verify_client", Some(json!({"sign": sign(&challenge)?, "data": challenge})))?;
        if r["code"] != 0 {
            return Err(format!("verify_client failed: {r}").into());
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn parses_a_record_after_compressed_name() {
        let mut msg = vec![0, 0, 0x84, 0, 0, 1, 0, 1, 0, 0, 0, 0];
        msg.extend_from_slice(b"\x07SeeStar\x05local\x00\x00\x01\x00\x01"); // question
        msg.extend_from_slice(&[0xC0, 12, 0, 1, 0x80, 1, 0, 0, 0, 120, 0, 4, 192, 168, 0, 79]); // answer
        assert_eq!(super::first_a_record(&msg), Some(std::net::Ipv4Addr::new(192, 168, 0, 79)));
        assert_eq!(super::first_a_record(&msg[..20]), None);
    }

    // Live check against a scope on the network: cargo test -- --ignored
    #[test]
    #[ignore]
    fn mdns_finds_the_scope() {
        assert!(super::mdns_ipv4("SeeStar.local").is_some());
    }

    // Reference value from the Python client (azel.py) for the same key and input.
    #[test]
    fn sign_matches_python_reference() {
        assert_eq!(super::sign("abc").unwrap(), include_str!("../tests/sign_abc.txt").trim());
    }
}
