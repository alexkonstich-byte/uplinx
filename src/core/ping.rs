//! TCP ping and the "is another VPN faking handshakes?" check.

use crate::model::ProxyNode;
use std::net::{TcpStream, ToSocketAddrs};
use std::time::{Duration, Instant};

pub const PING_FAILED: i32 = -1;
pub const PING_UNRELIABLE: i32 = -3;

pub fn tcp(n: &ProxyNode, timeout_ms: u64) -> i32 {
    let Ok(mut addrs) = (n.address.as_str(), n.port).to_socket_addrs() else { return PING_FAILED };
    let Some(addr) = addrs.next() else { return PING_FAILED };
    let t0 = Instant::now();
    match TcpStream::connect_timeout(&addr, Duration::from_millis(timeout_ms)) {
        Ok(_) => (t0.elapsed().as_millis() as i32).max(1),
        Err(_) => PING_FAILED,
    }
}

/// True when connecting to TEST-NET-1 (RFC 5737, cannot exist) "succeeds": a foreign TUN is completing handshakes.
pub fn handshakes_faked() -> bool {
    TcpStream::connect_timeout(&"192.0.2.1:443".parse().unwrap(), Duration::from_millis(800)).is_ok()
}
