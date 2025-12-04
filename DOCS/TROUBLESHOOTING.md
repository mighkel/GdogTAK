# Troubleshooting GdogTAK

Common issues and solutions when using GdogTAK.

## BLE Connection Issues

### "Scanning for Alpha..." never finds device

**Cause**: Garmin Explore (or another app) is already connected to the Alpha.

**Solution**:
1. Open Android Settings → Apps → Garmin Explore
2. Force Stop the app
3. Go to Permissions → Nearby devices → Don't allow
4. Return to GdogTAK and tap "Start Tracking"

**Why**: The Alpha stops advertising once a BLE client connects. Only one app can connect at a time.

### Found device but connection fails

**Possible causes**:
- Alpha is out of BLE range (typically 30-50 feet)
- Alpha is busy with another operation
- Android Bluetooth stack issue

**Solutions**:
1. Move closer to the Alpha
2. Toggle Bluetooth off/on on your phone
3. Restart the Alpha handheld
4. Restart your phone

### Connected but "Status: Connected" never changes to "Tracking"

**Cause**: Service discovery or characteristic subscription failed.

**Check logcat**:
```bash
adb logcat -s BleTrackingService:D | grep -i "service\|characteristic\|cccd"
```

**Look for**:
- "Discovered X services" (should be > 0)
- "Found Garmin service" 
- "CCCD write successful" (should appear 5 times)

## No Position Data

### "Tracking active (5 channels)" but Positions: 0

**Most likely cause**: Garmin Explore is not running.

**Current limitation**: The Alpha only streams position data when Garmin Explore has sent an initialization command.

**Workaround**:
1. Keep GdogTAK running with "Tracking active"
2. Open Garmin Explore app
3. Positions should start appearing in GdogTAK

### Positions appear but then stop

**Possible causes**:
- Lost BLE connection
- Collar is stationary (Alpha may throttle updates)
- Explore app was closed

**Check**:
- GdogTAK status should still show "Tracking active"
- Move the collar to trigger position updates
- Ensure Explore stays running in background

### Getting positions from wrong characteristic

The app subscribes to all 5 Garmin notify characteristics. Position data flows on `6a4e2811`. Check logcat for:

```
Dog position #X from char YYYY
```

If positions come from a different characteristic, please report it!

## ATAK Integration Issues

### Dog doesn't appear in ATAK

**Check GdogTAK is broadcasting**:
```bash
adb logcat -s AtakBroadcaster:D
```

Should show: "Sent CoT for K9-DOG1"

**Check ATAK is receiving multicast**:
1. ATAK Settings → Network Preferences
2. Ensure "Multicast" or "Mesh SA" is enabled
3. Check firewall isn't blocking UDP 6969

**Network issues**:
- GdogTAK broadcasts to `239.2.3.1:6969` (SA multicast)
- Phone and ATAK device must be on same network
- Some networks block multicast traffic

### Dog appears but immediately disappears

**Cause**: CoT stale time is too short (30 seconds default).

**Check**: Position updates should arrive every 2-5 seconds. If GdogTAK stops sending, the icon will go stale.

### Dog shows at wrong location

**Possible causes**:
- Coordinate parsing error (please report with logcat!)
- Old cached position
- GPS accuracy on collar is poor

**Debug**: Check the raw coordinates in logcat match expected location.

## Android Permissions

### Required Permissions

GdogTAK needs:
- **Bluetooth** (BLUETOOTH, BLUETOOTH_ADMIN)
- **Bluetooth Scan** (BLUETOOTH_SCAN) - Android 12+
- **Bluetooth Connect** (BLUETOOTH_CONNECT) - Android 12+
- **Location** (ACCESS_FINE_LOCATION) - Required for BLE scanning
- **Nearby Devices** - Android 12+

### Permission Denied Errors

If you see permission errors in logcat:
1. Uninstall and reinstall GdogTAK
2. Grant all permissions when prompted
3. Or manually enable in Settings → Apps → GdogTAK → Permissions

## Logcat Debugging

### Full debug output

```bash
adb logcat -s BleTrackingService:D GarminProtocol:D AtakBroadcaster:D CotGenerator:D MainActivity:D
```

### Filter for positions only

```bash
adb logcat | grep -i "dog position"
```

### Save to file for analysis

```bash
adb logcat -s BleTrackingService:D GarminProtocol:D > gdogtak_debug.log
```

## Known Limitations

### Cannot operate standalone (yet)

GdogTAK currently needs Garmin Explore running to trigger position data. This is the #1 issue to solve. If you can help capture btsnoop logs from a rooted device, please contribute!

### Single dog only

Currently hardcoded to track one dog as "K9-DOG1". Multi-dog support is planned.

### No settings UI

Dog name, callsign, and team are hardcoded. Settings screen is planned.

### May not work with all Alpha models

Only tested with Alpha 300i. Other models (200i, 200, 100) may have different BLE behavior.

## Reporting Issues

When reporting issues, please include:

1. **Device info**: Phone model, Android version, Alpha model
2. **Steps to reproduce**: What you did before the issue occurred
3. **Expected vs actual behavior**: What should happen vs what happened
4. **Logcat output**: Filtered debug logs (see above)
5. **Screenshots**: GdogTAK status screen, ATAK if relevant

File issues at: https://github.com/mighkel/GdogTAK/issues

## FAQ

**Q: Do I need to root my phone?**

A: No. GdogTAK works on unrooted phones. Root is only helpful for capturing btsnoop logs to discover the Explore init command.

**Q: Does this work offline / without internet?**

A: Yes! The Alpha→Phone→ATAK path is all local. No internet required once you're in the field.

**Q: Can I use this with TAK Server?**

A: Yes. CoT messages broadcast on SA multicast will be picked up by any TAK client, including TAK Server connections.

**Q: Will this drain my phone battery?**

A: BLE is low power. The main battery impact is keeping the screen on. Background operation should be efficient.

**Q: Why does Explore need to be running?**

A: The Alpha requires an initialization command to start streaming position data over BLE. Garmin Explore sends this command. We haven't yet figured out what the command is to send it ourselves.
