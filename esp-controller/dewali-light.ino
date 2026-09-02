/*
  ESP32 relay patterns + 0.96" SSD1306 OLED (I2C)
  - Added speed controller commands (STEP, SPD+, SPD-, RUN)
  - Robust manual pattern parsing
  - Ensures manual pattern selection updates currentPatternIdx for consistent status
  - OLED initialized once in setup()
  - Libraries required:
      Adafruit SSD1306
      Adafruit GFX
*/

#include <WiFi.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

const char* ssid     = "";
const char* password = "";

WiFiServer server(8080);

// Relay pins
const int NUM_RELAYS = 4;
const int relayPins[NUM_RELAYS] = {2, 4, 16, 17};
const bool RELAY_ACTIVE_LOW = false;

// Timing (ms) - adjustable via commands
unsigned long PATTERN_STEP_MS = 300UL;     // step interval inside a pattern
unsigned long PATTERN_RUN_MS  = 10000UL;   // how long to run each pattern in AUTO (10s)

// Bounds for step/run values
const unsigned long STEP_MS_MIN = 50UL;
const unsigned long STEP_MS_MAX = 2000UL;
const unsigned long RUN_MS_MIN  = 1000UL;
const unsigned long RUN_MS_MAX  = 600000UL; // 10 minutes

// ---------------- Patterns P1..P10 ----------------
const uint8_t P1_STEPS[] = { 0b1110, 0b1101, 0b1011, 0b0111 }; // len 4
const uint8_t P2_STEPS[] = { 0b0001, 0b0010, 0b0100, 0b1000 };
const uint8_t P3_STEPS[] = { 0b0011, 0b0110, 0b1100, 0b1001 };
const uint8_t P4_STEPS[] = { 0b1100, 0b0110, 0b0011, 0b1001 };
const uint8_t P5_STEPS[] = { 0b0001, 0b0010, 0b0100, 0b1000, 0b0100, 0b0010 };
const uint8_t P6_STEPS[] = { 0b0001, 0b0011, 0b0111, 0b1111 };
const uint8_t P7_STEPS[] = { 0b1111, 0b1110, 0b1100, 0b1000, 0b0000 };
const uint8_t P8_STEPS[] = { 0b1010, 0b0101 };
const uint8_t P9_STEPS[] = { 0b0110, 0b1111, 0b1001, 0b0000 };
const uint8_t P10_STEPS[] = { 0b1010, 0b0101, 0b1110, 0b0111, 0b1101, 0b1011, 0b1111, 0b0000 };

const int P1_LEN = sizeof(P1_STEPS)/sizeof(P1_STEPS[0]);
const int P2_LEN = sizeof(P2_STEPS)/sizeof(P2_STEPS[0]);
const int P3_LEN = sizeof(P3_STEPS)/sizeof(P3_STEPS[0]);
const int P4_LEN = sizeof(P4_STEPS)/sizeof(P4_STEPS[0]);
const int P5_LEN = sizeof(P5_STEPS)/sizeof(P5_STEPS[0]);
const int P6_LEN = sizeof(P6_STEPS)/sizeof(P6_STEPS[0]);
const int P7_LEN = sizeof(P7_STEPS)/sizeof(P7_STEPS[0]);
const int P8_LEN = sizeof(P8_STEPS)/sizeof(P8_STEPS[0]);
const int P9_LEN = sizeof(P9_STEPS)/sizeof(P9_STEPS[0]);
const int P10_LEN = sizeof(P10_STEPS)/sizeof(P10_STEPS[0]);

// Register patterns
const uint8_t* PATTERNS[] = {
  P1_STEPS, P2_STEPS, P3_STEPS, P4_STEPS, P5_STEPS,
  P6_STEPS, P7_STEPS, P8_STEPS, P9_STEPS, P10_STEPS
};
const int PATTERN_LENGTHS[] = {
  P1_LEN, P2_LEN, P3_LEN, P4_LEN, P5_LEN,
  P6_LEN, P7_LEN, P8_LEN, P9_LEN, P10_LEN
};
const int PATTERN_COUNT = sizeof(PATTERNS)/sizeof(PATTERNS[0]);

