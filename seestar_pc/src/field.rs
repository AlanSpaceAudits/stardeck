//! In the field the PC joins the Seestar's own hotspot; the gateway of that network is the scope.

use std::net::Ipv4Addr;

/// Gateways to try when the scope's name does not answer: those of Wi-Fi interfaces first, then the
/// default route's.
pub fn gateway_candidates() -> Vec<Ipv4Addr> {
    let mut out = Vec::new();
    let interfaces = netdev::get_interfaces();
    let wifi = interfaces.iter().filter(|i| i.if_type == netdev::prelude::InterfaceType::Wireless80211);
    let default = interfaces.iter().filter(|i| i.default);
    for i in wifi.chain(default) {
        for ip in i.gateway.iter().flat_map(|g| g.ipv4.iter()) {
            if !ip.is_unspecified() && !out.contains(ip) {
                out.push(*ip);
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    #[test]
    fn candidates_are_plain_ipv4() {
        for ip in super::gateway_candidates() {
            assert!(!ip.is_unspecified() && !ip.is_loopback(), "{ip}");
        }
    }
}
