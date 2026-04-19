# Optimix: Intelligent SQL Query Optimizer

Optimix is a cross-platform desktop application designed to parse, analyze, and optimize complex SQL queries. It acts as a local SQL compiler tool, utilizing both rule-based transformations and cost-based algorithms to improve database query execution plans.

## Core Engine Features

* **Multi-Tier Rule-Based Optimization:** Implements over 40 distinct SQL optimization patterns across three tiers, including Predicate Pushdown, Subquery Unnesting, Constant Folding, and Join Predicate Move-Around.
* **Cost-Based Join Optimizer:** Evaluates multiple execution paths and utilizes a cost-based calculation system to determine the most efficient query.
* **Security & Authentication:** Manages secure user sessions via JWT and Google OAuth, utilizing Bcrypt for credential hashing.
* **Local-First Architecture:** Runs entirely offline using an embedded SQLite database for state management, with a standalone Java backend processing the SQL syntax trees via JSqlParser.

## Technology Stack

**Frontend (Desktop Client):**
* React + TypeScript
* Vite Bundler
* Electron (Packaged for Windows x64 & macOS arm64)

**Backend (Optimization Engine):**
* Java 17
* Javalin (Lightweight Web Framework)
* SQLite & JDBC
* Maven (Build & Dependency Management)

## Installation & Usage

Optimix is distributed as a standalone desktop installer. No command-line setup or server configuration is required.

1. Navigate to the [Releases](../../releases) page.
2. Download the appropriate installer for your operating system:
   * **Windows:** `Optimix Setup 1.0.0.exe`
   * **macOS:** `Optimix-1.0.0-arm64.dmg`
3. Install and run the application. 

---
*Developed by Nandani Bhati*