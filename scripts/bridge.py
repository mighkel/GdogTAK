#!/usr/bin/env python3
"""
Garmin Alpha 300i → TAK Server Bridge (Prototype)
=================================================
Captures dog collar positions via BLE and pushes them to TAK Server as CoT.

REQUIREMENTS:
    pip install bleak aiohttp

PROTOCOL FINDINGS (from BLE capture analysis):
    - Service UUID: 6a4e2800-667b-11e3-949a-0800200c9a66 (Garmin Multi-Link)
    - Characteristic: 6a4e2813-667b-11e3-949a-0800200c9a66 (notifications)
    - Device markers: 0x28 = handheld, 0x35 = dog collar
    - Coordinates: Garmin semicircles in protobuf structure
    - Pattern: 0A 0C 08 [lat varint] 10 [lon varint] 18 [timestamp]

Author: Reverse engineered from Garmin Alpha 300i BLE traffic
Date: December 2024
"""

import asyncio
import struct
import socket
import ssl
import time
from datetime import datetime, timezone
from typing import Optional, Tuple, Dict
from dataclasses import dataclass

# BLE library (uncomment when running on device with BLE)
# from bleak import BleakClient, BleakScanner

# ============================================================================
# CONFIGURATION
# ============================================================================

GARMIN_SERVICE_UUID = "6a4e2800-667b-11e3-949a-0800200c9a66"
GARMIN_NOTIFY_CHAR = "6a4e2813-667b-11e3-949a-0800200c9a66"

# TAK Server connection
TAK_SERVER_HOST = "your-tak-server.example.com"
TAK_SERVER_PORT = 8089  # SSL CoT port
TAK_CERT_FILE = "/path/to/client.p12"  # Your TAK client certificate

# Dog identification
DOG_CALLSIGN = "K9-ROVER"
DOG_UID = "GARMIN-TT25-001"
DOG_TEAM = "CCVFD-SAR"
DOG_ROLE = "SAR Canine"

# Device markers from protocol analysis
DEVICE_HANDHELD = 0x28
DEVICE_COLLAR = 0x35

# ============================================================================
# COORDINATE PARSING
# ============================================================================

@dataclass
class DogPosition:
    """Parsed dog collar position"""
    lat: float
    lon: float
    timestamp: int
    altitude: Optional[float] = None
    speed: Optional[float] = None
    heading: Optional[float] = None


def decode_varint(data: bytes, offset: int = 0) -> Tuple[int, int]:
    """Decode a protobuf varint, return (value, bytes_consumed)"""
    result = 0
    shift = 0
    consumed = 0
    while offset + consumed < len(data):
        byte = data[offset + consumed]
        result |= (byte & 0x7F) << shift
        consumed += 1
        if not (byte & 0x80):
            break
        shift += 7
    return result, consumed


def decode_sint32(varint: int) -> int:
    """ZigZag decode for signed integers"""
    return (varint >> 1) ^ -(varint & 1)


def semicircles_to_degrees(sc: int) -> float:
    """Convert Garmin semicircles to decimal degrees"""
    return sc * (180.0 / 2147483648.0)


def parse_ble_notification(data: bytes) -> Optional[DogPosition]:
    """
    Parse a Garmin Multi-Link BLE notification for dog collar position.
    
    Returns None if this isn't a collar position message.
    """
    if len(data) < 40:
        return None
    
    # Check for dog collar marker (0x35)
    is_collar = False
    for i in range(min(len(data) - 3, 20)):
        if data[i] == 0x02 and data[i+1] == DEVICE_COLLAR and data[i+2] == 0x01:
            is_collar = True
            break
    
    if not is_collar:
        return None
    
    # Find coordinate block: 0A 0C 08 [lat] 10 [lon] 18 [timestamp]
    for j in range(len(data) - 15):
        if data[j] == 0x0A and data[j+1] == 0x0C and data[j+2] == 0x08:
            # Decode latitude
            lat_val, lat_len = decode_varint(data, j + 3)
            lat_signed = decode_sint32(lat_val)
            
            # Decode longitude
            lon_offset = j + 3 + lat_len
            if lon_offset >= len(data) or data[lon_offset] != 0x10:
                continue
                
            lon_val, lon_len = decode_varint(data, lon_offset + 1)
            lon_signed = decode_sint32(lon_val)
            
            # Convert to degrees
            lat_deg = semicircles_to_degrees(lat_signed)
            lon_deg = semicircles_to_degrees(lon_signed)
            
            # Sanity check
            if not (-90 <= lat_deg <= 90 and -180 <= lon_deg <= 180):
                continue
            
            # Decode timestamp
            ts_offset = lon_offset + lon_len + 1
            timestamp = 0
            if ts_offset < len(data) and data[ts_offset] == 0x18:
                timestamp, _ = decode_varint(data, ts_offset + 1)
            
            return DogPosition(
                lat=lat_deg,
                lon=lon_deg,
                timestamp=timestamp
            )
    
    return None


