# GdogTAK

**Track your SAR K9 in ATAK/WinTAK using Garmin Alpha GPS dog tracking hardware**

This project enables real-time dog tracking in TAK (Team Awareness Kit) systems using Garmin's Alpha dog tracking hardware. Your SAR K9's position shows up on the same map as your team members — no cell service required.

```
┌─────────────┐  VHF   ┌─────────────┐  BLE   ┌─────────────┐  CoT   ┌─────────────┐
│  TT25 Dog   │───────▶│ Alpha 300i  │───────▶│   GdogTAK   │───────▶│    ATAK     │
│   Collar    │ 9 mi   │  Handheld   │        │ Android App │  UDP   │  TAK Server │
└─────────────┘        └─────────────┘        └─────────────┘        └─────────────┘
```

## 🎉 Status: Working Prototype

**December 2024**: Android app successfully displays dog collar positions in ATAK!

- ✅ BLE protocol reverse-engineered (Garmin Multi-Link service)
- ✅ Android app connects to Alpha 300i via Bluetooth LE
- ✅ Dog positions decoded and broadcast as CoT to TAK network
- ✅ K9 icon appears on ATAK map with real-time position updates
- ⚠️ Currently requires Garmin Explore app running (triggers data stream)
- 🔧 Settings UI for dog names/callsigns (in progress)
- 🔧 Multi-dog support (in progress)
- 📋 Standalone operation (init command discovery needed)
- 📋 ATAK plugin for integrated UI

## Hardware Requirements

| Component | Tested | Likely Compatible |
|-----------|--------|-------------------|
| **Handheld** | Alpha 300i | Alpha 200i, Alpha 200, Alpha 100 |
| **Collar** | TT25 | TT20, TT15, T5 |
| **Phone** | Samsung S24 Ultra | Any Android with BLE support |
| **TAK** | ATAK 5.2+, WinTAK | Any TAK client on same network |

## Quick Start

### 1. Build the Android App

```bash
git clone https://github.com/mighkel/GdogTAK.git
cd GdogTAK/android

# Open in Android Studio and build, or:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Prepare Your Devices

1. **Power on** your Alpha handheld and TT25 collar
2. **Verify** the collar shows on the Alpha's map
3. **Open Garmin Explore** app on your phone (required for now)
4. **Start ATAK** and ensure SA multicast is enabled

### 3. Run GdogTAK

1. Launch GdogTAK app
2. Grant Bluetooth and location permissions
3. Tap "Start Tracking"
4. Wait for "Tracking active (5 channels)" status
5. Your dog appears in ATAK as "K9-DOG1"!

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                        GdogTAK Android App                      │
├────────────────────────────────────────────────────────────────┤
│  BleTrackingService          │  GarminProtocol                 │
│  - Scans for "Alpha" devices │  - Parses Garmin BLE packets    │
│  - Manages BLE connection    │  - Decodes protobuf coordinates │
│  - Subscribes to 5 notify    │  - Extracts dog vs handheld     │
│    characteristics           │    positions                    │
├────────────────────────────────────────────────────────────────┤
│  CotGenerator                │  AtakBroadcaster                │
│  - Creates CoT XML events    │  - UDP multicast to 239.2.3.1   │
│  - SAR K9 team/role metadata │  - Port 6969 (SA channel)       │
└────────────────────────────────────────────────────────────────┘
```

## Documentation

- **[PROTOCOL.md](PROTOCOL.md)** — Garmin BLE protocol deep-dive
- **[docs/BLE-CHARACTERISTICS.md](docs/BLE-CHARACTERISTICS.md)** — Characteristic UUIDs and data flow
- **[docs/COORDINATE-ENCODING.md](docs/COORDINATE-ENCODING.md)** — Semicircle to decimal conversion
- **[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)** — Common issues and solutions

## Current Limitations

### Requires Garmin Explore Running

The Alpha 300i only streams position data over BLE when Garmin Explore is connected and has triggered an initialization command. We're working to identify this command for standalone operation.

**Workaround**: Keep Garmin Explore running in background while using GdogTAK.

### Single Dog Callsign

Currently hardcoded to "K9-DOG1". Settings UI for custom callsigns is planned.

### BLE Connection Exclusivity

Only one app can connect to the Alpha at a time. If Explore is connected, GdogTAK can't connect (and vice versa). The current workaround piggybacks on Explore's connection.

## Use Cases

### Search and Rescue
Track your SAR K9's position during wilderness searches. Works in areas with no cell coverage — the Alpha's VHF link gives you 9+ miles of range to the collar.

### Hunting
Monitor multiple dogs during hunts with the same TAK system your hunting party uses for coordination.

### Training
Record and analyze training runs with full GPS tracks integrated into your existing TAK workflow.

### Why TAK Integration?

The Alpha handheld shows dog positions just fine. But TAK integration gives you:

- **Shared awareness**: Everyone on the TAK network sees the dog
- **Track recording**: TAK Server logs the full track for AAR
- **Integration**: Dog position alongside team members, waypoints, boundaries
- **Redundancy**: If the handler goes down, others can locate the dog

## Contributing

Contributions welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

Priority areas:
- 🔬 **Capture the init command** — Need btsnoop logs from rooted device
- 📱 **Settings UI** — Dog names, callsigns, team assignments
- 🐕 **Multi-dog support** — Track multiple collars with unique identifiers
- 🔌 **ATAK plugin** — Integrated UI within ATAK itself
- 📖 **Testing** — Other Alpha/collar models (200i, 100, T5, etc.)

## Legal

This project reverse-engineers Garmin's proprietary BLE protocol for interoperability with TAK systems. Not affiliated with or endorsed by Garmin Ltd.

**Use at your own risk.** This is prototype software. Do not rely on it for life-safety applications without thorough testing.

## License

MIT License — See [LICENSE](LICENSE) for details.

## Acknowledgments

- [Gadgetbridge Project](https://gadgetbridge.org/) — Garmin protocol insights
- [TAK Product Center](https://tak.gov/) — TAK ecosystem
- Nordic Semiconductor — nRF Connect for BLE analysis
- The SAR K9 teams who need this capability

---

*Built for the handlers who go into the backcountry with their four-legged partners.* 🐕
