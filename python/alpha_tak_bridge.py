# Contributing to Garmin Alpha → TAK Integration

Thanks for your interest in contributing! This project aims to bring dog tracking capabilities to the TAK ecosystem, and community contributions are essential.

## Ways to Contribute

### 🐛 Bug Reports & Protocol Corrections

If you find errors in the protocol documentation or the code doesn't work with your hardware:

1. Open an issue with:
   - Your hardware (Alpha model, collar model, firmware versions)
   - What you expected vs. what happened
   - Any BLE captures or logs you can share

### 🔬 Protocol Research

Help us understand more of the Garmin protocol:

- **Multi-dog scenarios**: How are multiple collars distinguished?
- **Status fields**: Where are "treed", "on point", etc. encoded?
- **Other models**: Does Alpha 200i/200/100 use the same protocol?

To contribute research:
1. Capture BLE data using nRF Connect
2. Document your findings
3. Submit a PR to PROTOCOL.md or open a discussion issue

### 📱 Android Development

The main development need is a production-quality Android app. Key requirements:

- BLE connection management (robust reconnection)
- Background service for continuous tracking
- Local broadcast to ATAK (UDP multicast or Intent)
- Clean UI for configuration

If you have Android development experience, see the `android/` directory (coming soon) or open an issue to discuss architecture.

### 📖 Documentation

- Improve explanations in PROTOCOL.md
- Add setup guides for different scenarios
- Translate documentation

## Development Setup

### For Protocol Research

```bash
# Clone the repo
git clone https://github.com/YOUR_USERNAME/garmin-alpha-tak.git
cd garmin-alpha-tak

# Install Python dependencies
pip install bleak

# Run the demo/parser
python garmin_alpha_tak_bridge.py --demo
```

### For BLE Capture

1. Install nRF Connect on your Android phone
2. Connect to your Alpha 300i
3. Subscribe to characteristic `6a4e2813-...`
4. Export the capture log
5. Share in an issue or PR

## Code Style

- Python: Follow PEP 8, use type hints
- Document functions with docstrings
- Keep the "simple version" and "technical version" pattern in documentation

## Pull Request Process

1. Fork the repo and create a branch (`git checkout -b feature/my-feature`)
2. Make your changes with clear commit messages
3. Test with actual hardware if possible
4. Update documentation if needed
5. Submit a PR with a clear description of changes

## Questions?

- Open a GitHub Discussion for general questions
- Open an Issue for bugs or specific technical questions

## Code of Conduct

Be respectful and constructive. We're all here to help SAR teams and their dogs.

