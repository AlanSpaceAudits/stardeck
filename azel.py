#!/usr/bin/env python3
"""Live azimuth / elevation readout from a Seestar (JSON-RPC, TCP 4700).

usage: azel.py [host]      host omitted -> SeeStar.local (mDNS)
       azel.py --selftest
"""
import base64, json, socket, sys, time
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

KEY = serialization.load_pem_private_key(
    (Path(__file__).parent / "seestar_key.pem").read_bytes(), None)


def sign(challenge: str) -> str:
    return base64.b64encode(
        KEY.sign(challenge.encode(), padding.PKCS1v15(), hashes.SHA1())).decode()


class Scope:
    def __init__(self, host):
        self.f = socket.create_connection(
            socket.getaddrinfo(host, 4700, socket.AF_INET)[0][4], timeout=10).makefile("rwb")
        self.id = 0

    def call(self, method, params=None):
        self.id += 1
        msg = {"id": self.id, "method": method}
        if params is not None:
            msg["params"] = params
        self.f.write(json.dumps(msg).encode() + b"\r\n")
        self.f.flush()
        while True:  # skip unsolicited Event lines
            line = self.f.readline()
            if not line:
                raise ConnectionError("scope closed the connection")
            r = json.loads(line)
            if r.get("id") == self.id:
                return r

    def verify(self):
        r = self.call("get_verify_str")
        if r.get("code") == 103:  # old firmware, no handshake
            return
        challenge = r["result"]["str"]
        r = self.call("verify_client", {"sign": sign(challenge), "data": challenge})
        if r.get("code") != 0:
            sys.exit(f"verify_client failed: {r}")


def selftest():
    sig = base64.b64decode(sign("abc"))
    KEY.public_key().verify(sig, b"abc", padding.PKCS1v15(), hashes.SHA1())
    print("ok")


def main():
    if "--selftest" in sys.argv:
        return selftest()
    host = sys.argv[1] if len(sys.argv) > 1 else "SeeStar.local"
    scope = Scope(host)
    scope.verify()
    while True:
        r = scope.call("scope_get_horiz_coord", {"ret_obj": True})
        res = r.get("result")
        if isinstance(res, dict) and "alt_az" in res:
            alt, az = res["alt_az"]
            print(f"\rAz {az:8.3f}°   El {alt:7.3f}°", end="", flush=True)
        else:
            print(f"\r{r}", end="", flush=True)
        time.sleep(1)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print()