# ============================================================================
# CoT MESSAGE GENERATION
# ============================================================================

def generate_cot_xml(position: DogPosition, callsign: str = DOG_CALLSIGN, 
                     uid: str = DOG_UID) -> str:
    """
    Generate Cursor-on-Target XML for a dog position.
    
    CoT type: a-f-G-U-C (friendly ground unit - combat/SAR)
    """
    now = datetime.now(timezone.utc)
    time_str = now.strftime("%Y-%m-%dT%H:%M:%S.000Z")
    stale_str = (now.replace(second=now.second + 30)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    
    cot = f'''<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" 
       uid="{uid}" 
       type="a-f-G-U-C-I" 
       time="{time_str}" 
       start="{time_str}" 
       stale="{stale_str}" 
       how="m-g">
    <point lat="{position.lat:.7f}" 
           lon="{position.lon:.7f}" 
           hae="0" 
           ce="10.0" 
           le="10.0"/>
    <detail>
        <contact callsign="{callsign}"/>
        <remarks>SAR K9 - Garmin TT25 Collar</remarks>
        <__group name="{DOG_TEAM}" role="{DOG_ROLE}"/>
        <track course="0" speed="0"/>
        <precisionlocation altsrc="GPS" geopointsrc="GPS"/>
    </detail>
</event>'''
    
    return cot


# ============================================================================
# TAK SERVER CONNECTION
# ============================================================================

class TAKConnection:
    """Simple TAK Server SSL connection for pushing CoT"""
    
    def __init__(self, host: str, port: int, cert_file: str):
        self.host = host
        self.port = port
        self.cert_file = cert_file
        self.sock = None
        self.ssl_sock = None
    
    def connect(self):
        """Establish SSL connection to TAK Server"""
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.load_cert_chain(self.cert_file)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.ssl_sock = context.wrap_socket(self.sock)
        self.ssl_sock.connect((self.host, self.port))
        print(f"✅ Connected to TAK Server {self.host}:{self.port}")
    
    def send_cot(self, cot_xml: str):
        """Send CoT message to TAK Server"""
        if self.ssl_sock:
            self.ssl_sock.send(cot_xml.encode('utf-8'))
    
    def close(self):
        """Close connection"""
        if self.ssl_sock:
            self.ssl_sock.close()


# ============================================================================
# BLE BRIDGE (Main Logic)
# ============================================================================

class GarminAlphaBridge:
    """
    Bridge between Garmin Alpha 300i BLE and TAK Server.
    
    This class:
    1. Scans for and connects to Garmin Alpha 300i
    2. Subscribes to position notifications
    3. Parses collar position updates
    4. Pushes positions to TAK Server as CoT
    """
    
    def __init__(self, tak_host: str, tak_port: int, tak_cert: str):
        self.tak = TAKConnection(tak_host, tak_port, tak_cert)
        self.last_position: Optional[DogPosition] = None
        self.position_count = 0
    
    def handle_notification(self, sender, data: bytes):
        """
        BLE notification callback.
        Called each time the Alpha sends position data.
        """
        position = parse_ble_notification(data)
        
        if position:
            self.position_count += 1
            self.last_position = position
            
            # Generate and send CoT
            cot = generate_cot_xml(position)
            print(f"🐕 Dog position #{self.position_count}: "
                  f"{position.lat:.6f}, {position.lon:.6f}")
            
            try:
                self.tak.send_cot(cot)
                print(f"   → Sent to TAK Server")
            except Exception as e:
                print(f"   ⚠️ TAK send failed: {e}")
    
    async def run(self, device_address: str):
        """
        Main bridge loop.
        
        Args:
            device_address: BLE MAC address of Alpha 300i (e.g., "FA:1A:C1:B3:DC:2F")
        """
        # Note: Uncomment BLE imports and this code when running on real device
        
        print(f"🔍 Connecting to Garmin Alpha at {device_address}...")
        
        # --- PLACEHOLDER FOR ACTUAL BLE CONNECTION ---
        # async with BleakClient(device_address) as client:
        #     print(f"✅ Connected to {client.address}")
        #     
        #     # Subscribe to notifications
        #     await client.start_notify(GARMIN_NOTIFY_CHAR, self.handle_notification)
        #     print(f"📡 Listening for dog collar positions...")
        #     
        #     # Keep running
        #     while True:
        #         await asyncio.sleep(1)
        
        print("⚠️ BLE not available - running in demo mode")
        print("   To use: pip install bleak, then uncomment BLE code")


# ============================================================================
# DEMO / TEST MODE
# ============================================================================

def demo_mode():
    """
    Demonstrate the protocol parser with sample data from the capture.
    """
    print("="*60)
    print("GARMIN ALPHA → TAK BRIDGE - DEMO MODE")
    print("="*60)
    
    # Sample BLE notification data (from actual capture)
    sample_messages = [
        # Dog collar message
        bytes.fromhex("E73B000249052B951F1101010102350101023501012"
                     "36A333A310A2F0A0C0880C0D7F10310FFEBF7A70A18"
                     "C387C99C04304D4DFE99BA4255010101058"
                     "27D0C080210061801380440064805"),
        # Another collar message  
        bytes.fromhex("EA20000249052B9B821101010102350101023501012B"
                     "6A333A310A2F0A0C0880BCD7F10310FFEFF7A70A18D2"
                     "87C99C04304D4F6FE9425589828A3F827D0C08041001"
                     "1809380440064805C60800"),
    ]
    
    print("\n📦 Parsing sample BLE notifications:\n")
    
    for i, data in enumerate(sample_messages):
        print(f"Message {i+1} ({len(data)} bytes):")
        print(f"  Raw: {data[:40].hex()}...")
        
        position = parse_ble_notification(data)
        if position:
            print(f"  ✅ Dog collar position:")
            print(f"     Lat: {position.lat:.6f}°")
            print(f"     Lon: {position.lon:.6f}°")
            print(f"     Timestamp: {position.timestamp}")
            
            cot = generate_cot_xml(position)
            print(f"\n  📄 Generated CoT XML:")
            # Print just the key parts
            for line in cot.split('\n')[2:6]:
                print(f"     {line.strip()}")
        else:
            print(f"  ⚠️ Not a collar position message")
        print()
    
    print("="*60)
    print("INTEGRATION STEPS")
    print("="*60)
    print("""
1. HARDWARE SETUP:
   - Android phone with BLE (runs this bridge)
   - Garmin Alpha 300i handheld
   - TT25 collar on dog
   - Phone connected to Alpha via Bluetooth

2. SOFTWARE SETUP:
   pip install bleak aiohttp
   
3. CONFIGURE:
   - Set TAK_SERVER_HOST to your TAK Server
   - Set TAK_CERT_FILE to your client certificate
   - Set DOG_CALLSIGN for ATAK display name

4. RUN:
   python garmin_alpha_tak_bridge.py --device FA:1A:C1:B3:DC:2F

5. VERIFY:
   - Dog appears on ATAK map as "K9-ROVER"
   - Position updates every 2.5 seconds (collar rate)
""")


if __name__ == "__main__":
    import sys
    
    if len(sys.argv) > 1 and sys.argv[1] == "--demo":
        demo_mode()
    else:
        print("Usage:")
        print("  python garmin_alpha_tak_bridge.py --demo    # Test parser")
        print("  python garmin_alpha_tak_bridge.py --device MAC_ADDRESS")
        print("\nRunning demo mode...")
        demo_mode()
