# Smart Pill Dispenser

## Overview
The Smart Pill Dispenser automates medication reminders and dispensing using an Arduino-based system with a servo-driven carousel, RTC timekeeping, LCD display, buzzer alerts, and Bluetooth scheduling via a mobile app. It is aimed at improving medication adherence for elderly and chronically ill patients by providing scheduled reminders and simple dispensing cues.

This README merges the functional specifics from `smartpill.ino` with the broader system description, diagrams references, testing notes, standards, feasibility, and future roadmap from the Smart Pill Dispenser report (PDF). 

## Key Features
- Scheduled reminders for Morning, Afternoon, and Night (set over Bluetooth)
- Audible and visual alerts: buzzer beeps and LCD prompts
- Servo-driven tray positions per time slot
- Accurate scheduling via DS3231 RTC
- Bluetooth configuration using simple text commands
- Clear UI on 16x2 I2C LCD showing current time, next due dose, and command responses

## System Architecture
- Microcontroller: Arduino (PDF references Arduino Nano)
- Peripherals:
  - RTC: DS3231 (accurate real-time clock)
  - LCD: 16x2 I2C, address 0x27
  - Servo: 180° servo for rotating pill tray
  - Buzzer: Active buzzer for alerts
  - Bluetooth: HC-05 (SoftwareSerial on D10/D11)
- Power: 12V adapter + buck converter to 5V (per PDF); ensure stable 5V rail for servo
- Enclosure: 3D-printed modular pill tray (see PDF images, Appendix page 39)

## Hardware Pin Map (from code)
- SERVO_PIN: D9
- BUZZER_PIN: D5
- RX_BT: D10 (Arduino receives from HC-05 TX)
- TX_BT: D11 (Arduino transmits to HC-05 RX)
- LCD: I2C (0x27)

## Behavior (smartpill.ino)
- Libraries: `Servo`, `Wire`, `LiquidCrystal_I2C`, `RTClib`, `SoftwareSerial`
- Time slots (mins since midnight): `morningTime`, `afternoonTime`, `nightTime` (`-1` means not set)
- Daily reset: At day change, clears completion flags so reminders can fire again
- Display: Every 3s, shows current time and next pending reminder while no active alert
- Reminders: At or after scheduled minutes, triggers -> servo moves to angle, buzzer beeps, LCD shows “TAKE MEDICINE!” and label, Bluetooth sends reminder text. Active for 10s, then servo returns to rest.
- Bluetooth protocol:
  - M:HH:MM (set morning)
  - A:HH:MM (set afternoon)
  - N:HH:MM (set night)
  - Responds with: “Morning set: HH:MM” (similar for A/N). Invalid command -> “Invalid Cmd”.
- Safety/Errors: If RTC fails init, LCD shows “RTC Error��� and system halts.

## Servo Angles (from code)
- REST_ANGLE = 0°
- MORNING_ANGLE = 60°
- AFTERNOON_ANGLE = 120°
- NIGHT_ANGLE = 180°
Adjust to match your tray’s mechanical alignment.

## Buzzer Pattern (from code)
- 3 beeps, 200 ms on, 200 ms off per beep

## LCD Behavior
- Idle: cycles time and next pending slot every 3 seconds
- Reminder active: “TAKE MEDICINE!” and the slot label for 10 seconds

## Build and Upload
1) Install Arduino IDE
2) Install libraries (Library Manager):
   - RTClib by Adafruit
   - LiquidCrystal_I2C (matching your I2C LCD backpack)
   - Servo (built-in)
3) Wire components as per pin map and ensure I2C address (0x27 commonly; scan if different)
4) Open `smartpill.ino`, select the correct board/port (e.g., Arduino Nano if used)
5) Upload the sketch

## Bluetooth Command Examples
- Set morning to 08:00: `M:08:00`
- Set afternoon to 13:30: `A:13:30`
- Set night to 20:15: `N:20:15`
- Expected response over Bluetooth and LCD: `Morning set: 08:00` (similar for A/N)

## User Flow (PDF Flow Diagram, page 25)
- Power on -> System initializes (RTC, LCD, Servo, Buzzer)
- Pair phone to HC-05 and send schedule commands (M:/A:/N:)
- Times stored in minutes since midnight; daily reset of taken flags at day change
- At or after scheduled time:
  - Trigger: LCD shows TAKE MEDICINE!, buzzer beeps, servo rotates to time-specific angle
  - Bluetooth notification sent
  - After 10 seconds, servo returns to rest
