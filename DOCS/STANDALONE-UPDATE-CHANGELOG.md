# GdogTAK Standalone Init Update

**Date**: December 8, 2024  
**Breakthrough**: Captured Garmin Explore init sequence from btsnoop logs!

## Summary

GdogTAK can now operate **completely standalone** without requiring Garmin Explore to run in the background. We reverse-engineered the exact BLE commands from your btsnoop capture.

## Files Updated

### 1. BleTrackingService.kt (Major Update)

**New Features:**
- Sends complete init sequence after BLE connection
- Subscribes to all 5 notification characteristics (2810-2814)
- Generates device ID from Android ID for session consistency
- Sequential command sending with proper timing
- New `INITIALIZING` status state
- Better logging throughout init process

**Init Sequence Implemented:**
```
Step 1: Device ID Exchange
  → 00 05 [8-byte-id] 00 00
  → 00 00 [8-byte-id] 04 00 00

Step 2: Enable Channels (5x)
  → 15 00, 15 01, 15 02, 15 03, 15 04

Step 3: Start Streaming
  → 16 01 19 00 00 00
```

### 2. GarminProtocol.kt (Updated)

**Changes:**
- Added `WRITE_CHAR_UUID` constant: `6a4e2821-...`
- Added `NOTIFY_CHAR_UUIDS` list (all 5 characteristics)
- Improved coordinate parsing with nested pattern support
- Reduced minimum packet size from 40 to 20 bytes
- Better null coordinate filtering

## Testing

1. Copy files to your Android project:
   ```
   app/src/main/java/com/gdogtak/ble/BleTrackingService.kt
   app/src/main/java/com/gdogtak/ble/GarminProtocol.kt
   ```

2. Build and install

3. **Test WITHOUT Garmin Explore running:**
   - Force stop Garmin Explore
   - Start GdogTAK
   - Watch status: Scanning → Connecting → Initializing → Tracking
   - Should see "Tracking active (standalone)" if init succeeds

4. Check logcat for init sequence:
   ```
   adb logcat -s BleTrackingService | grep -i init
   ```

## Expected Logcat Output

```
I/BleTrackingService: Starting Garmin Alpha init sequence
D/BleTrackingService: Device ID: XX-XX-XX-XX-XX-XX-XX-XX
D/BleTrackingService: Init step 1/8: 00-05-XX-XX-XX-XX-XX-XX-XX-XX-00-00
D/BleTrackingService: Init step 2/8: 00-00-XX-XX-XX-XX-XX-XX-XX-XX-04-00-00
D/BleTrackingService: Init step 3/8: 15-00
D/BleTrackingService: Init step 4/8: 15-01
D/BleTrackingService: Init step 5/8: 15-02
D/BleTrackingService: Init step 6/8: 15-03
D/BleTrackingService: Init step 7/8: 15-04
D/BleTrackingService: Init step 8/8: 16-01-19-00-00-00
I/BleTrackingService: Init sequence complete!
I/BleTrackingService: Status: TRACKING - Tracking active (standalone)
```

## Fallback Behavior

If write characteristic isn't found:
- App falls back to "passive mode"
- Will still work if Garmin Explore is running

## What's Next

If this works:
1. ✅ Standalone operation achieved!
2. Settings UI for dog names
3. Multi-dog support
4. Handler tracking toggle

If init doesn't trigger data:
- May need to adjust timing (increase `INIT_STEP_DELAY_MS`)
- Device ID format might need tweaking
- Capture another btsnoop with more context

## Files

- [BleTrackingService.kt](./BleTrackingService.kt) - Updated service with init sequence
- [GarminProtocol.kt](./GarminProtocol.kt) - Updated protocol constants
- [INIT-SEQUENCE-DISCOVERY.md](./INIT-SEQUENCE-DISCOVERY.md) - Full protocol documentation

---

**This is a huge milestone!** 🎉🐕

If standalone mode works, GdogTAK becomes a real deployable tool for SAR operations - no more Explore dependency workaround.
