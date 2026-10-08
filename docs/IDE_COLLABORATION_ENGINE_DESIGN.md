# IntelliJ IDEA Collaboration Plugin: Event Listening & Remote Cursor/Selection Rendering Engine Specification

> **Target Platform**: IntelliJ Platform SDK 2024.1 ~ 2026.x  
> **Primary Language**: Kotlin 2.0.0+ (JVM 21)  
> **Architecture Goal**: High-concurrency, low-latency, zero-intrusion, production-grade teacher-student collaborative editing with overlay multi-cursor/selection rendering.

---

## 1. System Architecture Overview

In educational live coding, the teacher conducts lectures using a host IntelliJ IDEA instance, while students (via web browsers or lightweight clients) observe or interact in real time. The IDE core layer resolves two symmetric challenges:
1. **Upstream Capture (Teacher -> Students)**: Captures document mutations, cursor movements, selection changes, and active tab transitions with zero dead-zones. Events are aggregated and debounced before broadcasting to all students.
2. **Downstream Rendering & Safe Writes (Students -> Teacher)**: Safely ingests student edits into the host document while suppressing recursive self-notifications (preventing infinite echo loops). Simultaneously renders student remote orange cursors, translucent selection highlights, and floating name badges non-invasively over the teacher's active editor.

```text
+---------------------------------------------------------------------------------------------------+
|                                   IntelliJ IDEA Viewport (Editor)                                 |
|                                                                                                   |
|   1  fun calculateTotal(orders: List<Order>): Double {                                            |
|   2      var sum = 0.0                                                                            |
|   3      for (order in orders) {                                                                  |
|   4          // Teacher native cursor & insertion point                                           |
|   5          sum += order.price * [0.85]  <-- Teacher native selection                            |
|   6      }                      |                                                                 |
|   7                             |-- 👨‍🎓 Student: Alice (Orange floating name badge)                |
|   8                             |   (2px vertical orange cursor line with translucent selection)  |
+---------------------------------------------------------------------------------------------------+
       ▲                                                                       │
       │ Downstream: Student write-back & overlay rendering                    │ Upstream: Teacher event capture
       │                                                                       ▼
+----------------------------------+                   +--------------------------------------------+
|   RemoteSyncEngine (Downstream)  |                   |     TeacherEventListener (Upstream)        |
| - EDT Scheduling (invokeLater)   |                   | - DocumentListener (Mutation Diff/Delta)   |
| - Safe WriteCommandAction writes |                   | - CaretListener (Line/Column/Offset)       |
| - RemoteApplyGuard (Anti-loop)   |                   | - SelectionListener (Selection Range)      |
| - RemoteCursorManager (Overlays) |                   | - FileEditorManagerListener (Tab Switches) |
+----------------------------------+                   +--------------------------------------------+
       ▲                                                                       │
       │ WebSocket / Network Layer                                             │ Debounce / Delta Broadcast
       +============================= Collaboration Netty Channel =============+
```

---

## 2. IntelliJ Platform SDK Configuration (2024 ~ 2026)

From IntelliJ 2024 onward, JetBrains adopted the `org.jetbrains.intellij.platform` Gradle plugin (2.x), replacing the legacy 1.x plugin and requiring JDK 21.

### 2.1 `build.gradle.kts` Production Configuration
```kotlin
plugins {
    id("java")
    alias(libs.plugins.kotlin.jvm) // kotlin("jvm") version "2.0.0"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.eduplus.ide"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2024.1.7")
        pluginVerifier()
        zipSigner()
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.google.code.gson:gson:2.11.0")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id.set("com.eduplus.ide.collaboration")
        name.set("EduPlus Realtime Collaborative Teaching")
        version.set(project.version.toString())
        ideaVersion {
            sinceBuild.set("241")
            untilBuild.set("261.*")
        }
    }
}
```

### 2.2 `plugin.xml` Declarations
```xml
<idea-plugin>
    <id>com.eduplus.ide.collaboration</id>
    <name>EduPlus Realtime Collaborative Teaching</name>
    <vendor email="support@eduplus.com">EduPlus Team</vendor>
    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
        <projectService
            serviceInterface="com.eduplus.collab.service.TeachingSessionService"
            serviceImplementation="com.eduplus.collab.service.TeachingSessionService"/>
        <projectService
            serviceImplementation="com.eduplus.collab.render.RemoteCursorManager"/>
    </extensions>
</idea-plugin>
```

---