- LCD cycles current time and next pending schedule between reminders

## Visual References (from PDF)
- Block Diagram (page 23): Power Supply -> Arduino Nano controlling RTC, LCD, Servo, HC-05, Buzzer; application domain: Smart Pill Dispenser
- Architecture Diagram (page 24): Mobile app via Bluetooth to Arduino, coordinating RTC, LCD, servo, buzzer
- Flow Diagram (page 25): Steps for setting times and triggering reminders
- Circuit Diagram (page 26): Example wiring among components
- Design Diagram (page 27): 3D model of circular multi-compartment dispenser
- Product Photos (Appendix page 39): Prototype with breadboard, LCD, and servo assembly

## Hardware Setup Summary (aligned with PDF Chapter 4)
- Components:
  - Arduino Nano (or compatible)
  - 180° Servo
  - DS3231 RTC
  - 16x2 I2C LCD (0x27)
  - HC-05 Bluetooth module
  - Buzzer
  - 12V 2A adapter + buck converter to 5V
  - Jumper wires, 3D-printed enclosure
- Wiring highlights:
  - Servo: Signal -> D9, power from 5V rail capable of servo current
  - Buzzer: D5 -> buzzer, shared GND
  - HC-05: TX -> D10 (Arduino RX via SoftwareSerial), RX -> D11 (Arduino TX) with proper level shifting if needed
  - RTC/LCD: SDA/SCL to Arduino I2C pins, 5V and GND

## Operating Notes and Constraints
- Time validity: HH must be 00–23; MM must be 00–59. Invalid strings result in -1 and “Not set”.
- Trigger behavior: Reminders trigger when current time >= scheduled minutes; each triggers once per day.
- Ensure RTC time/date is correct; otherwise schedules may not match expectations.

## Testing Summary (PDF Chapter 9)
- Unit tests: RTC accuracy, servo positioning, buzzer cadence, Bluetooth parsing/response
- System tests: End-to-end scheduling from mobile to dispenser; edge cases such as overlapping time slots and missed doses
- UAT: LCD text clarity, buzzer sound levels, simplified mobile interface

## Feasibility Overview (PDF Chapter 7)
- Technical: Uses reliable, low-cost components
- Economic: Low BOM cost; 3D printing reduces manufacturing overhead
- Operational: Simple UI; modular, easy maintenance; low-power 5V operation
- Market: Addresses elderly/chronic care needs; affordable alternative to expensive dispensers
- Legal: Consider medical device regulations (e.g., IEC 60601), data privacy if extended with cloud (GDPR/HIPAA)

## Standards and Compliance (PDF Chapter 5)
- Electrical safety: IS 13252-like requirements for power supply
- Medical device safety: IEC 60601 considerations for home medical electronics
- Materials: Food-grade ABS enclosure for hygiene
- Data privacy: If extended with cloud/app, consider GDPR/HIPAA for PHI

## Future Enhancements (PDF Chapter 10)
- Cloud integration for logs and caregiver dashboards (SMS/push)
- Voice assistant compatibility (Alexa/Google)
- Camera-based intake verification
- Advanced reminder system with custom audio/voice
- Multi-user capability and per-slot security
- Battery backup for outages
- EHR integration for prescription-driven schedules

## Bill of Materials (PDF Table 7.2)
- Arduino Nano
- 180° Servo
- DS3231 RTC Module
- 12V 2A Adapter
- Buck Converter (to 5V)
- 16x2 I2C LCD
- HC-05 Bluetooth Module
- Jumper Cables
- 3D-printed enclosure

## Troubleshooting
- LCD shows no text: Verify I2C address (0x27 common, but not guaranteed); use an I2C scanner.
- Bluetooth not responding: Confirm baud 9600, RX/TX cross wiring, newline endings from app.
- Reminders not triggering: Check RTC time/date, ensure schedules are not -1 ("Not set").
- Servo misalignment: Adjust MORNING_ANGLE/AFTERNOON_ANGLE/NIGHT_ANGLE to match tray positions.

## Repository Structure
- `smartpill.ino`: Main Arduino firmware (RTC scheduling, Bluetooth command parsing, servo control, buzzer alerts, LCD UI)

## References (PDF page 38)
- Arduino Nano documentation, RTClib and Servo libraries, HC-05 module references, and related research on automated pill dispensers and IoT healthcare.

## License and Acknowledgments
- For educational and prototyping use. Acknowledge library authors and cited works from the PDF references.
