#!/usr/bin/env python3
"""
Compare WORKING Garmin Explore session (BR_2026-02-09_03, Session 3)
with FAILING GdogTAK session (BR_2026-02-09_05, last session).

Parses btsnoop_hci.log files, extracts ATT writes and notifications,
detects sessions by >30s gaps, and provides side-by-side comparison.
"""

import struct
import datetime
import os
import sys

# ============================================================
# Constants
# ============================================================

BTSNOOP_EPOCH = datetime.datetime(2000, 1, 1, tzinfo=datetime.timezone.utc)

WORKING_FILE = r"C:\PROJECTS\GDOGTAK-WORKSPACE\LOGS\BUG-REPORTS\BR_2026-02-09_03\FS\data\misc\bluetooth\logs\btsnoop_hci.log"
FAILING_FILE = r"C:\PROJECTS\GDOGTAK-WORKSPACE\LOGS\BUG-REPORTS\BR_2026-02-09_05\FS\data\misc\bluetooth\logs\btsnoop_hci.log"

SESSION_GAP_SECONDS = 30

# Garmin command type labels
CMD_LABELS = {
    (0x02, 0x08): "POLL_CONFIG",
    (0x02, 0x09): "CONFIG",
    (0x02, 0x0A): "CONFIG_0A",
    (0x02, 0x0B): "CONFIG_0B",
    (0x02, 0x0C): "CONFIG_0C",
    (0x02, 0x0D): "CONFIG_0D",
    (0x02, 0x0E): "CONFIG_0E",
    (0x02, 0x10): "CMD_10",
    (0x02, 0x11): "COLLAR_SLOT",
    (0x02, 0x12): "CMD_12",
    (0x02, 0x13): "CMD_13",
    (0x02, 0x14): "CMD_14",
    (0x02, 0x15): "CMD_15",
    (0x02, 0x16): "CONFIG_16",
    (0x02, 0x17): "CMD_17",
    (0x02, 0x18): "CMD_18",
    (0x02, 0x19): "CMD_19",
    (0x02, 0x1A): "CMD_1A",
    (0x02, 0x1B): "CMD_1B",
    (0x02, 0x1C): "CMD_1C",
    (0x02, 0x1D): "POS_QUERY",
    (0x02, 0x1E): "CMD_1E",
    (0x02, 0x1F): "CMD_1F",
    (0x02, 0x20): "CMD_20",
    (0x02, 0x29): "RESP_29",
    (0x02, 0x2A): "CMD_2A",
    (0x02, 0x2B): "CMD_2B",
    (0x02, 0x2C): "CMD_2C",
    (0x02, 0x30): "CMD_30",
    (0x02, 0x31): "CMD_31",
    (0x02, 0x32): "CMD_32",
    (0x02, 0x33): "CMD_33",
    (0x02, 0x34): "CMD_34",
    (0x02, 0x35): "COLLAR_RELAY",
    (0x02, 0x36): "CMD_36",
    (0x02, 0x37): "CMD_37",
    (0x02, 0x38): "CMD_38",
    (0x02, 0x39): "CMD_39",
    (0x02, 0x3A): "CMD_3A",
    (0x02, 0x3B): "CMD_3B",
    (0x02, 0x3C): "POSITION_3C",
    (0x02, 0x3D): "CMD_3D",
    (0x02, 0x3E): "CMD_3E",
    (0x02, 0x40): "CMD_40",
    (0x02, 0x41): "CMD_41",
    (0x02, 0x42): "CMD_42",
    (0x02, 0x43): "CMD_43",
    (0x02, 0x44): "DEVICE_REG",
    (0x02, 0x45): "CMD_45",
    (0x02, 0x50): "CMD_50",
    (0x02, 0x51): "CMD_51",
    (0x02, 0x52): "DEVICE_LIST",
    (0x02, 0x53): "CMD_53",
    (0x02, 0x54): "CMD_54",
    (0x02, 0x55): "CMD_55",
    (0x02, 0x60): "CMD_60",
    (0x02, 0x70): "CMD_70",
    (0x02, 0x71): "CMD_71",
    (0x02, 0x72): "CMD_72",
    (0x02, 0x73): "CMD_73",
    (0x02, 0x74): "CMD_74",
    (0x02, 0x75): "CMD_75",
    (0x02, 0x76): "CMD_76",
    (0x02, 0x77): "CMD_77",
    (0x02, 0x78): "CMD_78",
    (0x02, 0x79): "CMD_79",
    (0x02, 0x7A): "POSITION_7A",
    (0x02, 0x7B): "CMD_7B",
    (0x00, 0x00): "KEEPALIVE",
    (0x01, 0x40): "HANDSHAKE",
}