// ---------------- runtime state ----------------
bool relayState[NUM_RELAYS] = { false, false, false, false };

// AUTO state
int currentPatternIdx = 0;    // 0..PATTERN_COUNT-1 for AUTO cycling
int patternStepIndex = 0;
unsigned long lastStepMillis = 0;
unsigned long currentPatternStartMillis = 0;

// MANUAL_LOOP state (M Pn repeating)
int manualLoopPatternIdx = 0;
int manualLoopStepIndex = 0;

// MANUAL_RUN_ONCE state (if you still use run-once)
int manualRunPatternIdx = 0;
int manualRunStep = 0;

// modes
enum Mode { MODE_AUTO, MODE_MANUAL_STATIC, MODE_MANUAL_RUN_ONCE, MODE_MANUAL_LOOP };
Mode currentMode = MODE_AUTO;

// wifi tracking
bool wasWifiConnected = false;

// single non-blocking TCP client
WiFiClient activeClient;
String recvBuffer = "";

// ---------------- OLED (0.96" SSD1306) ----------------
// Using Adafruit SSD1306 (128x64) over I2C
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
// Change address if your display uses 0x3D
#define OLED_ADDR 0x3C
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

// display update timing
unsigned long lastDisplayMillis = 0;
const unsigned long DISPLAY_UPDATE_MS = 500;
bool displayOk = false; // flag to avoid re-init and to skip drawing if init failed

// ---------------- helpers ----------------
void writeRelayPin(int idx, bool on) {
  if (idx < 0 || idx >= NUM_RELAYS) return;
  digitalWrite(relayPins[idx], RELAY_ACTIVE_LOW ? (on ? LOW : HIGH) : (on ? HIGH : LOW));
  relayState[idx] = on;
}

void applyMask(uint8_t mask) {
  for (int i = 0; i < NUM_RELAYS; ++i) {
    bool on = (mask >> i) & 0x01;
    writeRelayPin(i, on);
  }
}

void setAllRelays(bool on) {
  for (int i = 0; i < NUM_RELAYS; ++i) writeRelayPin(i, on);
}

void blinkAllRelays(int times, unsigned long onMs, unsigned long offMs) {
  for (int t = 0; t < times; ++t) {
    setAllRelays(true);
    delay(onMs);
    setAllRelays(false);
    if (t < times - 1) delay(offMs);
  }
}

void applyPatternStep(int patIdx, int stepIdx) {
  if (patIdx < 0 || patIdx >= PATTERN_COUNT) return;
  int len = PATTERN_LENGTHS[patIdx];
  if (len <= 0) return;
  stepIdx %= len;
  uint8_t mask = PATTERNS[patIdx][stepIdx];
  applyMask(mask);
}

// ---------------- mode entry functions ----------------
void enterAuto() {
  currentMode = MODE_AUTO;
  // keep currentPatternIdx if already set (so AUTO continues where left off)
  patternStepIndex = 0;
  unsigned long now = millis();
  lastStepMillis = now;
  currentPatternStartMillis = now;
  applyPatternStep(currentPatternIdx, patternStepIndex);
  Serial.print("ENTER AUTO: P"); Serial.print(currentPatternIdx+1);
  Serial.print(" step=0 mask="); Serial.println(String(PATTERNS[currentPatternIdx][0], BIN));
}

void enterManualStatic() {
  currentMode = MODE_MANUAL_STATIC;
  Serial.println("ENTER MANUAL_STATIC");
}

void enterManualRunOncePattern(int patIdx) {
  if (patIdx < 0 || patIdx >= PATTERN_COUNT) return;
  currentMode = MODE_MANUAL_RUN_ONCE;
  manualRunPatternIdx = patIdx;
  manualRunStep = 0;
  // keep consistent "global" pattern pointers for status
  currentPatternIdx = patIdx;
  patternStepIndex = 0;
  lastStepMillis = millis();
  applyPatternStep(manualRunPatternIdx, manualRunStep);
  Serial.print("ENTER MANUAL_RUN_ONCE: P"); Serial.print(patIdx+1); Serial.println(" step=0");
}

