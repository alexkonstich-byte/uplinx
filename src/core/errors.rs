//! Raw core / network errors in plain words.

use crate::t;

#[derive(Clone, Debug, PartialEq)]
pub struct Friendly { pub title: String, pub hint: String, pub details: String }

pub fn explain(raw: &str) -> Friendly {
    let s = raw.to_lowercase();
    let has = |x: &str| s.contains(x);
    let (title, hint) = if has("only one usage of each socket address") || has("address already in use") || has("bind:") {
        ("Port already in use", "Another program uses the proxy port. Change the SOCKS/HTTP ports in settings or close the other VPN client.")
    } else if has("wintun") || has("access is denied") || has("access denied") {
        ("Couldn't create the TUN adapter", "Administrator rights are required and wintun.dll must be next to xray.exe. Restart Uplinx as administrator.")
    } else if has("allowinsecure") {
        ("Outdated allowInsecure parameter", "Xray no longer allows disabling certificate checks. Set the server certificate's SHA-256 (pinnedPeerCertSha256).")
    } else if (has("geosite") && (has("not found") || has("failed to load"))) || (has("geoip") && has("not found")) {
        ("Missing geo database", "A rule refers to a category that isn't in the current geo databases. Update them or change the source in settings.")
    } else if has("failed to parse") || has("invalid character") || has("unknown field") || has("infra/conf") {
        ("Server configuration error", "The link or config contains something Xray doesn't accept. Check the server in the editor or open the details.")
    } else if has("reality") && (has("verify") || has("public key") || has("publickey")) {
        ("REALITY error", "The public key or shortId doesn't match. Update the subscription or check the server parameters.")
    } else if has("x509") || has("certificate") {
        ("Certificate error", "The server presented a certificate that failed verification. Check the SNI or set the certificate's SHA-256.")
    } else if has("timeout") || has("timed out") || has("deadline exceeded") {
        ("Server not responding", "The server is unreachable or blocked. Try another server or transport.")
    } else if has("connection refused") {
        ("Server refused the connection", "Nothing listens on this port. The server may be down or moved — update the subscription.")
    } else if has("no such host") || has("lookup") || has("dns") {
        ("Server address not found", "DNS couldn't find the server. Check your internet or the server address.")
    } else if has("amneziawg") {
        ("AmneziaWG error", "The AmneziaWG tunnel didn't start. Check the config and that the AmneziaWG client is installed.")
    } else if has("github") || has("http 4") || has("http 5") {
        ("Download failed", "The download server returned an error. Check your internet and try again later.")
    } else {
        let first = raw.lines().find(|l| !l.trim().is_empty()).unwrap_or("").chars().take(160).collect::<String>();
        return Friendly { title: t!("Couldn't connect"), hint: if first.is_empty() { t!("Unknown error") } else { first }, details: raw.trim().into() };
    };
    Friendly { title: t!(title), hint: t!(hint), details: raw.trim().into() }
}