# ============================================================
# Parsing
# ============================================================

def parse_btsnoop(filepath):
    """Parse a btsnoop_hci.log file and return list of ATT packets."""
    packets = []

    with open(filepath, 'rb') as f:
        # Read 16-byte file header
        header = f.read(16)
        if len(header) < 16:
            print(f"ERROR: File too short for header: {filepath}")
            return packets

        # Parse records
        while True:
            rec_hdr = f.read(24)
            if len(rec_hdr) < 24:
                break

            # Record header is 24 bytes: 4+4+4+4+8
            orig_len, incl_len, flags, drops = struct.unpack('>IIII', rec_hdr[:16])
            ts_us = struct.unpack('>Q', rec_hdr[16:24])[0]

            data = f.read(incl_len)
            if len(data) < incl_len:
                break

            # Convert timestamp
            ts_dt = BTSNOOP_EPOCH + datetime.timedelta(microseconds=ts_us)

            is_received = (flags & 0x01) == 1

            # Check for ACL packet (HCI type 0x02)
            if len(data) < 10:
                continue
            if data[0] != 0x02:
                continue

            # L2CAP CID at bytes 7:9 (little-endian)
            l2cap_cid = struct.unpack('<H', data[7:9])[0]
            if l2cap_cid != 0x0004:  # ATT channel
                continue

            att_opcode = data[9]

            if att_opcode in (0x12, 0x52):  # Write Request / Write Command
                if len(data) < 12:
                    continue
                handle = struct.unpack('<H', data[10:12])[0]
                att_data = data[12:]
                packets.append({
                    'type': 'write',
                    'timestamp': ts_dt,
                    'ts_us': ts_us,
                    'handle': handle,
                    'opcode': att_opcode,
                    'data': att_data,
                    'is_received': is_received,
                    'raw_size': len(att_data),
                })
            elif att_opcode == 0x1B:  # Handle Value Notification
                if len(data) < 12:
                    continue
                handle = struct.unpack('<H', data[10:12])[0]
                att_data = data[12:]
                packets.append({
                    'type': 'notification',
                    'timestamp': ts_dt,
                    'ts_us': ts_us,
                    'handle': handle,
                    'opcode': att_opcode,
                    'data': att_data,
                    'is_received': is_received,
                    'raw_size': len(att_data),
                })

    return packets


def detect_sessions(packets):
    """Detect sessions by >30 sec gaps. Returns list of (start_idx, end_idx) tuples."""
    if not packets:
        return []

    sessions = []
    session_start = 0

    for i in range(1, len(packets)):
        gap = (packets[i]['ts_us'] - packets[i-1]['ts_us']) / 1_000_000.0
        if gap > SESSION_GAP_SECONDS:
            sessions.append((session_start, i - 1))
            session_start = i

    sessions.append((session_start, len(packets) - 1))
    return sessions


def extract_command(data):
    """
    Extract the Garmin command type from packet data.

    The data starts with a 2-byte fragment header [base][seq].
    - If first byte & 0x80: it's a multi-fragment packet. The high bit is set.
      base = first_byte & 0x7F, seq = second_byte
    - If first byte < 0x80: single fragment or first fragment.
      base = first_byte, seq = second_byte

    After the 2-byte header, the payload follows.
    If payload[0] == 0x00, then payload[1:3] is the command type (cat, id).

    Returns: (label, is_continuation_frag, cmd_tuple_or_none, frag_info_str)
    """
    if len(data) < 2:
        return ("TOO_SHORT", False, None, "")

    first_byte = data[0]
    second_byte = data[1]

    is_high_bit = (first_byte & 0x80) != 0
    base = first_byte & 0x7F
    seq = second_byte

    frag_str = f"[0x{first_byte:02X} 0x{second_byte:02X}]"

    payload = data[2:]  # Strip 2-byte fragment header

    if len(payload) == 0:
        return (f"EMPTY_HDR", False, None, frag_str)

    # Check if this payload starts a command (first payload byte == 0x00)
    if payload[0] == 0x00 and len(payload) >= 3:
        cmd_cat = payload[1]
        cmd_id = payload[2]
        cmd_tuple = (cmd_cat, cmd_id)
        label = CMD_LABELS.get(cmd_tuple, f"CMD_{cmd_cat:02X}_{cmd_id:02X}")
        if is_high_bit:
            label = f"FRAG>{label}"
        return (label, False, cmd_tuple, frag_str)
    else:
        # Not a command-start payload -- could be continuation data or non-standard
        if is_high_bit:
            return (f"FRAG_DATA[0x{payload[0]:02X}]", True, None, frag_str)
        else:
            return (f"DATA[0x{payload[0]:02X}]", False, None, frag_str)