// NEW: manual loop entry — M Pn to repeat pattern until another command
void enterManualLoopPattern(int patIdx) {
  if (patIdx < 0 || patIdx >= PATTERN_COUNT) return;
  currentMode = MODE_MANUAL_LOOP;
  manualLoopPatternIdx = patIdx;
  manualLoopStepIndex = 0;
  // keep consistent "global" pattern pointers for status and display
  currentPatternIdx = patIdx;
  patternStepIndex = 0;
  lastStepMillis = millis();
  applyPatternStep(manualLoopPatternIdx, manualLoopStepIndex);
  Serial.print("ENTER MANUAL_LOOP: P"); Serial.print(patIdx+1); Serial.println(" step=0 (repeating)");
}

// ---------------- parsing helpers ----------------
// Extract pattern number from a string containing a 'P' followed by digits.
// Returns -1 if none found or invalid.
int parsePatternNumber(const String &s) {
  // find 'P' or 'p'
  for (int i = 0; i < (int)s.length(); ++i) {
    char c = s.charAt(i);
    if (c == 'P' || c == 'p') {
      // collect digits following P
      String num = "";
      for (int j = i + 1; j < (int)s.length(); ++j) {
        char d = s.charAt(j);
        if (d >= '0' && d <= '9') num += d;
        else break;
      }
      if (num.length() == 0) return -1;
      int n = num.toInt();
      if (n <= 0) return -1;
      return n; // 1-based
    }
  }
  return -1;
}

// parse first integer (positive) found anywhere in string, returns -1 if none
long parseFirstNumber(const String &s) {
  String num = "";
  bool inNum = false;
  for (int i = 0; i < s.length(); ++i) {
    char c = s.charAt(i);
    if (c >= '0' && c <= '9') {
      num += c;
      inNum = true;
    } else {
      if (inNum) break; // stop at first non-digit after a number started
    }
  }
  if (num.length() == 0) return -1;
  return num.toInt();
}

// Build short status (reports pattern/step according to active mode)
String buildStatusLine() {
  String s = "STATUS:";
  s += (WiFi.status() == WL_CONNECTED) ? "CONNECTED" : "DISCONNECTED";
  s += ";IP:";
  if (WiFi.status() == WL_CONNECTED) s += WiFi.localIP().toString();
  else s += "0.0.0.0";
  for (int i = 0; i < NUM_RELAYS; ++i) {
    s += ";R"; s += (i+1); s += ":"; s += (relayState[i] ? "ON" : "OFF");
  }
  s += ";MODE:";
  if (currentMode == MODE_AUTO) s += "AUTO";
  else if (currentMode == MODE_MANUAL_STATIC) s += "MANUAL_STATIC";
  else if (currentMode == MODE_MANUAL_RUN_ONCE) s += "MANUAL_RUN_ONCE";
  else s += "MANUAL_LOOP";

  // show pattern/step depending on mode
  if (currentMode == MODE_MANUAL_LOOP) {
    s += ";PATTERN:P"; s += String(manualLoopPatternIdx+1);
    s += ";STEP:"; s += String(manualLoopStepIndex);
  } else if (currentMode == MODE_MANUAL_RUN_ONCE) {
    s += ";PATTERN:P"; s += String(manualRunPatternIdx+1);
    s += ";STEP:"; s += String(manualRunStep);
  } else {
    s += ";PATTERN:P"; s += String(currentPatternIdx+1);
    s += ";STEP:"; s += String(patternStepIndex);
  }

  // Append speed values
  s += ";STEP_MS:"; s += String(PATTERN_STEP_MS);
  s += ";RUN_MS:"; s += String(PATTERN_RUN_MS);

  return s;
}

