# Garmin Alpha → TAK Integration

**Track your SAR K9 in ATAK/WinTAK using Garmin Alpha 300i + TT25 collar**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Status: Research](https://img.shields.io/badge/Status-Research%2FPrototype-orange)]()

## What This Does

This project enables real-time dog tracking in TAK (Team Awareness Kit) systems using Garmin's Alpha dog tracking hardware. Your SAR K9's position shows up on the same map as your team members — no cell service required.

```
┌─────────────┐     VHF      ┌─────────────┐     BLE      ┌─────────────┐     CoT      ┌─────────────┐
│  TT25 Dog   │─────────────▶│  Alpha 300i │─────────────▶│  Android    │─────────────▶│    ATAK     │
│   Collar    │   (9 miles)  │  Handheld   │  (Bluetooth) │  Bridge App │  (local/net) │  TAK Server │
└─────────────┘              └─────────────┘              └─────────────┘              └─────────────┘
```

## Project Status

🔬 **Research/Prototype Phase**

We've successfully reverse-engineered the Garmin Alpha BLE protocol and can decode dog collar positions. A working Python prototype exists. Android app development is next.

### What Works
- ✅ BLE protocol decoded (Garmin Multi-Link)
- ✅ Coordinate encoding understood (semicircles in protobuf)
- ✅ Dog vs handheld positions distinguished
- ✅ Python parser/bridge prototype
- ✅ CoT XML generation for TAK

### In Progress
- 🔧 Android app for production deployment
- 🔧 Multi-dog support
- 🔧 Testing with various Alpha models (300i, 200i, 100)

### Planned
- 📋 ATAK plugin for integrated UI
- 📋 Dog status indicators (treed, on point, moving)
- 📋 Track history/breadcrumbs

## Hardware Requirements

- **Garmin Alpha handheld**: Alpha 300i (tested), likely works with 200i, 200, 100
- **Garmin dog collar**: TT25 (tested), likely works with T20, TT15, T5
- **Android phone**: Any phone with Bluetooth LE (no root required)
- **TAK setup**: ATAK on Android, or WinTAK + TAK Server

## Quick Start (Prototype)

```bash
# Clone the repo
git clone https://github.com/YOUR_USERNAME/garmin-alpha-tak.git
cd garmin-alpha-tak

# Install dependencies
pip install bleak

# Run demo mode (tests the parser)
python garmin_alpha_tak_bridge.py --demo

# Run with actual hardware (replace with your Alpha's MAC address)
python garmin_alpha_tak_bridge.py --device FA:1A:C1:B3:DC:2F
```

## Documentation

- **[PROTOCOL.md](PROTOCOL.md)** — Detailed protocol documentation
  - "I just want to understand it" summary
  - "I want to hack on this" technical deep-dive
  - Raw data samples and analysis

- **[alpha-bt-capture-quickref.md](docs/alpha-bt-capture-quickref.md)** — Quick reference for BLE capture

## Use Cases

### SAR K9 Operations
Track your search dog's position in real-time during wilderness searches. Works in areas with no cell coverage — the Alpha's VHF link gives you 9 miles of range to the collar.

### Hunting Dog Tracking
Monitor multiple dogs during hunts with the same TAK system your hunting party uses for coordination.

### Working Dog Training
Record and analyze training runs with full GPS tracks integrated into your existing TAK workflow.

## Why Not Just Use the Alpha's Screen?

The Alpha handheld shows dog positions just fine. But TAK integration gives you:

- **Shared awareness**: Everyone on the TAK network sees the dog, not just the handler
- **Track recording**: TAK Server logs the full track for after-action review
- **Integration**: Dog position alongside team members, waypoints, boundaries
- **Redundancy**: If the handler goes down, others can still locate the dog

## Contributing

This is an open research project. Contributions welcome:

- 🐛 Bug reports and protocol corrections
- 📱 Android app development
- 🔬 Testing with different Alpha/collar models
- 📖 Documentation improvements

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Legal / Disclaimer

This project involves reverse engineering Garmin's proprietary BLE protocol. It is intended for interoperability purposes (enabling Garmin hardware to work with TAK systems) and falls under fair use/interoperability exceptions in most jurisdictions.

**This project is not affiliated with or endorsed by Garmin Ltd. or any TAK Program office.**

Use at your own risk. This is prototype software for research purposes. Do not rely on it for life-safety applications without thorough testing.

## License

MIT License — See [LICENSE](LICENSE) for details.

## Acknowledgments

- [Gadgetbridge Project](https://gadgetbridge.org/) — Garmin BLE protocol documentation
- [TAK Product Center](https://tak.gov/) — TAK ecosystem
- Nordic Semiconductor — nRF Connect app for BLE analysis
- The SAR K9 teams who need this capability

---

*Built for the handlers who go into the backcountry with their four-legged partners.*