def format_hex(data, max_bytes=60):
    """Format bytes as hex string, truncating if needed."""
    hex_str = data[:max_bytes].hex()
    # Add spaces every 2 chars for readability
    spaced = ' '.join(hex_str[i:i+2] for i in range(0, len(hex_str), 2))
    if len(data) > max_bytes:
        spaced += f" ... (+{len(data)-max_bytes} bytes)"
    return spaced


def print_separator(char='=', width=130):
    print(char * width)


def print_header(text, char='=', width=130):
    print()
    print(char * width)
    print(f"  {text}")
    print(char * width)


# ============================================================
# Main comparison
# ============================================================

def main():
    print_header("BTSNOOP COMPARISON: WORKING (Garmin Explore) vs FAILING (GdogTAK)")
    print(f"  WORKING file: {WORKING_FILE}")
    print(f"  FAILING file: {FAILING_FILE}")
    print(f"  Working file size: {os.path.getsize(WORKING_FILE):,} bytes")
    print(f"  Failing file size: {os.path.getsize(FAILING_FILE):,} bytes")

    # ---- Parse both files ----
    print("\n  Parsing WORKING file...")
    working_pkts = parse_btsnoop(WORKING_FILE)
    print(f"  Found {len(working_pkts)} ATT packets (writes + notifications)")

    print("  Parsing FAILING file...")
    failing_pkts = parse_btsnoop(FAILING_FILE)
    print(f"  Found {len(failing_pkts)} ATT packets (writes + notifications)")

    # ---- Detect sessions ----
    working_sessions = detect_sessions(working_pkts)
    failing_sessions = detect_sessions(failing_pkts)

    print_header("SESSION DETECTION")

    print(f"\n  WORKING file: {len(working_sessions)} sessions detected")
    for i, (s, e) in enumerate(working_sessions):
        t_start = working_pkts[s]['timestamp']
        t_end = working_pkts[e]['timestamp']
        duration = (working_pkts[e]['ts_us'] - working_pkts[s]['ts_us']) / 1_000_000.0
        n_writes = sum(1 for p in working_pkts[s:e+1] if p['type'] == 'write')
        n_notifs = sum(1 for p in working_pkts[s:e+1] if p['type'] == 'notification')
        print(f"    Session {i}: {t_start.strftime('%H:%M:%S')} - {t_end.strftime('%H:%M:%S')} "
              f"({duration:.1f}s) | {e-s+1} pkts ({n_writes} writes, {n_notifs} notifs)")

    print(f"\n  FAILING file: {len(failing_sessions)} sessions detected")
    for i, (s, e) in enumerate(failing_sessions):
        t_start = failing_pkts[s]['timestamp']
        t_end = failing_pkts[e]['timestamp']
        duration = (failing_pkts[e]['ts_us'] - failing_pkts[s]['ts_us']) / 1_000_000.0
        n_writes = sum(1 for p in failing_pkts[s:e+1] if p['type'] == 'write')
        n_notifs = sum(1 for p in failing_pkts[s:e+1] if p['type'] == 'notification')
        print(f"    Session {i}: {t_start.strftime('%H:%M:%S')} - {t_end.strftime('%H:%M:%S')} "
              f"({duration:.1f}s) | {e-s+1} pkts ({n_writes} writes, {n_notifs} notifs)")

    # ---- Select target sessions ----
    # WORKING: Session 3 (0-indexed) - the reconnect session at ~11:45
    if len(working_sessions) < 4:
        print(f"\n  WARNING: Expected 4 sessions in WORKING file, found {len(working_sessions)}")
        print("  Using last session as fallback")
        w_idx = len(working_sessions) - 1
    else:
        w_idx = 3  # Session 3 (0-indexed)

    # FAILING: Last session
    f_idx = len(failing_sessions) - 1

    w_start, w_end = working_sessions[w_idx]
    f_start, f_end = failing_sessions[f_idx]

    w_session = working_pkts[w_start:w_end+1]
    f_session = failing_pkts[f_start:f_end+1]

    w_t0 = w_session[0]['ts_us']
    f_t0 = f_session[0]['ts_us']

    print_header("SELECTED SESSIONS FOR COMPARISON")
    print(f"  WORKING: Session {w_idx} | Start: {w_session[0]['timestamp'].strftime('%H:%M:%S.%f')} | {len(w_session)} packets")
    print(f"  FAILING: Session {f_idx} | Start: {f_session[0]['timestamp'].strftime('%H:%M:%S.%f')} | {len(f_session)} packets")

    # ---- Extract writes and notifications ----
    w_writes = [p for p in w_session if p['type'] == 'write']
    w_notifs = [p for p in w_session if p['type'] == 'notification']
    f_writes = [p for p in f_session if p['type'] == 'write']
    f_notifs = [p for p in f_session if p['type'] == 'notification']

    print(f"  WORKING: {len(w_writes)} writes, {len(w_notifs)} notifications")
    print(f"  FAILING: {len(f_writes)} writes, {len(f_notifs)} notifications")

    # ============================================================
    # 5. FIRST 20 WRITES side-by-side
    # ============================================================
    print_header("FIRST 20 WRITES - SIDE BY SIDE COMPARISON")

    max_writes = 20

    # Print WORKING writes first
    print(f"\n  --- WORKING (Session {w_idx}) WRITES ---")
    print(f"  {'#':>3} {'Offset':>10} {'Handle':>6} {'Sz':>3} {'Label':>22}   Hex Data")
    print(f"  {'-'*3} {'-'*10} {'-'*6} {'-'*3} {'-'*22}   {'-'*70}")
    for i, pkt in enumerate(w_writes[:max_writes]):
        offset = (pkt['ts_us'] - w_t0) / 1_000_000.0
        label, is_cont, cmd, frag_info = extract_command(pkt['data'])
        hex_data = format_hex(pkt['data'], max_bytes=55)
        print(f"  {i:3d} {offset:10.4f}s 0x{pkt['handle']:04X} {pkt['raw_size']:3d} {label:>22}   {hex_data}")

    # Print FAILING writes
    print(f"\n  --- FAILING (Session {f_idx}) WRITES ---")
    print(f"  {'#':>3} {'Offset':>10} {'Handle':>6} {'Sz':>3} {'Label':>22}   Hex Data")
    print(f"  {'-'*3} {'-'*10} {'-'*6} {'-'*3} {'-'*22}   {'-'*70}")
    for i, pkt in enumerate(f_writes[:max_writes]):
        offset = (pkt['ts_us'] - f_t0) / 1_000_000.0
        label, is_cont, cmd, frag_info = extract_command(pkt['data'])
        hex_data = format_hex(pkt['data'], max_bytes=55)
        print(f"  {i:3d} {offset:10.4f}s 0x{pkt['handle']:04X} {pkt['raw_size']:3d} {label:>22}   {hex_data}")

    # ============================================================
    # 6. FIRST 30 NOTIFICATIONS side-by-side
    # ============================================================
    print_header("FIRST 30 NOTIFICATIONS - SIDE BY SIDE COMPARISON")

    max_notifs = 30

    # Print WORKING notifications
    print(f"\n  --- WORKING (Session {w_idx}) NOTIFICATIONS ---")
    print(f"  {'#':>3} {'Offset':>10} {'Handle':>6} {'Sz':>3} {'Label':>22}   Hex Data")
    print(f"  {'-'*3} {'-'*10} {'-'*6} {'-'*3} {'-'*22}   {'-'*70}")
    for i, pkt in enumerate(w_notifs[:max_notifs]):
        offset = (pkt['ts_us'] - w_t0) / 1_000_000.0
        label, is_cont, cmd, frag_info = extract_command(pkt['data'])
        hex_data = format_hex(pkt['data'], max_bytes=55)
        print(f"  {i:3d} {offset:10.4f}s 0x{pkt['handle']:04X} {pkt['raw_size']:3d} {label:>22}   {hex_data}")

    # Print FAILING notifications
    print(f"\n  --- FAILING (Session {f_idx}) NOTIFICATIONS ---")
    print(f"  {'#':>3} {'Offset':>10} {'Handle':>6} {'Sz':>3} {'Label':>22}   Hex Data")
    print(f"  {'-'*3} {'-'*10} {'-'*6} {'-'*3} {'-'*22}   {'-'*70}")
    for i, pkt in enumerate(f_notifs[:max_notifs]):
        offset = (pkt['ts_us'] - f_t0) / 1_000_000.0
        label, is_cont, cmd, frag_info = extract_command(pkt['data'])
        hex_data = format_hex(pkt['data'], max_bytes=55)
        print(f"  {i:3d} {offset:10.4f}s 0x{pkt['handle']:04X} {pkt['raw_size']:3d} {label:>22}   {hex_data}")

    # ============================================================
    # 7. KEY COMPARISON: First occurrences of key commands
    # ============================================================
    print_header("KEY COMPARISON: FIRST OCCURRENCE OF KEY NOTIFICATIONS")

    def find_first_cmd_notification(notifs, t0, target_cmds):
        """Find first notification matching any of the target command tuples."""
        for pkt in notifs:
            label, is_cont, cmd, frag_info = extract_command(pkt['data'])
            if cmd in target_cmds:
                offset = (pkt['ts_us'] - t0) / 1_000_000.0
                return offset, pkt, label, cmd
        return None, None, None, None

    def count_cmd_in_timewindow(notifs, t0, target_cmd, window_sec):
        """Count notifications matching target_cmd within window_sec of t0."""
        count = 0
        for pkt in notifs:
            offset = (pkt['ts_us'] - t0) / 1_000_000.0
            if offset > window_sec:
                break
            label, is_cont, cmd, frag_info = extract_command(pkt['data'])
            if cmd == target_cmd:
                count += 1
        return count

    # 02_11 collar slot notification
    print("\n  First 02_11 (COLLAR_SLOT) notification:")
    w_off, w_pkt, w_label, _ = find_first_cmd_notification(w_notifs, w_t0, [(0x02, 0x11)])
    f_off, f_pkt, f_label, _ = find_first_cmd_notification(f_notifs, f_t0, [(0x02, 0x11)])
    if w_off is not None:
        print(f"    WORKING: +{w_off:.4f}s | handle=0x{w_pkt['handle']:04X} | {format_hex(w_pkt['data'], 40)}")
    else:
        print(f"    WORKING: NOT FOUND in session")
    if f_off is not None:
        print(f"    FAILING: +{f_off:.4f}s | handle=0x{f_pkt['handle']:04X} | {format_hex(f_pkt['data'], 40)}")
    else:
        print(f"    FAILING: NOT FOUND in session")

    # 02_3C or 02_7A position notification
    print("\n  First 02_3C or 02_7A (POSITION) notification:")
    w_off, w_pkt, w_label, w_cmd = find_first_cmd_notification(w_notifs, w_t0, [(0x02, 0x3C), (0x02, 0x7A)])
    f_off, f_pkt, f_label, f_cmd = find_first_cmd_notification(f_notifs, f_t0, [(0x02, 0x3C), (0x02, 0x7A)])
    if w_off is not None:
        print(f"    WORKING: +{w_off:.4f}s | {w_label} | handle=0x{w_pkt['handle']:04X} | {format_hex(w_pkt['data'], 40)}")
    else:
        print(f"    WORKING: NOT FOUND in session")
    if f_off is not None:
        print(f"    FAILING: +{f_off:.4f}s | {f_label} | handle=0x{f_pkt['handle']:04X} | {format_hex(f_pkt['data'], 40)}")
    else:
        print(f"    FAILING: NOT FOUND in session")

    # 02_29 device list response
    print("\n  First 02_29 (RESP_29) notification:")
    w_off, w_pkt, w_label, _ = find_first_cmd_notification(w_notifs, w_t0, [(0x02, 0x29)])
    f_off, f_pkt, f_label, _ = find_first_cmd_notification(f_notifs, f_t0, [(0x02, 0x29)])
    if w_off is not None:
        print(f"    WORKING: +{w_off:.4f}s | handle=0x{w_pkt['handle']:04X} | {format_hex(w_pkt['data'], 40)}")
    else:
        print(f"    WORKING: NOT FOUND in session")
    if f_off is not None:
        print(f"    FAILING: +{f_off:.4f}s | handle=0x{f_pkt['handle']:04X} | {format_hex(f_pkt['data'], 40)}")
    else:
        print(f"    FAILING: NOT FOUND in session")

    # 02_09 notifications in first 30 seconds
    print("\n  02_09 (CONFIG) notifications in first 30 seconds:")
    w_count = count_cmd_in_timewindow(w_notifs, w_t0, (0x02, 0x09), 30.0)
    f_count = count_cmd_in_timewindow(f_notifs, f_t0, (0x02, 0x09), 30.0)
    print(f"    WORKING: {w_count}")
    print(f"    FAILING: {f_count}")

    # 02_16 config notifications in first 30 seconds
    print("\n  02_16 (CONFIG_16) notifications in first 30 seconds:")
    w_count = count_cmd_in_timewindow(w_notifs, w_t0, (0x02, 0x16), 30.0)
    f_count = count_cmd_in_timewindow(f_notifs, f_t0, (0x02, 0x16), 30.0)
    print(f"    WORKING: {w_count}")
    print(f"    FAILING: {f_count}")

    # ============================================================
    # 8. ALL notification command types in first 60s - both sessions
    # ============================================================
    def analyze_notifications_in_window(notifs, t0, window_sec, session_name):
        """Analyze all notification command types in the first window_sec seconds."""
        cmd_counts = {}
        cmd_first_seen = {}
        total = 0
        frag_data_count = 0
        data_count = 0

        for pkt in notifs:
            offset = (pkt['ts_us'] - t0) / 1_000_000.0
            if offset > window_sec:
                break
            total += 1
            label, is_cont, cmd, frag_info = extract_command(pkt['data'])
            if is_cont:
                frag_data_count += 1
                continue
            if cmd is not None:
                key = f"{cmd[0]:02X}_{cmd[1]:02X}"
                cmd_counts[key] = cmd_counts.get(key, 0) + 1
                if key not in cmd_first_seen:
                    cmd_first_seen[key] = offset
            else:
                data_count += 1

        return cmd_counts, cmd_first_seen, total, frag_data_count, data_count

    print_header(f"WORKING SESSION: ALL NOTIFICATION COMMAND TYPES IN FIRST 60 SECONDS")
    w_cmd_counts, w_cmd_first, w_total, w_frag_data, w_data = analyze_notifications_in_window(w_notifs, w_t0, 60.0, "WORKING")
    print(f"\n  Total notifications in first 60s: {w_total}")
    print(f"  Fragment continuation data: {w_frag_data}")
    print(f"  Non-command data: {w_data}")
    print(f"\n  {'Command':>12} {'Count':>6} {'First Seen':>12}   Label")
    print(f"  {'-'*12} {'-'*6} {'-'*12}   {'-'*25}")
    for key in sorted(w_cmd_counts.keys()):
        parts = key.split('_')
        cmd_tuple = (int(parts[0], 16), int(parts[1], 16))
        label = CMD_LABELS.get(cmd_tuple, f"CMD_{key}")
        print(f"  {key:>12} {w_cmd_counts[key]:6d} {w_cmd_first[key]:11.4f}s   {label}")

    print_header(f"FAILING SESSION: ALL NOTIFICATION COMMAND TYPES IN FIRST 60 SECONDS")
    f_cmd_counts, f_cmd_first, f_total, f_frag_data, f_data = analyze_notifications_in_window(f_notifs, f_t0, 60.0, "FAILING")
    print(f"\n  Total notifications in first 60s: {f_total}")
    print(f"  Fragment continuation data: {f_frag_data}")
    print(f"  Non-command data: {f_data}")
    print(f"\n  {'Command':>12} {'Count':>6} {'First Seen':>12}   Label")
    print(f"  {'-'*12} {'-'*6} {'-'*12}   {'-'*25}")
    for key in sorted(f_cmd_counts.keys()):
        parts = key.split('_')
        cmd_tuple = (int(parts[0], 16), int(parts[1], 16))
        label = CMD_LABELS.get(cmd_tuple, f"CMD_{key}")
        print(f"  {key:>12} {f_cmd_counts[key]:6d} {f_cmd_first[key]:11.4f}s   {label}")

    # ============================================================
    # DIFF SUMMARY
    # ============================================================
    print_header("DIFF SUMMARY: Notification Commands (first 60s)")

    w_keys = set(w_cmd_counts.keys())
    f_keys = set(f_cmd_counts.keys())

    only_working = w_keys - f_keys
    only_failing = f_keys - w_keys
    both = w_keys & f_keys

    if only_working:
        print("\n  >>> Commands ONLY in WORKING session (not in FAILING):")
        for key in sorted(only_working):
            parts = key.split('_')
            cmd_tuple = (int(parts[0], 16), int(parts[1], 16))
            label = CMD_LABELS.get(cmd_tuple, f"CMD_{key}")
            print(f"      {key} ({label}): {w_cmd_counts[key]} occurrences, first at +{w_cmd_first[key]:.4f}s")
    else:
        print("\n  No commands unique to WORKING session.")

    if only_failing:
        print("\n  >>> Commands ONLY in FAILING session (not in WORKING):")
        for key in sorted(only_failing):
            parts = key.split('_')
            cmd_tuple = (int(parts[0], 16), int(parts[1], 16))
            label = CMD_LABELS.get(cmd_tuple, f"CMD_{key}")
            print(f"      {key} ({label}): {f_cmd_counts[key]} occurrences, first at +{f_cmd_first[key]:.4f}s")
    else:
        print("\n  No commands unique to FAILING session.")

    if both:
        print("\n  Commands in BOTH sessions (count comparison):")
        print(f"    {'Command':>12} {'WORKING':>8} {'FAILING':>8} {'Diff':>8}   Label")
        print(f"    {'-'*12} {'-'*8} {'-'*8} {'-'*8}   {'-'*25}")
        for key in sorted(both):
            parts = key.split('_')
            cmd_tuple = (int(parts[0], 16), int(parts[1], 16))
            label = CMD_LABELS.get(cmd_tuple, f"CMD_{key}")
            wc = w_cmd_counts.get(key, 0)
            fc = f_cmd_counts.get(key, 0)
            diff = fc - wc
            diff_str = f"{'+'if diff>=0 else ''}{diff}"
            print(f"    {key:>12} {wc:8d} {fc:8d} {diff_str:>8}   {label}")

    # ============================================================
    # Write sequence comparison (command types only, skipping fragments)
    # ============================================================
    print_header("WRITE COMMAND SEQUENCE COMPARISON: First 20")

    def get_write_labels(writes, t0, count=20):
        seq = []
        for pkt in writes[:count]:
            label, is_cont, cmd, frag_info = extract_command(pkt['data'])
            offset = (pkt['ts_us'] - t0) / 1_000_000.0
            seq.append((offset, label, pkt['raw_size']))
        return seq

    w_write_seq = get_write_labels(w_writes, w_t0, 20)
    f_write_seq = get_write_labels(f_writes, f_t0, 20)

    max_len = max(len(w_write_seq), len(f_write_seq))
    print(f"\n  {'#':>3} {'W-Offset':>10} {'W-Sz':>4} {'WORKING Label':>24} | {'F-Offset':>10} {'F-Sz':>4} {'FAILING Label':>24} {'Match':>5}")
    print(f"  {'-'*3} {'-'*10} {'-'*4} {'-'*24} | {'-'*10} {'-'*4} {'-'*24} {'-'*5}")
    for i in range(max_len):
        if i < len(w_write_seq):
            w_part = f"{w_write_seq[i][0]:10.4f}s {w_write_seq[i][2]:4d} {w_write_seq[i][1]:>24}"
        else:
            w_part = f"{'':>10} {'':>4} {'':>24}"
        if i < len(f_write_seq):
            f_part = f"{f_write_seq[i][0]:10.4f}s {f_write_seq[i][2]:4d} {f_write_seq[i][1]:>24}"
        else:
            f_part = f"{'':>10} {'':>4} {'':>24}"

        w_label = w_write_seq[i][1] if i < len(w_write_seq) else ""
        f_label = f_write_seq[i][1] if i < len(f_write_seq) else ""
        match = "  OK" if w_label == f_label else "DIFF"
        print(f"  {i:3d} {w_part} | {f_part} {match}")

    print("\n  (DIFF = labels differ between WORKING and FAILING)")

    # ============================================================
    # Extended: Show all WRITE command types in first 60s
    # ============================================================
    print_header("WRITE COMMAND TYPES IN FIRST 60 SECONDS")

    w_wcmd, w_wcmd_first, w_wtotal, w_wfrag, w_wdata = analyze_notifications_in_window(w_writes, w_t0, 60.0, "WORKING")
    f_wcmd, f_wcmd_first, f_wtotal, f_wfrag, f_wdata = analyze_notifications_in_window(f_writes, f_t0, 60.0, "FAILING")

    print(f"\n  WORKING writes in first 60s: {w_wtotal} total, {w_wfrag} frag-data, {w_wdata} non-cmd")
    print(f"  FAILING writes in first 60s: {f_wtotal} total, {f_wfrag} frag-data, {f_wdata} non-cmd")

    all_wcmd_keys = sorted(set(w_wcmd.keys()) | set(f_wcmd.keys()))
    print(f"\n  {'Command':>12} {'W-Count':>8} {'W-First':>10} {'F-Count':>8} {'F-First':>10}   Label")
    print(f"  {'-'*12} {'-'*8} {'-'*10} {'-'*8} {'-'*10}   {'-'*25}")
    for key in all_wcmd_keys:
        parts = key.split('_')
        cmd_tuple = (int(parts[0], 16), int(parts[1], 16))
        label = CMD_LABELS.get(cmd_tuple, f"CMD_{key}")
        wc = w_wcmd.get(key, 0)
        fc = f_wcmd.get(key, 0)
        wf = f"{w_wcmd_first[key]:.4f}s" if key in w_wcmd_first else "N/A"
        ff = f"{f_wcmd_first[key]:.4f}s" if key in f_wcmd_first else "N/A"
        print(f"  {key:>12} {wc:8d} {wf:>10} {fc:8d} {ff:>10}   {label}")

    # ============================================================
    # Extended: Detailed first 50 notifications for the FAILING session
    # ============================================================
    print_header("FAILING SESSION: FIRST 50 NOTIFICATIONS (DETAILED)")

    print(f"\n  {'#':>3} {'Offset':>10} {'Handle':>6} {'Sz':>3} {'Label':>22}   Hex Data (full payload)")
    print(f"  {'-'*3} {'-'*10} {'-'*6} {'-'*3} {'-'*22}   {'-'*80}")
    for i, pkt in enumerate(f_notifs[:50]):
        offset = (pkt['ts_us'] - f_t0) / 1_000_000.0
        label, is_cont, cmd, frag_info = extract_command(pkt['data'])
        hex_data = format_hex(pkt['data'], max_bytes=70)
        print(f"  {i:3d} {offset:10.4f}s 0x{pkt['handle']:04X} {pkt['raw_size']:3d} {label:>22}   {hex_data}")

    # ============================================================
    # Extended: Detailed first 50 notifications for the WORKING session
    # ============================================================
    print_header("WORKING SESSION: FIRST 50 NOTIFICATIONS (DETAILED)")

    print(f"\n  {'#':>3} {'Offset':>10} {'Handle':>6} {'Sz':>3} {'Label':>22}   Hex Data (full payload)")
    print(f"  {'-'*3} {'-'*10} {'-'*6} {'-'*3} {'-'*22}   {'-'*80}")
    for i, pkt in enumerate(w_notifs[:50]):
        offset = (pkt['ts_us'] - w_t0) / 1_000_000.0
        label, is_cont, cmd, frag_info = extract_command(pkt['data'])
        hex_data = format_hex(pkt['data'], max_bytes=70)
        print(f"  {i:3d} {offset:10.4f}s 0x{pkt['handle']:04X} {pkt['raw_size']:3d} {label:>22}   {hex_data}")

    # ============================================================
    # Extended: FULL notification timeline for first 60s - BOTH sessions
    # ============================================================
    print_header("NOTIFICATION TIMELINE: ALL COMMANDS IN FIRST 15 SECONDS")

    def show_cmd_timeline(notifs, t0, window_sec, name):
        print(f"\n  --- {name} ---")
        print(f"  {'Offset':>10} {'Label':>24}   Hex (first 20 bytes)")
        print(f"  {'-'*10} {'-'*24}   {'-'*60}")
        count = 0
        for pkt in notifs:
            offset = (pkt['ts_us'] - t0) / 1_000_000.0
            if offset > window_sec:
                break
            label, is_cont, cmd, frag_info = extract_command(pkt['data'])
            if is_cont:
                continue  # Skip continuation fragments for readability
            hex_short = format_hex(pkt['data'], max_bytes=20)
            print(f"  {offset:10.4f}s {label:>24}   {hex_short}")
            count += 1
        print(f"  ({count} command-start packets shown)")

    show_cmd_timeline(w_notifs, w_t0, 15.0, "WORKING")
    show_cmd_timeline(f_notifs, f_t0, 15.0, "FAILING")

    # ============================================================
    # Write timeline first 15s
    # ============================================================
    print_header("WRITE TIMELINE: ALL COMMANDS IN FIRST 15 SECONDS")

    def show_write_timeline(writes, t0, window_sec, name):
        print(f"\n  --- {name} ---")
        print(f"  {'Offset':>10} {'Label':>24} {'Sz':>3}   Hex (first 20 bytes)")
        print(f"  {'-'*10} {'-'*24} {'-'*3}   {'-'*60}")
        count = 0
        for pkt in writes:
            offset = (pkt['ts_us'] - t0) / 1_000_000.0
            if offset > window_sec:
                break
            label, is_cont, cmd, frag_info = extract_command(pkt['data'])
            hex_short = format_hex(pkt['data'], max_bytes=20)
            print(f"  {offset:10.4f}s {label:>24} {pkt['raw_size']:3d}   {hex_short}")
            count += 1
        print(f"  ({count} packets shown)")

    show_write_timeline(w_writes, w_t0, 15.0, "WORKING")
    show_write_timeline(f_writes, f_t0, 15.0, "FAILING")

    print_header("ANALYSIS COMPLETE")


if __name__ == '__main__':
    main()