// ---------------- OLED display rendering ----------------
void updateDisplay() {
  if (!displayOk) return; // don't attempt to draw if init failed

  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  // Line 0: WiFi / IP (short)
  display.setCursor(0, 0);
  if (WiFi.status() == WL_CONNECTED) {
    // show SSID truncated if long
    String ss = String(WiFi.SSID());
    if (ss.length() > 12) ss = ss.substring(0, 12) + "...";
    display.print("WiFi: ");
    display.print(ss);
  } else {
    display.print("WiFi: DISCONNECTED");
  }

  // Line 1: IP (smaller)
  display.setCursor(0, 9);
  display.print("IP: ");
  if (WiFi.status() == WL_CONNECTED) display.print(WiFi.localIP().toString());
  else display.print("0.0.0.0");

  // Line 2: Mode and pattern
  display.setCursor(0, 20);
  display.print("Mode:");
  if (currentMode == MODE_AUTO) display.print("AUTO");
  else if (currentMode == MODE_MANUAL_STATIC) display.print("MANUAL_STAT");
  else if (currentMode == MODE_MANUAL_RUN_ONCE) display.print("RUN_ONCE");
  else display.print("M_LOOP");

  int dispPattern = currentPatternIdx;
  int dispStep = patternStepIndex;
  if (currentMode == MODE_MANUAL_LOOP) {
    dispPattern = manualLoopPatternIdx;
    dispStep = manualLoopStepIndex;
  } else if (currentMode == MODE_MANUAL_RUN_ONCE) {
    dispPattern = manualRunPatternIdx;
    dispStep = manualRunStep;
  }
  display.print(" P"); display.print(dispPattern+1);
  display.print(" S"); display.print(dispStep);

  // Line 3..: Relays as boxes/labels
  int y = 34;
  for (int i = 0; i < NUM_RELAYS; ++i) {
    int x = i * 30;
    if (relayState[i]) {
      display.fillRect(x, y, 10, 10, SSD1306_WHITE);
      display.setTextColor(SSD1306_WHITE);
      display.setCursor(x + 12, y);
      display.print("R"); display.print(i+1);
      display.setTextColor(SSD1306_WHITE);
    } else {
      display.drawRect(x, y, 10, 10, SSD1306_WHITE);
      display.setCursor(x + 12, y);
      display.print("R"); display.print(i+1);
    }
  }

  // Footer: show pattern mask + timings
  display.setCursor(0, 54);
  uint8_t mask = 0;
  if (currentMode == MODE_MANUAL_LOOP) {
    if (manualLoopPatternIdx >= 0 && manualLoopPatternIdx < PATTERN_COUNT) {
      int len = PATTERN_LENGTHS[manualLoopPatternIdx];
      int step = (manualLoopStepIndex % max(1, len));
      mask = PATTERNS[manualLoopPatternIdx][step];
    }
    display.print("Mloop:");
  } else if (currentMode == MODE_MANUAL_RUN_ONCE) {
    if (manualRunPatternIdx >= 0 && manualRunPatternIdx < PATTERN_COUNT) {
      int len = PATTERN_LENGTHS[manualRunPatternIdx];
      int mr = manualRunStep;
      if (mr >= len) mr = len - 1;
      if (mr < 0) mr = 0;
      mask = PATTERNS[manualRunPatternIdx][mr];
    }
    display.print("Run:");
  } else {
    int len = PATTERN_LENGTHS[currentPatternIdx];
    int step = patternStepIndex % max(1, len);
    mask = PATTERNS[currentPatternIdx][step];
    display.print("Auto:");
  }
  for (int b = NUM_RELAYS-1; b >= 0; --b) {
    display.print( ( (mask >> b) & 1 ) ? '1' : '0' );
  }

  // show timing info near right
  display.setCursor(80, 54);
  display.print("STEP:");
  display.print(PATTERN_STEP_MS);
  display.print("ms");
  display.setCursor(80, 62);
  display.print("RUN:");
  display.print(PATTERN_RUN_MS / 1000);
  display.print("s");

  display.display();
}

