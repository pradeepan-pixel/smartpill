#include <Servo.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <RTClib.h>
#include <SoftwareSerial.h>

// Constants
#define SERVO_PIN 9
#define BUZZER_PIN 5
#define RX_BT 10
#define TX_BT 11

// Servo angles
#define REST_ANGLE 0
#define MORNING_ANGLE 60
#define AFTERNOON_ANGLE 120
#define NIGHT_ANGLE 180

// Buzzer alert timing
#define BUZZER_BEEPS 3
#define BUZZER_DURATION 200
#define BUZZER_PAUSE 200

// Display refresh time
#define DISPLAY_INTERVAL 3000
#define REMINDER_DURATION 10000

// Objects
Servo myServo;
LiquidCrystal_I2C lcd(0x27, 16, 2);
RTC_DS3231 rtc;
SoftwareSerial bluetoothSerial(RX_BT, TX_BT);

// Time slots (minutes since midnight)
int morningTime = -1;
int afternoonTime = -1;
int nightTime = -1;

// Flags
bool morningDone = false;
bool afternoonDone = false;
bool nightDone = false;
int lastCheckDay = -1;

// Time trackers
unsigned long lastDisplayMillis = 0;
unsigned long reminderStartMillis = 0;
bool reminderActive = false;
String activeReminder = "";

// Bluetooth
String btBuffer = "";
bool btComplete = false;

void setup() {
  Serial.begin(9600);
  Serial.println("==== Smart Dispenser Initializing ====");
  bluetoothSerial.begin(9600);
  Serial.println("Bluetooth serial initialized");

  myServo.attach(SERVO_PIN);
  myServo.write(REST_ANGLE);
  Serial.println("Servo initialized at rest position");

  lcd.begin();
  lcd.backlight();
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("Smart Dispenser");
  lcd.setCursor(0, 1);
  lcd.print("Initializing...");
  Serial.println("LCD initialized");
  delay(1500);

  pinMode(BUZZER_PIN, OUTPUT);
  digitalWrite(BUZZER_PIN, LOW);
  Serial.println("Buzzer initialized");

  if (!rtc.begin()) {
    showError("RTC Error");
    Serial.println("ERROR: RTC initialization failed");
    while (1);
  }
  Serial.println("RTC initialized successfully");

  lcd.clear();
  lcd.print("Bluetooth Ready");
  lcd.setCursor(0, 1);
  lcd.print("Set Times: M/A/N");
  Serial.println("==== Initialization Complete ====");
}

void loop() {
  DateTime now = rtc.now();

  checkNewDay(now);
  updateDisplay(now);
  handleBluetooth();
  processBluetooth();
  checkReminders(now);
  handleReminderEnd();
}

// ========== CORE LOGIC ==========

void checkNewDay(DateTime now) {
  if (now.day() != lastCheckDay) {
    morningDone = afternoonDone = nightDone = false;
    lastCheckDay = now.day();
  }
}

void updateDisplay(DateTime now) {
  if (millis() - lastDisplayMillis >= DISPLAY_INTERVAL && !reminderActive) {
    lcd.clear();
    lcd.setCursor(0, 0);
    lcd.print("Time: ");
    lcd.print(formatTime(now.hour(), now.minute()));

    lcd.setCursor(0, 1);
    if (!morningDone && morningTime >= 0)
      lcd.print("M: " + formatMinutesToTime(morningTime));
    else if (!afternoonDone && afternoonTime >= 0)
      lcd.print("A: " + formatMinutesToTime(afternoonTime));
    else if (!nightDone && nightTime >= 0)
      lcd.print("N: " + formatMinutesToTime(nightTime));
    else
      lcd.print("No reminders");

    lastDisplayMillis = millis();
  }
}

void checkReminders(DateTime now) {
  int currentMins = now.hour() * 60 + now.minute();

  if (!morningDone && morningTime >= 0 && currentMins >= morningTime) {
    triggerReminder("Morning", MORNING_ANGLE);
    morningDone = true;
  }
  else if (!afternoonDone && afternoonTime >= 0 && currentMins >= afternoonTime) {
    triggerReminder("Afternoon", AFTERNOON_ANGLE);
    afternoonDone = true;
  }
  else if (!nightDone && nightTime >= 0 && currentMins >= nightTime) {
    triggerReminder("Night", NIGHT_ANGLE);
    nightDone = true;
  }
}

