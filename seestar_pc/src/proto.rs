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
    // the system resolver often returns only the IPv6 link-local address for .local names
    let out = std::process::Command::new("avahi-resolve").args(["-4", "-n", host]).output()?;
    let ip = String::from_utf8_lossy(&out.stdout).split_whitespace().nth(1).map(str::to_owned);
    let ip = ip.ok_or_else(|| format!("no IPv4 address for {host}"))?;
    Ok(std::net::SocketAddr::new(ip.parse()?, port))
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
    // Reference value from the Python client (azel.py) for the same key and input.
    #[test]
    fn sign_matches_python_reference() {
        assert_eq!(super::sign("abc").unwrap(), include_str!("../tests/sign_abc.txt").trim());
    }
}