// ---------------- processCommand ----------------
void processCommand(String cmd) {
  cmd.trim();
  cmd.toUpperCase();
  Serial.print("CMD recv: '"); Serial.print(cmd); Serial.println("'");

  // quick shortcuts & speed control
  if (cmd == "ON") {
    setAllRelays(true);
    enterManualStatic();
    if (activeClient && activeClient.connected()) activeClient.println("OK ON");
  } else if (cmd == "OFF") {
    setAllRelays(false);
    enterManualStatic();
    if (activeClient && activeClient.connected()) activeClient.println("OK OFF");
  } else if (cmd == "AUTO") {
    enterAuto();
    if (activeClient && activeClient.connected()) activeClient.println("OK AUTO");
  } else if (cmd == "SPD+") {
    // speed up: shorten interval (multiply by 0.8)
    unsigned long newStep = (unsigned long)max((long)(PATTERN_STEP_MS * 0.8), (long)STEP_MS_MIN);
    PATTERN_STEP_MS = newStep;
    Serial.print("Speed increased. STEP_MS="); Serial.println(PATTERN_STEP_MS);
    if (activeClient && activeClient.connected()) activeClient.println("OK SPD+ " + String(PATTERN_STEP_MS));
  } else if (cmd == "SPD-") {
    // slow down: increase interval (multiply by 1.25)
    unsigned long newStep = (unsigned long)min((unsigned long)(PATTERN_STEP_MS * 1.25), STEP_MS_MAX);
    PATTERN_STEP_MS = newStep;
    Serial.print("Speed decreased. STEP_MS="); Serial.println(PATTERN_STEP_MS);
    if (activeClient && activeClient.connected()) activeClient.println("OK SPD- " + String(PATTERN_STEP_MS));
  } else if (cmd.startsWith("S ") || cmd.startsWith("STEP ") || cmd.startsWith("SPEED ")) {
    long v = parseFirstNumber(cmd);
    if (v >= 0) {
      if (v < (long)STEP_MS_MIN) v = STEP_MS_MIN;
      if (v > (long)STEP_MS_MAX) v = STEP_MS_MAX;
      PATTERN_STEP_MS = (unsigned long)v;
      Serial.print("STEP_MS set to "); Serial.println(PATTERN_STEP_MS);
      if (activeClient && activeClient.connected()) activeClient.println("OK STEP_MS " + String(PATTERN_STEP_MS));
    } else {
      if (activeClient && activeClient.connected()) activeClient.println("ERR Invalid speed");
    }
  } else if (cmd.startsWith("RUN ") || cmd.startsWith("RUNDUR ") || cmd.startsWith("RUNMS ")) {
    long v = parseFirstNumber(cmd);
    if (v >= 0) {
      if (v < (long)RUN_MS_MIN) v = RUN_MS_MIN;
      if (v > (long)RUN_MS_MAX) v = RUN_MS_MAX;
      PATTERN_RUN_MS = (unsigned long)v;
      Serial.print("PATTERN_RUN_MS set to "); Serial.println(PATTERN_RUN_MS);
      if (activeClient && activeClient.connected()) activeClient.println("OK RUN_MS " + String(PATTERN_RUN_MS));
    } else {
      if (activeClient && activeClient.connected()) activeClient.println("ERR Invalid run duration");
    }
  } else if (cmd.startsWith("M ") || cmd.startsWith("MP") || cmd.startsWith("M")) {
    // Support multiple forms: "M Pn", "MPn", "M P  n", "M P10", "M P-ignored"
    int n = parsePatternNumber(cmd); // returns 1-based or -1
    if (n >= 1 && n <= PATTERN_COUNT) {
      enterManualLoopPattern(n - 1);
      if (activeClient && activeClient.connected()) activeClient.println("OK M P" + String(n));
    } else {
      if (activeClient && activeClient.connected()) activeClient.println("ERR Invalid pattern");
      Serial.println("Invalid manual pattern command");
    }
  } else if (cmd.startsWith("P")) {
    // Allow direct "P<n>" as shorthand too
    int n = parsePatternNumber(cmd);
    if (n >= 1 && n <= PATTERN_COUNT) {
      enterManualLoopPattern(n - 1);
      if (activeClient && activeClient.connected()) activeClient.println("OK P" + String(n));
    } else {
      if (activeClient && activeClient.connected()) activeClient.println("ERR Invalid pattern");
    }
  } else {
    if (activeClient && activeClient.connected()) activeClient.println("ERR Unknown command");
    Serial.println("Unknown command");
  }

  if (activeClient && activeClient.connected()) activeClient.println(buildStatusLine());
}