void triggerReminder(String label, int angle) {
  Serial.println("Reminder: " + label);
  myServo.write(angle);
  playBuzzer();

  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("TAKE MEDICINE!");
  lcd.setCursor(0, 1);
  lcd.print(label);

  bluetoothSerial.println("Reminder: " + label);
  reminderActive = true;
  activeReminder = label;
  reminderStartMillis = millis();
}

void handleReminderEnd() {
  if (reminderActive && millis() - reminderStartMillis >= REMINDER_DURATION) {
    myServo.write(REST_ANGLE);
    reminderActive = false;
    activeReminder = "";
  }
}

// ========== BLUETOOTH ==========

void handleBluetooth() {
  while (bluetoothSerial.available()) {
    char c = bluetoothSerial.read();
    if (c == '\n' || c == '\r') {
      if (btBuffer.length() > 0) {
        Serial.print("Bluetooth command received: ");
        Serial.println(btBuffer);
        btComplete = true;
      }
    } else {
      btBuffer += c;
    }
  }
}

void processBluetooth() {
  if (btComplete) {
    btBuffer.trim();
    String response;
    Serial.print("Processing Bluetooth command: ");
    Serial.println(btBuffer);

    if (btBuffer.startsWith("M:")) {
      morningTime = parseTime(btBuffer.substring(2));
      morningDone = false;
      response = "Morning set: " + formatMinutesToTime(morningTime);
      Serial.println("Morning time set to: " + formatMinutesToTime(morningTime));
    }
    else if (btBuffer.startsWith("A:")) {
      afternoonTime = parseTime(btBuffer.substring(2));
      afternoonDone = false;
      response = "Afternoon set: " + formatMinutesToTime(afternoonTime);
      Serial.println("Afternoon time set to: " + formatMinutesToTime(afternoonTime));
    }
    else if (btBuffer.startsWith("N:")) {
      nightTime = parseTime(btBuffer.substring(2));
      nightDone = false;
      response = "Night set: " + formatMinutesToTime(nightTime);
      Serial.println("Night time set to: " + formatMinutesToTime(nightTime));
    }
    else {
      response = "Invalid Cmd";
      Serial.println("Invalid command received");
    }

    bluetoothSerial.println(response);
    Serial.println("Response sent to Bluetooth: " + response);
    
    lcd.clear();
    lcd.setCursor(0, 0);
    lcd.print(response.substring(0, 16));
    lcd.setCursor(0, 1);
    if (response.length() > 16)
      lcd.print(response.substring(16));
    
    btBuffer = "";
    btComplete = false;
  }
}

// ========== HELPERS ==========

int parseTime(String timeStr) {
  int idx = timeStr.indexOf(':');
  if (idx < 1) return -1;

  int h = timeStr.substring(0, idx).toInt();
  int m = timeStr.substring(idx + 1).toInt();
  if (h < 0 || h > 23 || m < 0 || m > 59) return -1;

  return h * 60 + m;
}

String formatMinutesToTime(int totalMinutes) {
  if (totalMinutes < 0) return "Not set";
  int h = totalMinutes / 60;
  int m = totalMinutes % 60;
  return formatTime(h, m);
}

String formatTime(int h, int m) {
  String s = "";
  if (h < 10) s += "0";
  s += String(h) + ":";
  if (m < 10) s += "0";
  s += String(m);
  return s;
}

void playBuzzer() {
  for (int i = 0; i < BUZZER_BEEPS; i++) {
    digitalWrite(BUZZER_PIN, HIGH);
    delay(BUZZER_DURATION);
    digitalWrite(BUZZER_PIN, LOW);
    delay(BUZZER_PAUSE);
  }
}

void showError(String msg) {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("ERROR:");
  lcd.setCursor(0, 1);
  lcd.print(msg);
  Serial.println("Error: " + msg);
}