## 3. IDE Event Listening Architecture (Upstream Capture)

### 3.1 Document Modification: `DocumentListener`
- Captures `offset`, `oldLength`, and `newFragment`.
- Computes character-level differences (`code_delta`) for transmission over WebSocket.

### 3.2 Cursor & Selection: `CaretListener` & `SelectionListener`
- `CaretEvent`: Extracts caret physical offset, logical line, and column.
- `SelectionEvent`: Extracts selection range `[startOffset, endOffset]`.
- Throttled using Kotlin coroutines (`Channel.CONFLATED` or `debounce(30ms)`) to prevent packet floods during rapid typing.

### 3.3 Active Tab Transitions: `FileEditorManagerListener`
- Listens to `selectionChanged`:
  1. Retrieves target `VirtualFile` and relative path;
  2. Extracts document text and computes SHA-256 integrity hash;
  3. Broadcasts `code_full` snapshot to synchronize student views;
  4. Disposes previous remote highlighters and re-attaches to the newly selected editor.

---

## 4. Echo-Loop Prevention Architecture

### 4.1 Root Cause of Feedback Loops
```text
Student edit arrives -> Written to teacher Document
                              │
    [Infinite Loop Hazard!]   ▼
Document modified -> DocumentListener fires -> Misinterpreted as teacher typing -> Rebroadcasted -> ...
```

### 4.2 Three Layers of Defense
1. **Document-Scoped `UserDataHolder` Marker**:
   Tagging the document with `IS_REMOTE_SYNC_KEY = Key.create<Boolean>("...")` before applying remote edits and clearing it in `finally`.
2. **ThreadLocal Mutex (`RemoteApplyGuard`)**:
   Ensures that calls executed on the EDT within `RemoteApplyGuard.runGuarded { ... }` suppress upstream broadcast handlers.
3. **Command Name Segregation & Undo Isolation**:
   Isolates remote edits under specific command names (e.g., `"EduPlus_Remote_Apply"`) so teacher's local `Ctrl+Z` undo stack remains untouched.

---

## 5. Threading Model & Safe Document Ingestion

1. **Read Action**: Reading document content or inspecting the PSI tree requires a Read Action.
2. **Write Action**: Mutating documents requires `WriteCommandAction` executed strictly on the Event Dispatch Thread (EDT).
3. **Background Isolation**: WebSocket IO threads never invoke `WriteCommandAction` directly; tasks are dispatched via `ApplicationManager.getApplication().invokeLater(...)`.

---

## 6. Remote Cursor & Selection Overlay Engine

### 6.1 Architectural Trade-Off: InlayModel vs. MarkupModel

| Feature | Option A: InlayModel | Option B: MarkupModel (`RangeHighlighter` + `CustomHighlighterRenderer`) |
|:---|:---|:---|
| **Impact on Text Layout** | **Intrusive**. Shifts downstream text horizontally. | **Zero Intrusion**. Pure overlay layer; does not alter text layout. |
| **Selection Support** | Poor. Only supports point insertion. | **Native**. Supports `TextAttributes` background coloring across multi-line ranges. |
| **Caret Offset Tracking**| Basic. | **Excellent**. Built-in `RangeMarker` tracks offsets during text insertions. |
| **Industry Benchmark** | Used for Inlay Hints. | **JetBrains Code With Me (CWM)** core approach. |

**Selected Strategy**: **MarkupModel + CustomHighlighterRenderer**.

### 6.2 Visual Rendering Architecture
Each connected student maintains a visual session comprising:
1. **Selection Highlighter**: Translucent orange background (`rgba(255, 160, 0, 0.25)`).
2. **Caret Highlighter**: A 2px solid orange vertical line (`#FF6D00`) with a floating name capsule tag (`👨‍🎓 Student: Alice`).
3. **Pure CSS Badging on Web Client**: On the student Monaco editor, floating badges use pure CSS `::before` pseudo-elements to avoid DOM mutations that could reset student caret positions.

---

## 7. Lifecycle & Memory Leak Protection

1. **Highlighter Teardown on Tab Switch**:
   When active editors change or close, `clearAll(editor)` safely cleans handles from the previous editor's `MarkupModel`, preventing cross-editor `RangeHighlighterTree` exceptions.
2. **Inactivity Watchdog**:
   Students with no heartbeat or activity for >30 seconds have their highlighters automatically pruned.
3. **Project Disposal**:
   Implements `Disposable` to ensure full cleanup upon project closure.