// ---------------- setup & loop ----------------
void setup() {
  Serial.begin(115200);
  delay(100);

  // init pins (start OFF)
  for (int i = 0; i < NUM_RELAYS; ++i) {
    pinMode(relayPins[i], OUTPUT);
    writeRelayPin(i, false);
  }

  // init OLED (once)
  const int SDA_PIN = 21;
  const int SCL_PIN = 22;
  Wire.begin(SDA_PIN, SCL_PIN); // explicit pins for ESP32

  displayOk = false;
  if (!display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR)) {
    Serial.println("SSD1306 allocation failed");
    displayOk = false;
  } else {
    displayOk = true;
    display.clearDisplay();

    // --- simple "firecracker" animation ---
    for (int burst = 0; burst < 3; burst++) {
      display.clearDisplay();
      display.setTextSize(1);
      display.setTextColor(SSD1306_WHITE);
      display.setCursor(20, 25);
      display.println("💥");
      display.display();
      delay(150);

      display.clearDisplay();
      // burst lines
      for (int i = 0; i < 8; i++) {
        float angle = i * (PI / 4);
        int x = 64 + 10 * cos(angle);
        int y = 32 + 10 * sin(angle);
        display.drawLine(64, 32, x, y, SSD1306_WHITE);
      }
      display.display();
      delay(150);

      display.clearDisplay();
      // second burst (larger)
      for (int i = 0; i < 12; i++) {
        float angle = i * (PI / 6);
        int x = 64 + 20 * cos(angle);
        int y = 32 + 20 * sin(angle);
        display.drawLine(64, 32, x, y, SSD1306_WHITE);
      }
      display.display();
      delay(150);
    }

    // --- show Happy Diwali message ---
    display.clearDisplay();
    display.setTextSize(2);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(8, 20);
    display.println("HAPPY");
    display.setCursor(18, 40);
    display.println("DIWALI!");
    display.display();
    delay(2000);
  }

  Serial.print("Connecting to ");
  Serial.println(ssid);
  WiFi.begin(ssid, password);

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 10000) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();

  server.begin();
  server.setNoDelay(true);

  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("Connected IP: ");
    Serial.println(WiFi.localIP());
    blinkAllRelays(2, 200, 200);
    enterAuto();
    wasWifiConnected = true;
  } else {
    Serial.println("WiFi not connected yet.");
    wasWifiConnected = false;
  }

  // initial display update
  lastDisplayMillis = millis() - DISPLAY_UPDATE_MS; // force immediate update in loop
}

