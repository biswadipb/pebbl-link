#!/usr/bin/env python3
"""Prints the BLE writes and notifications from an Android Bluetooth HCI snoop log (btsnoop_hci.log).

Usage: python3 tools/btsnoop_att.py btsnoop_hci.log

Shows, in order, what the phone wrote to the watch (TX) and what the watch sent back (RX), with the
characteristic UUID when the capture includes the service discovery. Capture a session where the
vendor app is connected and you do one thing at a time (sync time, find watch, send a message).
"""
import struct
import sys

ATT_CID = 0x0004
OPCODES = {
    0x0A: "READ", 0x0B: "READ-RSP", 0x12: "WRITE", 0x13: "WRITE-RSP", 0x52: "WRITE-CMD",
    0x1B: "NOTIFY", 0x1D: "INDICATE", 0x09: "TYPE-RSP",
}


def uuid_text(raw: bytes) -> str:
    if len(raw) == 2:
        return "%04x" % struct.unpack("<H", raw)[0]
    return "-".join(raw[::-1].hex()[i:j] for i, j in ((0, 8), (8, 12), (12, 16), (16, 20), (20, 32)))


def records(path):
    with open(path, "rb") as f:
        head = f.read(16)
        if head[:8] != b"btsnoop\0":
            sys.exit("Not a btsnoop file")
        while True:
            h = f.read(24)
            if len(h) < 24:
                return
            _orig, incl, flags, _drops, ts = struct.unpack(">IIIIq", h)
            yield ts, flags, f.read(incl)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    path = sys.argv[1]
    names = {}  # ATT handle -> uuid, filled from the capture's own service discovery
    partial = {}  # ACL handle -> [expected length, bytes so far]
    first = None
    for ts, flags, data in records(path):
        if not data or data[0] != 0x02:  # only ACL data packets
            continue
        handle_flags, acl_len = struct.unpack("<HH", data[1:5])
        conn, pb = handle_flags & 0x0FFF, (handle_flags >> 12) & 0x3
        payload = data[5:5 + acl_len]
        if pb == 0x2:  # start of an L2CAP packet
            l2_len, cid = struct.unpack("<HH", payload[:4])
            partial[conn] = [l2_len, cid, payload[4:]]
        elif pb == 0x1 and conn in partial:  # continuation
            partial[conn][2] += payload
        else:
            continue
        l2_len, cid, body = partial[conn]
        if len(body) < l2_len or cid != ATT_CID:
            continue
        del partial[conn]
        received = bool(flags & 1)
        op, rest = body[0], body[1:]

        if op == 0x09 and rest:  # discovery: handle, properties, value handle, uuid
            size = rest[0]
            for i in range(1, len(rest) - size + 1, size):
                item = rest[i:i + size]
                if size in (7, 21):
                    names[struct.unpack("<H", item[3:5])[0]] = uuid_text(item[5:])
        if op not in (0x12, 0x52, 0x1B, 0x1D, 0x0B, 0x0A) or not rest:
            continue
        first = first or ts
        if op in (0x0B, 0x13):
            handle, value = None, rest
        else:
            handle, value = struct.unpack("<H", rest[:2])[0], rest[2:]
        label = names.get(handle, "handle 0x%04x" % handle) if handle is not None else ""
        arrow = "RX" if received else "TX"
        print("%8.3fs %s %-9s %-40s %s" % ((ts - first) / 1e6, arrow, OPCODES[op], label, value.hex(" ")))


if __name__ == "__main__":
    main()
