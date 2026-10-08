# Student Monaco Editor Client: Rendering & Dual-Cursor Visualization Engine Specification

---

## 1. Minimalist Student Architecture & Zero-Dependency Offline Loading

### 1.1 Offline-First Zero External Dependency Strategy
In school computer labs and closed campus intranet environments, workstations are often isolated from the public internet or placed behind restrictive proxies. Relying on external CDNs (such as `cdnjs`, `jsdelivr`, or `unpkg`) causes script loading timeouts, rendering Monaco Editor unusable.

This system adopts an **"Embedded Static Assets + Local Loopback Self-Hosting (127.0.0.1)"** architecture:
1. **Bundled Static Assets in IntelliJ Plugin**:
   - The Monaco Editor core package (`min/vs`) is bundled directly inside the plugin resources under `src/main/resources/webapp/`. Unnecessary languages are trimmed to keep the total asset footprint compact.
   - Asset directory structure:
     ```text
     src/main/resources/webapp/
     ├── index.html
     ├── style.css
     ├── app.js
     └── vs/
         ├── loader.js
         ├── editor/
         │   ├── editor.main.js
         │   ├── editor.main.css
         │   └── editor.main.nls.js
         ├── base/worker/workerMain.js
         └── basic-languages/ (syntax highlighters)
     ```
2. **Lightweight Embedded HTTP Service in Plugin**:
   - The embedded Jetty server binds to `127.0.0.1:{dynamic_port}` (or local LAN IP) upon session startup.
   - When students open the browser via LAN, they access the teacher's hosted port directly (e.g., `http://<TEACHER_IP>:<PORT>/?token=...`).
   - All static scripts and styles load via relative HTTP paths: `<script src="./vs/loader.js"></script>`.
3. **Monaco Web Worker Sandbox Isolation**:
   - Browsers enforce strict same-origin policies on Web Workers and `file://` URLs.
   - In `app.js`, `window.MonacoEnvironment.getWorkerUrl` is customized with inline Blobs and relative paths, guaranteeing instant offline initialization.

---

## 2. Dual-Cursor & Selection Visualization Mechanism

### 2.1 High-Fidelity Teacher Cursor & Selection Rendering (Blue Theme)
During live lectures, students need to clearly observe where the teacher is looking and editing:
- **Monaco `deltaDecorations` API**:
  Instead of frequent and costly DOM manipulations, the client uses Monaco's internal rendering pipeline via `editor.deltaDecorations(oldDecorations, newDecorations)`. This achieves 60 FPS performance with zero layout jitter during scrolling and font resizing.
- **Breathing Cursor Line (`teacher-cursor`)**:
  A 2.5px solid blue vertical cursor line enhanced with CSS breathing animation (`0 0 8px rgba(33, 150, 243, 0.85)`).
- **Floating Badge Capsule (`teacher-cursor-badge`)**:
  A floating badge reading `👨‍🏫 Teacher` is displayed using CSS pseudo-elements (`::before`) above the teacher's caret line. Pure CSS placement prevents Monaco DOM element destruction and eliminates cursor jumping during co-located student keystrokes.
- **Translucent Selection Overlay (`teacher-selection`)**:
  When the teacher highlights a code block, a translucent blue background (`rgba(33, 150, 243, 0.25)`) is rendered across the selected range.

### 2.2 Viewport Following & Folded Code Handling
- **Smooth Viewport Following**:
  When enabled, if the teacher moves outside the student's visible screen area, the editor triggers `editor.revealPositionInCenterIfOutsideViewport(position, monaco.editor.ScrollType.Smooth)` to smoothly bring the teacher's cursor into view without disorienting jumps.
- **Unfolding Collapsed Code**:
  If the teacher's cursor lands within a collapsed code block, the block is expanded automatically to prevent the cursor from disappearing.

### 2.3 Student Cursor & Custom Theme (Orange Theme)
- Monaco is styled with a custom dark theme:
  - Student Cursor: Bright vibrant orange (`#FF9800`)
  - Student Selection: Translucent orange (`rgba(255, 152, 0, 0.25)`)
- High-contrast color pairing (Teacher Blue vs. Student Orange) ensures unambiguous multi-cursor recognition.

---

## 3. Event Capture, Throttling & Synchronization

### 3.1 30ms Throttled Cursor Reporting
- Mouse selections and arrow navigation trigger `onDidChangeCursorPosition` and `onDidChangeCursorSelection` at high frequencies.
- High-frequency events are throttled with a 30ms window, bounding transmission rate to ~33 FPS. This ensures smooth tracking while preventing network congestion.

### 3.2 Anti-Loop Mutex (`isApplyingRemoteUpdate`)
- **Echo Loop Hazard**:
  Teacher types `'A'` -> WebSocket pushes to student -> Student executes edits -> Triggers student's `onDidChangeModelContent` -> Student reports edit back to server -> Infinite feedback loop.
- **Implementation**:
  ```javascript
  let isApplyingRemoteUpdate = false;

  function applyRemoteChanges(changes) {
      isApplyingRemoteUpdate = true;
      try {
          editor.executeEdits('teacher-remote', changes);
      } finally {
          setTimeout(() => { isApplyingRemoteUpdate = false; }, 0);
      }
  }

  editor.onDidChangeModelContent((e) => {
      if (isApplyingRemoteUpdate) return; // Prevent echo loop!
      // Send student input...
  });
  ```

---

## 4. Frontend Fault Tolerance & Reconnection State Machine

### 4.1 Exponential Backoff Reconnection
- State transition model:
  ```text
  [DISCONNECTED] --(connect)--> [CONNECTING] --(handshake OK)--> [CONNECTED]
                                     │                                │
                             (connection error)             (heartbeat timeout)
                                     ↓                                ↓
                        [RECONNECTING] <──────────────────────────────┘
                               │ (Intervals: 1s, 2s, 4s, 8s, 10s)
                               ├─[<=5 attempts]─> retry connect()
                               └─[>5 attempts]──> [DISCONNECTED] -> Show "Manual Reconnect" button
  ```
- Backoff delays: `[1000, 2000, 4000, 8000, 10000]` ms.

### 4.2 Offline Queue & Snapshot Recovery
- Critical non-throttled edits during brief network disruptions are held in `State.offlineQueue`.
- Upon successful reconnection:
  1. Client sends `reconnect_sync` with `lastSeqId`.
  2. Server responds with missing deltas or a fresh `code_full` snapshot.
  3. Client overwrites state and flushes pending buffered changes.

### 4.3 Status Indicators & Read-Only Lecture Lock
- **Header Status Pill**:
  - 🟢 Green `CONNECTED`: Active session with round-trip ping time (e.g., `12ms`).
  - 🟡 Yellow `RECONNECTING`: Reconnecting attempt X/5 with warning indicator.
  - 🔴 Red `DISCONNECTED`: Disconnected with a manual reconnect action button.
- **Lecture Priority Mode (Read-Only Lock)**:
  - When the teacher engages Exclusive Mode, student editors dynamically switch to `readOnly: true` with a 🔒 lock badge.
  - When released, full two-way editing is restored automatically.