void loop() {
  // wifi reconnect detection
  bool nowConnected = (WiFi.status() == WL_CONNECTED);
  if (nowConnected && !wasWifiConnected) {
    Serial.print("WiFi reconnected IP: ");
    Serial.println(WiFi.localIP());
    blinkAllRelays(2, 200, 200);
    enterAuto();
  }
  wasWifiConnected = nowConnected;

  unsigned long now = millis();

  // Mode advancing
  if (currentMode == MODE_AUTO) {
    // advance step inside current pattern
    if (now - lastStepMillis >= PATTERN_STEP_MS) {
      lastStepMillis = now;
      patternStepIndex = (patternStepIndex + 1) % PATTERN_LENGTHS[currentPatternIdx];
      applyPatternStep(currentPatternIdx, patternStepIndex);
      Serial.print("AUTO P"); Serial.print(currentPatternIdx+1);
      Serial.print(" step="); Serial.print(patternStepIndex);
      Serial.print(" mask="); Serial.println(String(PATTERNS[currentPatternIdx][patternStepIndex], BIN));
    }
    // switch pattern after PATTERN_RUN_MS
    if (now - currentPatternStartMillis >= PATTERN_RUN_MS) {
      currentPatternIdx = (currentPatternIdx + 1) % PATTERN_COUNT;
      patternStepIndex = 0;
      currentPatternStartMillis = now;
      lastStepMillis = now;
      applyPatternStep(currentPatternIdx, patternStepIndex);
      Serial.print("SWITCH AUTO -> P"); Serial.print(currentPatternIdx+1);
      Serial.print(" step=0 mask="); Serial.println(String(PATTERNS[currentPatternIdx][0], BIN));
    }
  }
  else if (currentMode == MODE_MANUAL_LOOP) {
    // advance step inside the manual looped pattern
    if (now - lastStepMillis >= PATTERN_STEP_MS) {
      lastStepMillis = now;
      manualLoopStepIndex = (manualLoopStepIndex + 1) % PATTERN_LENGTHS[manualLoopPatternIdx];
      // Keep global pointers in sync for status/display
      currentPatternIdx = manualLoopPatternIdx;
      patternStepIndex = manualLoopStepIndex;
      applyPatternStep(manualLoopPatternIdx, manualLoopStepIndex);
      Serial.print("MANUAL_LOOP P"); Serial.print(manualLoopPatternIdx+1);
      Serial.print(" step="); Serial.print(manualLoopStepIndex);
      Serial.print(" mask="); Serial.println(String(PATTERNS[manualLoopPatternIdx][manualLoopStepIndex], BIN));
    }
  }
  else if (currentMode == MODE_MANUAL_RUN_ONCE) {
    // run once; when finished fall back to MANUAL_STATIC
    if (now - lastStepMillis >= PATTERN_STEP_MS) {
      lastStepMillis = now;
      manualRunStep++;
      if (manualRunStep >= PATTERN_LENGTHS[manualRunPatternIdx]) {
        enterManualStatic();
        Serial.println("Manual run finished; switched to MANUAL_STATIC");
        if (activeClient && activeClient.connected()) activeClient.println(buildStatusLine());
      } else {
        // keep global pointers in sync for status/display
        currentPatternIdx = manualRunPatternIdx;
        patternStepIndex = manualRunStep;
        applyPatternStep(manualRunPatternIdx, manualRunStep);
        Serial.print("MANUAL_RUN P"); Serial.print(manualRunPatternIdx+1);
        Serial.print(" step="); Serial.print(manualRunStep);
        Serial.print(" mask="); Serial.println(String(PATTERNS[manualRunPatternIdx][manualRunStep], BIN));
      }
    }
  }
  // MODE_MANUAL_STATIC -> nothing to do

  // Accept new client if none active
  if (!activeClient || !activeClient.connected()) {
    if (activeClient) {
      activeClient.stop();
      recvBuffer = "";
    }
    WiFiClient c = server.accept();
    if (c) {
      activeClient = c;
      recvBuffer = "";
      Serial.print("Client connected from ");
      Serial.println(activeClient.remoteIP());
      if (activeClient && activeClient.connected()) activeClient.println("CONNECTED");
      // send status immediately
      if (activeClient && activeClient.connected()) activeClient.println(buildStatusLine());
    }
  }

  // Read data from active client non-blocking
  if (activeClient && activeClient.connected() && activeClient.available()) {
    while (activeClient.available()) {
      char ch = (char)activeClient.read();
      if (ch == '\r' || ch == '\n') {
        if (recvBuffer.length() > 0) {
          processCommand(recvBuffer);
          recvBuffer = "";
        }
      } else {
        recvBuffer += ch;
        if (recvBuffer.length() > 200) recvBuffer = recvBuffer.substring(recvBuffer.length() - 200);
      }
    }
  }

  // minimal WiFi reconnect attempts
  static unsigned long lastTry = 0;
  if (!nowConnected && now - lastTry > 5000) {
    lastTry = now;
    Serial.println("Attempting WiFi reconnect...");
    WiFi.reconnect();
  }

  // Update display periodically (non-blocking)
  if (millis() - lastDisplayMillis >= DISPLAY_UPDATE_MS) {
    lastDisplayMillis = millis();
    updateDisplay();
  }

  delay(1);
}
